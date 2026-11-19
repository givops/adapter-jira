package io.givops.ticketing.jira;

import static io.givops.ticketing.jira.JiraConnection.invalidConfiguration;
import static io.givops.ticketing.jira.JiraPayload.invalidResponse;
import static io.givops.ticketing.jira.JiraTransport.limitExceeded;

import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketBrowserModels.*;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketScopeModels.Scope;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.IssueObservation;
import io.givops.ticketing.port.TicketProviderAdapter.Access;
import io.givops.ticketing.port.TicketRequestGate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/** Transient Cloud browser. No description is mapped to an observation or a persistent model. */
final class JiraBrowser {
    private static final List<String> FIELDS = List.of("project", "summary", "issuetype", "status", "priority",
            "resolution", "created", "updated", "resolutiondate", "fixVersions", "assignee", "labels");
    private static final BrowseDescriptor DESCRIPTOR = new BrowseDescriptor(
            List.of("text", "statuses", "categories", "labels:any"), List.of(Lifecycle.ACTIVE),
            List.of("key-asc", "updated-desc"), false, true, false);
    private final JiraAccess credentials;
    private final TicketProperties limits;
    private final JiraTransport transport;
    private final Clock clock;
    private final ObjectReader json;
    private final ObjectWriter writer;

    JiraBrowser(JiraAccess credentials, TicketProperties limits, JiraTransport transport, JsonMapper mapper, Clock clock) {
        this.credentials = credentials; this.limits = limits; this.transport = transport; this.clock = clock;
        json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        writer = mapper.writer();
    }

    BrowseDescriptor describe(BrowserRequest request, Access access, TicketRequestGate gate) {
        return describe(request, null, access, gate);
    }
    BrowseDescriptor describe(BrowserRequest request, Scope scope, Access access, TicketRequestGate gate) {
        var read = new Read(request, scope, access, gate);
        read.finish();
        return DESCRIPTOR;
    }

    TicketPage list(BrowserRequest request, BrowseQuery query, Access access, TicketRequestGate gate) {
        return list(request, null, query, access, gate);
    }
    TicketPage list(BrowserRequest request, Scope scope, BrowseQuery query, Access access, TicketRequestGate gate) {
        var read = new Read(request, scope, access, gate);
        validate(query);
        var body = new LinkedHashMap<String, Object>();
        body.put("jql", jql(request.externalProjectId(), scope, query)); body.put("fields", JiraScope.fields(FIELDS, scope));
        body.put("maxResults", query.pageSize());
        String previous = read.continuation(query);
        if (previous != null) body.put("nextPageToken", previous);
        byte[] bytes;
        try { bytes = writer.writeValueAsBytes(body); }
        catch (JacksonException exception) { throw invalidConfiguration(); }
        JsonNode root = read.request("/search/jql", bytes);
        JsonNode rows = JiraPayload.array(root, "issues", query.pageSize());
        Boolean last = JiraPayload.flag(root, "isLast");
        String next = JiraPayload.text(root, "nextPageToken", false);
        if (last == null || last && next != null || !last && (next == null || rows.isEmpty())) throw invalidResponse();
        if (next != null && (!validToken(next) || next.equals(previous))) throw invalidResponse();
        var items = new ArrayList<TicketSummary>();
        var ids = new HashSet<String>(); var keys = new HashSet<String>();
        gate.acceptedObjects(rows.size());
        for (var row : rows) {
            var item = read.summary(row);
            if (!ids.add(item.ref().externalIssueId()) || !keys.add(item.displayKey())) throw invalidResponse();
            items.add(item);
        }
        String continuation = read.continuation(query, next);
        read.finish();
        // Enhanced Cloud search does not promise an exact total or a frozen snapshot.
        return new TicketPage(items, continuation, null, read.revision, request.capturedAt(), query);
    }

    TicketDetail detail(BrowserRequest request, TicketRef ticket, Access access, TicketRequestGate gate) {
        return detail(request, null, ticket, access, gate);
    }
    TicketDetail detail(BrowserRequest request, Scope scope, TicketRef ticket, Access access, TicketRequestGate gate) {
        var read = new Read(request, scope, access, gate);
        read.validate(ticket);
        JsonNode row = read.request("/issue/" + ticket.externalIssueId() + "?fields=" + String.join(",", JiraScope.fields(FIELDS, scope)), null);
        var observation = read.observation(row);
        read.requireIdentity(ticket, observation);
        gate.acceptedObjects(1);
        var result = new TicketDetail(read.summary(row), observation.createdAt(), observation.assigneeAccountId(),
                observation.releaseVersionIds(), new Navigation(read.connection.browse(request.projectKey()), observation.webUrl()));
        read.finish();
        return result;
    }

    TicketContent content(BrowserRequest request, TicketRef ticket, Access access, TicketRequestGate gate) {
        return content(request, null, ticket, access, gate);
    }
    TicketContent content(BrowserRequest request, Scope scope, TicketRef ticket, Access access, TicketRequestGate gate) {
        var read = new Read(request, scope, access, gate);
        read.validate(ticket);
        JsonNode row = read.request("/issue/" + ticket.externalIssueId() + "?fields=" + String.join(",", JiraScope.fields(List.of("project", "description"), scope)), null);
        read.requireIdentity(ticket, read.observation(row));
        gate.acceptedObjects(1);
        String text = JiraPlainText.description(row.get("fields").get("description"), read.gate);
        read.finish();
        return new TicketContent(ticket, read.revision, ContentFormat.PLAIN_TEXT, text, List.of(), List.of());
    }

    private static void validate(BrowseQuery query) {
        if (query == null || !DESCRIPTOR.lifecycles().contains(query.lifecycle()) || !DESCRIPTOR.sorts().contains(query.sort())
                || query.text().codePoints().anyMatch(Character::isISOControl)
                || query.continuation() != null && !validToken(query.continuation())) throw invalidConfiguration();
        for (var family : List.of(query.statuses(), query.categories(), query.labels())) {
            for (var value : family) {
                if (value.isBlank() || value.length() > 256 || value.codePoints().anyMatch(Character::isISOControl))
                    throw invalidConfiguration();
            }
        }
    }

    private static boolean validToken(String token) {
        return !token.isBlank() && token.length() <= 4096 && token.codePoints().noneMatch(Character::isISOControl);
    }

    private static String jql(String project, Scope scope, BrowseQuery query) {
        var predicates = new ArrayList<String>(); predicates.add("project = " + project);
        if (scope != null) predicates.add(JiraScope.predicate(scope));
        if (!query.text().isEmpty()) {
            // Quote a Lucene phrase, then quote that value again for JQL. Never accept raw JQL.
            String literal = query.text().replaceAll("([+\\-!(){}\\[\\]^\"~*?:\\\\/&|])", "\\\\$1");
            String summary = "summary ~ " + quote("\"" + literal + "\"");
            predicates.add(JiraPayload.ISSUE_KEY.matcher(query.text()).matches()
                    ? "(key = " + quote(query.text()) + " OR " + summary + ")" : summary);
        }
        addFamily(predicates, "status", query.statuses());
        addFamily(predicates, "issuetype", query.categories());
        addFamily(predicates, "labels", query.labels());
        return String.join(" AND ", predicates) + (query.sort().equals("key-asc")
                ? " ORDER BY key ASC" : " ORDER BY updated DESC, key ASC");
    }

    private static void addFamily(List<String> predicates, String field, List<String> values) {
        if (!values.isEmpty()) predicates.add(field + " in (" + values.stream().distinct().sorted()
                .map(JiraBrowser::quote).collect(Collectors.joining(", ")) + ")");
    }
    private static String quote(String value) { return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    private final class Read {
        private final BrowserRequest request;
        private final Scope scope;
        private final JiraAccess.Captured captured;
        private final JiraConnection connection;
        private final TicketRequestGate gate;
        private final SourceRevision revision;
        Read(BrowserRequest request, Scope scope, Access access, TicketRequestGate gate) {
            if (scope != null) JiraScope.require(scope);
            this.scope = scope;
            if (!limits.enabled()) throw new TicketException(Code.DISABLED,
                    JiraMessages.format("ticketing.jiraAdapter.ticketing.access.is.disabled"));
            gate.check();
            if (request == null || request.source() == null || request.ticketProjectId() == null || request.capturedAt() == null
                    || request.externalProjectId() == null || !JiraPayload.ID.matcher(request.externalProjectId()).matches()
                    || request.projectKey() == null || !JiraPayload.PROJECT_KEY.matcher(request.projectKey()).matches()
                    || !(access instanceof JiraAccess.Captured)) throw invalidConfiguration();
            this.request = request; this.captured = (JiraAccess.Captured) access;
            this.gate = new TicketRequestGate() {
                @Override public Duration beforeRequest() { credentials.requireCurrent(captured, false); return gate.beforeRequest(); }
                @Override public void check() { gate.check(); credentials.requireCurrent(captured, false); }
                @Override public void receivedBytes(long count) { gate.receivedBytes(count); credentials.requireCurrent(captured, false); }
                @Override public void acceptedObjects(int count) { gate.acceptedObjects(count); credentials.requireCurrent(captured, false); }
                @Override public io.givops.adapters.ConnectionTarget connectionTarget() { return gate.connectionTarget(); }
                @Override public Duration remaining() { return gate.remaining(); }
            };
            credentials.requireSource(request.source(), captured); credentials.requireCurrent(captured, false);
            connection = captured.connection();
            revision = new SourceRevision("jira-live", hash(captured.context() + "\n" + request.source().revision()
                    + "\n" + request.ticketProjectId() + "\n" + request.externalProjectId() + "\n" + request.projectKey()
                    + (scope == null ? "" : "\n" + scopeContext(scope))), false);
            if (request.revision() != null && !revision.equals(request.revision()))
                throw new TicketException(Code.STALE_CONTEXT, JiraMessages.format("jira.browser.stale"));
        }
        String scopeContext(Scope scope) {
            try { return writer.writeValueAsString(scope); }
            catch (JacksonException exception) { throw invalidConfiguration(); }
        }
        void validate(TicketRef ticket) {
            if (ticket == null || !request.source().instanceId().equals(ticket.instanceId())
                    || !request.externalProjectId().equals(ticket.externalProjectId()) || ticket.externalIssueId() == null
                    || !JiraPayload.ID.matcher(ticket.externalIssueId()).matches()) throw invalidConfiguration();
        }
        void finish() { gate.check(); }
        String continuation(BrowseQuery query) {
            if (query.continuation() == null) return null;
            try {
                JsonNode cursor = json.readTree(Base64.getUrlDecoder().decode(query.continuation()));
                JiraPayload.requireObject(cursor);
                if (cursor.size() != 3 || !revision.value().equals(JiraPayload.text(cursor, "scope", true))
                        || !queryHash(query).equals(JiraPayload.text(cursor, "query", true))) throw invalidConfiguration();
                String token = JiraPayload.text(cursor, "token", true);
                if (!validToken(token)) throw invalidConfiguration();
                return token;
            } catch (JacksonException | IllegalArgumentException exception) { throw invalidConfiguration(); }
            catch (TicketException exception) { throw invalidConfiguration(); }
        }
        String continuation(BrowseQuery query, String token) {
            if (token == null) return null;
            try {
                String result = Base64.getUrlEncoder().withoutPadding().encodeToString(writer.writeValueAsBytes(
                        java.util.Map.of("scope", revision.value(), "query", queryHash(query), "token", token)));
                if (result.length() > 4096) throw limitExceeded();
                return result;
            } catch (JacksonException exception) { throw invalidResponse(); }
        }
        String queryHash(BrowseQuery query) {
            try { return hash(writer.writeValueAsString(new BrowseQuery(query.text(), query.lifecycle(), query.statuses(),
                    query.categories(), query.labels(), query.sort(), query.pageSize(), null))); }
            catch (JacksonException exception) { throw invalidConfiguration(); }
        }
        JsonNode request(String path, byte[] body) {
            finish();
            byte[] bytes = transport.read(connection.endpoint(path), connection.authorization(), body, gate);
            try { JsonNode root = json.readTree(bytes); JiraPayload.requireObject(root); return root; }
            catch (JacksonException exception) { throw invalidResponse(); }
        }
        IssueObservation observation(JsonNode row) {
            if (!JiraScope.matches(row, scope)) throw new TicketException(Code.NOT_FOUND, JiraMessages.format("jira.scope.ticket.outside"));
            var issue = JiraPayload.issue(row, request.externalProjectId(), connection, clock.instant());
            if (scope == null) return issue;
            var eligible = new HashSet<String>();
            JsonNode versions = row.get("fields").get("fixVersions");
            if (versions != null && !versions.isNull()) {
                if (!versions.isArray()) throw invalidResponse();
                for (var version : versions) {
                    String name = JiraPayload.text(version, "name", true);
                    if (scope.canonicalName(name) != null) eligible.add(JiraReportPayload.identifier(version, "id"));
                }
            }
            return JiraScope.issue(issue, eligible);
        }
        void requireIdentity(TicketRef ticket, IssueObservation observation) {
            if (!ticket.externalIssueId().equals(observation.identity().externalIssueId())) throw invalidResponse();
        }
        TicketSummary summary(JsonNode row) {
            IssueObservation issue = observation(row);
            var labels = new ArrayList<String>();
            JsonNode values = row.get("fields").get("labels");
            if (values != null && !values.isNull()) {
                if (!values.isArray() || values.size() > 100) throw invalidResponse();
                var unique = new HashSet<String>();
                for (var label : values) {
                    if (!label.isString() || !bounded(label.stringValue(), 256) || !unique.add(label.stringValue())) throw invalidResponse();
                    labels.add(label.stringValue());
                }
            }
            if (issue.summary() == null || !bounded(issue.summary(), 4096) || !bounded(issue.status(), 256)
                    || !bounded(issue.type(), 256)) throw invalidResponse();
            Outcome outcome = switch (issue.statusCategory() == null ? "" : issue.statusCategory()) {
                case "new" -> Outcome.OPEN;
                case "indeterminate" -> Outcome.IN_PROGRESS;
                default -> Outcome.UNKNOWN;
            };
            return new TicketSummary(new TicketRef(request.source().instanceId(), request.externalProjectId(), issue.identity().externalIssueId()),
                    issue.issueKey(), issue.summary(), issue.status(), issue.type(), Lifecycle.ACTIVE, outcome, labels,
                    issue.webUrl(), issue.updatedAt(), true, revision);
        }
    }
    private static boolean bounded(String value, int max) {
        return value == null || value.length() <= max && value.codePoints().noneMatch(Character::isISOControl);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
