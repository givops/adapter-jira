package io.givops.ticketing.jira;

import io.givops.adapters.FailureCategory;
import io.givops.release.commands.ReleaseCommandModels.Configuration;
import io.givops.release.commands.ReleaseCommandModels.ExistingReport;
import io.givops.release.commands.ReleaseCommandModels.Kind;
import io.givops.release.commands.ReleaseCommandModels.Request;
import io.givops.release.commands.ReleaseCommandModels.VersionShape;
import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.port.TicketRequestGate;
import io.givops.ticketing.port.ReleaseWriteAdapter;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Jira Cloud write adapter. Existing read adapters and their replay semantics remain unchanged. */
public class JiraReleaseWriter implements ReleaseWriteAdapter {
    public static final class Session implements ReleaseWriteAdapter.Session {
        private final JiraAccess.Captured captured;
        Session(JiraAccess.Captured captured) { this.captured = captured; }
        public String context() { return captured.context(); }
        @Override public String toString() { return "JiraReleaseSession[redacted]"; }
    }
    public static final class WriteFailure extends ReleaseWriteAdapter.WriteFailure {
        public WriteFailure(String code, boolean uncertain) {
            this(code, uncertain, FailureCategory.UNKNOWN);
        }
        public WriteFailure(String code, boolean uncertain, FailureCategory category) {
            super(code, uncertain, JiraMessages.format("ticketing.jiraReleaseWriter.the.jira.operation.could.not.be.confirmed"), category);
        }
        private WriteFailure(String code, boolean uncertain, String evidence, FailureCategory category) {
            super(code, uncertain, evidence, category);
        }
    }
    @Override public TicketProvider provider() { return TicketProvider.JIRA_CLOUD; }
    private static Session owned(ReleaseWriteAdapter.Session session) {
        if (session instanceof Session jira) return jira;
        throw JiraConnection.invalidConfiguration();
    }
    private final JiraAccess credentials;
    private final JiraTransport reads;
    private final JiraWriteTransport writes;
    private final JsonMapper mapper;
    private final TicketProperties limits;
    JiraReleaseWriter(JiraAccess credentials, JiraTransport reads, JsonMapper mapper, TicketProperties limits) {
        this(credentials, reads, new JiraWriteTransport(JiraTransport.clientBuilder(limits.requestTimeout()).build(),
                limits.maxResponseBytes(), reads.connectionClients()), mapper, limits);
    }
    JiraReleaseWriter(JiraAccess credentials, JiraTransport reads, JiraWriteTransport writes, JsonMapper mapper, TicketProperties limits) {
        this.credentials = credentials; this.reads = reads; this.writes = writes; this.mapper = mapper; this.limits = limits;
    }
    public Session capture(InstanceSource source) { return new Session(credentials.capture(source)); }
    public void requireCurrent(ReleaseWriteAdapter.Session session, boolean locked) { credentials.requireCurrent(owned(session).captured, locked); }

    /** Exhaust the bounded project version listing; absence is never inferred from a partial page. */
    public String version(ReleaseWriteAdapter.Session session, String projectId, String name, TicketRequestGate gate) {
        var matches = new HashSet<String>();
        var seenIds = new HashSet<String>();
        int offset = 0;
        Integer total = null;
        for (int page = 0; page < limits.maxPages(); page++) {
            var root = read(session, "/project/" + projectId + "/version?startAt=" + offset + "&maxResults=100", null, gate);
            JsonNode values = JiraPayload.array(root, "values", 100);
            Integer actual = JiraPayload.offset(root, "startAt");
            Integer count = JiraPayload.offset(root, "total");
            Boolean last = JiraPayload.flag(root, "isLast");
            if (actual == null || actual != offset || count == null || last == null || total != null && !total.equals(count)
                    || offset + values.size() > count || last != (offset + values.size() == count)
                    || !last && values.isEmpty()) throw JiraPayload.invalidResponse();
            total = count;
            gate.acceptedObjects(values.size());
            for (var value : values) {
                String id = JiraReportPayload.identifier(value, "id");
                if (!seenIds.add(id)) throw JiraPayload.invalidResponse();
                if (!projectId.equals(JiraReportPayload.identifier(value, "projectId"))) throw JiraPayload.invalidResponse();
                if (name.equals(JiraReportPayload.text(value, "name", true))) matches.add(id);
            }
            if (last) {
                if (matches.size() > 1) throw new WriteFailure("release_jira_version_ambiguous", false, FailureCategory.INVALID_RESPONSE);
                return matches.stream().findFirst().orElse(null);
            }
            offset += values.size();
        }
        throw JiraTransport.limitExceeded();
    }

    public String createVersion(ReleaseWriteAdapter.Session session, String projectId, String name, LocalDate plannedDate, TicketRequestGate gate) {
        try {
            var root = write(session, "/version", Map.of("projectId", new java.math.BigInteger(projectId), "name", name,
                    "released", false, "archived", false, "releaseDate", plannedDate.toString()), gate);
            String id = JiraReportPayload.identifier(root, "id");
            if (!name.equals(JiraReportPayload.text(root, "name", true))
                    || !projectId.equals(JiraReportPayload.identifier(root, "projectId"))) throw JiraPayload.invalidResponse();
            return id;
        } catch (WriteFailure failure) {
            if (!failure.uncertain() && failure.code().equals("release_jira_conflict")) {
                // Jira may reject a concurrent create with 400 or 409. Recover by a fresh, explicit read only.
                String recovered = version(session, projectId, name, gate);
                if (recovered != null) return recovered;
            }
            throw failure;
        } catch (TicketException failure) {
            throw new WriteFailure("release_jira_version_response_invalid", true, ticketFailureCategory(failure));
        }
    }

    public ExistingReport duplicate(ReleaseWriteAdapter.Session session, String destinationId, Configuration config, Kind kind,
                                    String versionName, String versionId, TicketRequestGate gate) {
        String type = kind == Kind.CAMPAIGN ? config.campaignIssueTypeId() : config.hotfixIssueTypeId();
        String token = null;
        var seen = new HashSet<String>();
        int count = 0;
        for (int page = 0; page < limits.maxPages(); page++) {
            var body = new LinkedHashMap<String, Object>();
            body.put("jql", "project = " + destinationId + " AND issuetype = " + type + " ORDER BY id ASC");
            body.put("fields", List.of("summary", "project", "issuetype", config.targetProjectFieldId(), config.versionFieldId()));
            body.put("maxResults", 100);
            if (token != null) body.put("nextPageToken", token);
            var root = read(session, "/search/jql", body, gate);
            var issues = JiraPayload.array(root, "issues", 100);
            count += issues.size(); gate.acceptedObjects(issues.size());
            if (count > limits.maxReleaseIssues()) throw JiraTransport.limitExceeded();
            for (var issue : issues) {
                var fields = issue.get("fields");
                JiraPayload.requireObject(fields);
                if (!destinationId.equals(JiraReportPayload.identifier(fields.get("project"), "id"))
                        || !type.equals(JiraReportPayload.identifier(fields.get("issuetype"), "id"))) throw JiraPayload.invalidResponse();
                var target = fields.get(config.targetProjectFieldId());
                if (target == null || !target.isNull() && !target.isString()) throw JiraPayload.invalidResponse();
                if (target.isNull() || !config.targetProjectValue().equals(target.stringValue())) continue;
                var version = fields.get(config.versionFieldId());
                if (version == null || !version.isNull() && (config.versionShape() == VersionShape.STRING ? !version.isString() : !version.isArray())) {
                    throw JiraPayload.invalidResponse();
                }
                boolean match = config.versionShape() == VersionShape.STRING
                        ? version != null && version.isString() && versionName.equals(version.stringValue())
                        : containsVersion(version, versionId);
                if (match) return report(session, issue, JiraReportPayload.text(fields, "summary", true));
            }
            Boolean last = JiraPayload.flag(root, "isLast");
            String next = JiraPayload.text(root, "nextPageToken", false);
            if (Boolean.TRUE.equals(last) && next == null) return null;
            if (last == null || last || issues.isEmpty() || next == null || next.length() > 2048 || !seen.add(next)) throw JiraPayload.invalidResponse();
            token = next;
        }
        throw JiraTransport.limitExceeded();
    }

    public ExistingReport createReport(ReleaseWriteAdapter.Session session, String destinationId, Configuration config, Request request,
                                        String versionId, String assignee, TicketRequestGate gate) {
        var fields = new LinkedHashMap<String, Object>();
        fields.put("project", Map.of("id", destinationId));
        fields.put("issuetype", Map.of("id", request.kind() == Kind.CAMPAIGN ? config.campaignIssueTypeId() : config.hotfixIssueTypeId()));
        fields.put("summary", request.summary());
        fields.put("assignee", Map.of("accountId", assignee));
        fields.put("description", adf(request.description()));
        fields.put(config.targetProjectFieldId(), config.targetProjectValue());
        fields.put(config.versionFieldId(), config.versionShape() == VersionShape.STRING ? request.versionName() : List.of(Map.of("id", versionId)));
        fields.put(config.plannedDateFieldId(), request.plannedDate().toString());
        fields.put(config.desiredDeliveryDateFieldId(), request.desiredDeliveryDate().toString());
        var root = write(session, "/issue", Map.of("fields", fields), gate);
        try { return report(session, root, request.summary()); }
        catch (TicketException failure) { throw new WriteFailure("release_jira_report_response_invalid", true, ticketFailureCategory(failure)); }
    }

    private static FailureCategory ticketFailureCategory(TicketException failure) {
        return switch (failure.code()) {
            case AUTHENTICATION_FAILED, AUTHORIZATION_FAILED -> FailureCategory.ACCESS_DENIED;
            default -> FailureCategory.INVALID_RESPONSE;
        };
    }

    static Map<String, Object> adf(String text) {
        var paragraphs = new ArrayList<Object>();
        for (String line : text.split("\\R", -1)) paragraphs.add(Map.of("type", "paragraph", "content",
                line.isEmpty() ? List.of() : List.of(Map.of("type", "text", "text", line))));
        return Map.of("type", "doc", "version", 1, "content", paragraphs);
    }
    private static boolean containsVersion(JsonNode node, String id) {
        if (node == null || !node.isArray() || id == null) return false;
        for (var v : node) if (id.equals(JiraReportPayload.identifier(v, "id"))) return true;
        return false;
    }
    private ExistingReport report(ReleaseWriteAdapter.Session session, JsonNode issue, String summary) {
        String key = JiraReportPayload.text(issue, "key", true);
        if (!JiraPayload.ISSUE_KEY.matcher(key).matches()) throw JiraPayload.invalidResponse();
        return new ExistingReport(key, summary, owned(session).captured.connection().browse(key).toString());
    }
    private JsonNode read(ReleaseWriteAdapter.Session session, String path, Map<String, Object> body, TicketRequestGate gate) {
        return mapper.readTree(reads.read(owned(session).captured.connection().endpoint(path), owned(session).captured.connection().authorization(),
                body == null ? null : mapper.writeValueAsBytes(body), gate));
    }
    private JsonNode write(ReleaseWriteAdapter.Session session, String path, Object body, TicketRequestGate gate) {
        JiraWriteTransport.Response response;
        try {
            response = writes.post(owned(session).captured.connection().endpoint(path), owned(session).captured.connection().authorization(),
                    mapper.writeValueAsBytes(body), gate);
        } catch (JiraWriteTransport.Failure failure) {
            FailureCategory category = switch (failure.code()) {
                case "release_jira_timeout", "release_jira_interrupted" -> FailureCategory.TRANSIENT;
                default -> FailureCategory.UNKNOWN;
            };
            throw new WriteFailure(failure.code(), failure.uncertain(), failure.getMessage(), category);
        }
        if (response.status() != 201) {
            int status = response.status();
            String code = switch (status) {
                case 400, 409 -> "release_jira_conflict";
                case 401, 403, 404 -> "release_jira_access_denied";
                case 429 -> "release_jira_rate_limited";
                default -> "release_jira_rejected";
            };
            FailureCategory category = switch (status) {
                // Jira may hide a resource when access is revoked; all three must invalidate private observations.
                case 401, 403, 404 -> FailureCategory.ACCESS_DENIED;
                case 408, 429 -> FailureCategory.TRANSIENT;
                default -> status >= 500 ? FailureCategory.TRANSIENT : FailureCategory.INVALID_REQUEST;
            };
            throw new WriteFailure(code, status == 408 || status >= 500, response.evidence(), category);
        }
        try {
            var result = mapper.readTree(response.body());
            JiraPayload.requireObject(result);
            return result;
        } catch (RuntimeException failure) { throw new WriteFailure("release_jira_response_invalid", true, FailureCategory.INVALID_RESPONSE); }
    }
    @Override public void close() { writes.close(); }
}
