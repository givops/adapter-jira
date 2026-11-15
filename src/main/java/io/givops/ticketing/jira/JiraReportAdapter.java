package io.givops.ticketing.jira;

import static io.givops.ticketing.jira.JiraPayload.invalidResponse;
import static io.givops.ticketing.jira.JiraTransport.limitExceeded;

import io.givops.testreports.config.TestReportProperties;
import io.givops.testreports.model.TestReportProviderModels.Cardinality;
import io.givops.testreports.model.TestReportProviderModels.Diagnostic;
import io.givops.testreports.model.TestReportProviderModels.Extractor;
import io.givops.testreports.model.TestReportProviderModels.FieldMetadata;
import io.givops.testreports.model.TestReportProviderModels.FieldRef;
import io.givops.testreports.model.TestReportProviderModels.FieldState;
import io.givops.testreports.model.TestReportProviderModels.History;
import io.givops.testreports.model.TestReportProviderModels.HistoryCoverage;
import io.givops.testreports.model.TestReportProviderModels.MetadataSnapshot;
import io.givops.testreports.model.TestReportProviderModels.NamedId;
import io.givops.testreports.model.TestReportProviderModels.OrdinaryDataset;
import io.givops.testreports.model.TestReportProviderModels.OrdinaryIssue;
import io.givops.testreports.model.TestReportProviderModels.OrdinaryVersion;
import io.givops.testreports.model.TestReportProviderModels.ProbeResult;
import io.givops.testreports.model.TestReportProviderModels.ProjectMetadata;
import io.givops.testreports.model.TestReportProviderModels.RawReport;
import io.givops.testreports.model.TestReportProviderModels.ReportDataset;
import io.givops.testreports.model.TestReportProviderModels.ReportInput;
import io.givops.testreports.model.TestReportProviderModels.SourceProject;
import io.givops.testreports.model.TestReportProviderModels.StatusEvent;
import io.givops.testreports.port.TestReportProviderAdapter;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.port.TicketProviderAdapter.Access;
import io.givops.ticketing.port.TicketRequestGate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

/** Jira Cloud read operations for independently configured test report datasets. */
public final class JiraReportAdapter implements TestReportProviderAdapter {
    private static final int PAGE_SIZE = 100;
    private static final int HISTORY_BATCH = 1000;
    private static final int MAX_TOKEN_LENGTH = 8192;
    private static final History NO_HISTORY = new History(HistoryCoverage.UNAVAILABLE, List.of(), List.of(
            new Diagnostic("HISTORY_UNAVAILABLE", null, JiraMessages.format("ticketing.jiraReportAdapter.complete.provider.history.was.not.acquired"))));
    private final JiraAccess credentials;
    private final JiraTransport transport;
    private final TestReportProperties limits;
    private final Clock clock;
    private final ObjectReader reader;
    private final ObjectWriter writer;

    JiraReportAdapter(JiraAccess credentials, JiraTransport transport, TestReportProperties limits,
                      JsonMapper mapper, Clock clock) {
        this.credentials = credentials;
        this.transport = transport;
        this.limits = limits;
        this.clock = clock;
        reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        writer = mapper.writer();
    }

    @Override public TicketProvider provider() { return TicketProvider.JIRA_CLOUD; }

    @Override public Access captureAccess(InstanceSource source) { return credentials.capture(source); }

    @Override public boolean current(InstanceSource source, Access access, boolean locked) {
        if (access instanceof JiraAccess.Captured jira) {
            credentials.requireSource(source, jira);
            credentials.requireCurrent(jira, locked);
            return true;
        }
        return false;
    }

    @Override public MetadataSnapshot metadata(ReportInput input, Access access, TicketRequestGate gate) {
        return session(input, access, gate).metadata();
    }

    @Override public ProbeResult probe(ReportInput input, Access access, TicketRequestGate gate) {
        var session = session(input, access, gate);
        var samples = session.search(session.reportPredicate(), 20, true).values().stream()
                .map(row -> session.report(row, NO_HISTORY)).toList();
        var diagnostics = samples.stream().flatMap(row -> row.diagnostics().stream()).distinct().toList();
        boolean valid = !samples.isEmpty() && samples.stream().allMatch(row -> usable(row.targetState())
                && usable(row.versionsState()) && (input.mapping().family() == null || usable(row.familyState())));
        if (samples.isEmpty()) diagnostics = List.of(new Diagnostic("NO_SAMPLES", null,
                JiraMessages.format("ticketing.jiraReportAdapter.no.matching.provider.issues.were.available.to.validate.the")));
        return new ProbeResult(clock.instant(), samples.size(), valid, samples, diagnostics);
    }

    @Override public ReportDataset reports(ReportInput input, Access access, TicketRequestGate gate) {
        var session = session(input, access, gate);
        var rows = session.search(session.reportPredicate(), limits.maxReportIssues(), false);
        rows.values().forEach(row -> session.report(row, NO_HISTORY));
        var histories = session.histories(List.copyOf(rows.keySet()));
        return new ReportDataset(clock.instant(), rows.entrySet().stream()
                .map(entry -> session.report(entry.getValue(), histories.getOrDefault(entry.getKey(), NO_HISTORY))).toList());
    }

    @Override public OrdinaryDataset ordinaryVersions(ReportInput input, Access access, TicketRequestGate gate) {
        var session = session(input, access, gate);
        return session.ordinary();
    }

    private Session session(ReportInput input, Access captured, TicketRequestGate gate) {
        validate(input);
        gate.check();
        JiraConnection connection = credentials.connection(input.source(), captured);
        return new Session(input, connection, gate);
    }

    private final class Session {
        private final ReportInput input;
        private final JiraConnection connection;
        private final TicketRequestGate gate;
        private final Map<String, SourceProject> sources = new LinkedHashMap<>();
        private final List<String> fields;

        Session(ReportInput input, JiraConnection connection, TicketRequestGate gate) {
            this.input = input;
            this.connection = connection;
            this.gate = gate;
            input.sourceProjects().forEach(source -> sources.put(source.externalProjectId(), source));
            var requested = new LinkedHashSet<>(List.of("project", "summary", "issuetype", "status", "assignee",
                    "created", "updated", "fixVersions"));
            requested.add(input.mapping().targetProject().fieldId());
            requested.add(input.mapping().versions().fieldId());
            if (input.mapping().family() != null) requested.add(input.mapping().family().fieldId());
            fields = List.copyOf(requested);
        }

        String reportPredicate() {
            String projects = "project IN (" + String.join(",", sources.keySet()) + ")";
            return input.issueTypeIds().isEmpty() ? projects : projects + " AND issuetype IN ("
                    + input.issueTypeIds().stream().sorted().collect(Collectors.joining(",")) + ")";
        }

        MetadataSnapshot metadata() {
            JsonNode allFields = request("/field", null);
            if (!allFields.isArray() || allFields.isEmpty()) throw invalidResponse();
            gate.acceptedObjects(allFields.size());
            var metadata = new LinkedHashMap<String, FieldMetadata>();
            for (JsonNode row : allFields) {
                JiraPayload.requireObject(row);
                String id = JiraReportPayload.text(row, "id", true);
                if (!validFieldId(id)) throw invalidResponse();
                JsonNode schema = row.get("schema");
                if (schema != null && !schema.isNull()) JiraPayload.requireObject(schema);
                String type = JiraReportPayload.text(schema, "type", false);
                String items = JiraReportPayload.text(schema, "items", false);
                List<Extractor> extractors = switch (type == null ? "" : type) {
                    case "string" -> List.of(Extractor.STRING);
                    case "project" -> List.of(Extractor.PROJECT_ID, Extractor.PROJECT_KEY);
                    case "option" -> List.of(Extractor.OPTION_ID);
                    case "array" -> "version".equals(items) ? List.of(Extractor.VERSION_IDS) : List.of();
                    default -> List.of();
                };
                var value = new FieldMetadata(id, JiraReportPayload.text(row, "name", true), type, items,
                        "array".equals(type) ? Cardinality.MANY : Cardinality.ONE, extractors);
                if (metadata.putIfAbsent(id, value) != null) throw invalidResponse();
            }
            var projects = new ArrayList<ProjectMetadata>();
            for (SourceProject source : input.sourceProjects()) {
                JsonNode rows = request("/project/" + source.externalProjectId() + "/statuses", null);
                if (!rows.isArray() || rows.isEmpty()) throw invalidResponse();
                gate.acceptedObjects(rows.size());
                var types = new LinkedHashMap<String, NamedId>();
                var statuses = new LinkedHashMap<String, NamedId>();
                for (JsonNode row : rows) {
                    var type = JiraReportPayload.named(row);
                    putNamed(types, type);
                    JsonNode statusRows = JiraPayload.array(row, "statuses", 10000);
                    gate.acceptedObjects(statusRows.size());
                    for (JsonNode status : statusRows) putNamed(statuses, JiraReportPayload.named(status));
                }
                projects.add(new ProjectMetadata(source.ticketProjectId(), List.copyOf(types.values()), List.copyOf(statuses.values())));
            }
            return new MetadataSnapshot(clock.instant(), List.copyOf(metadata.values()), projects, List.of());
        }

        Map<String, JsonNode> search(String predicate, int maximum, boolean probe) {
            var rows = new LinkedHashMap<String, JsonNode>();
            var keys = new HashMap<String, String>();
            var tokens = new HashSet<String>();
            String token = null;
            for (int page = 0; page < limits.maxPages(); page++) {
                int pageSize = probe ? 20 : Math.min(PAGE_SIZE, maximum - rows.size() + 1);
                var body = new LinkedHashMap<String, Object>();
                body.put("jql", predicate + " ORDER BY id ASC");
                body.put("fields", fields);
                body.put("maxResults", pageSize);
                if (token != null) body.put("nextPageToken", token);
                JsonNode root = request("/search/jql", body);
                JsonNode issues = JiraPayload.array(root, "issues", pageSize);
                gate.acceptedObjects(issues.size());
                for (JsonNode row : issues) {
                    String id = JiraReportPayload.identifier(row, "id");
                    String key = JiraReportPayload.text(row, "key", true);
                    if (!JiraPayload.ISSUE_KEY.matcher(key).matches()) throw invalidResponse();
                    String priorId = keys.putIfAbsent(key, id);
                    JsonNode previous = rows.putIfAbsent(id, row);
                    if (priorId != null && !priorId.equals(id) || previous != null && !previous.equals(row)) throw invalidResponse();
                }
                if (rows.size() > maximum) throw limitExceeded();
                Boolean last = JiraPayload.flag(root, "isLast");
                String next = JiraPayload.text(root, "nextPageToken", false);
                if (probe) {
                    if (last == null || Boolean.TRUE.equals(last) && next != null
                            || !Boolean.TRUE.equals(last) && !validToken(next)) throw invalidResponse();
                    return rows;
                }
                if (Boolean.TRUE.equals(last) && next == null) return rows;
                if (last == null || Boolean.TRUE.equals(last) || issues.isEmpty() || !validToken(next) || !tokens.add(next)) {
                    throw invalidResponse();
                }
                token = next;
            }
            throw limitExceeded();
        }

        RawReport report(JsonNode row, History history) {
            JsonNode values = row.get("fields");
            JiraPayload.requireObject(values);
            String projectId = JiraReportPayload.identifier(values.get("project"), "id");
            SourceProject source = sources.get(projectId);
            if (source == null) throw invalidResponse();
            var type = JiraReportPayload.named(values.get("issuetype"));
            if (!input.issueTypeIds().isEmpty() && !input.issueTypeIds().contains(type.id())) throw invalidResponse();
            var target = JiraReportPayload.extract(values, input.mapping().targetProject(), source.ticketProjectId());
            var versions = JiraReportPayload.extract(values, input.mapping().versions(), source.ticketProjectId());
            var family = JiraReportPayload.extract(values, input.mapping().family(), source.ticketProjectId());
            var diagnostics = new ArrayList<Diagnostic>();
            diagnostics.addAll(JiraReportPayload.diagnostics(input.mapping().targetProject(), target.state()));
            diagnostics.addAll(JiraReportPayload.diagnostics(input.mapping().versions(), versions.state()));
            diagnostics.addAll(JiraReportPayload.diagnostics(input.mapping().family(), family.state()));
            String key = JiraReportPayload.text(row, "key", true);
            return new RawReport(JiraReportPayload.identifier(row, "id"), key, connection.browse(key).toString(),
                    source.ticketProjectId(), JiraReportPayload.text(values, "summary", true), type,
                    JiraReportPayload.named(values.get("status")), JiraReportPayload.assignee(values),
                    JiraReportPayload.instant(values, "created", true), JiraReportPayload.instant(values, "updated", true),
                    target.scalar(), target.state(), JiraReportPayload.versions(versions), versions.state(),
                    family.scalar(), family.state(), history, diagnostics);
        }

        Map<String, History> histories(List<String> issueIds) {
            var results = new LinkedHashMap<String, History>();
            int totalEvents = 0;
            for (int start = 0; start < issueIds.size(); start += HISTORY_BATCH) {
                List<String> batch = issueIds.subList(start, Math.min(start + HISTORY_BATCH, issueIds.size()));
                var requested = Set.copyOf(batch);
                var collected = new LinkedHashMap<String, LinkedHashMap<String, StatusEvent>>();
                var incomplete = new HashSet<String>();
                var unavailable = new HashSet<String>();
                var eventCounts = new HashMap<String, Integer>();
                var seenHistories = new HashMap<String, JsonNode>();
                var tokens = new HashSet<String>();
                String token = null;
                boolean complete = false;
                for (int page = 0; page < limits.maxPages(); page++) {
                    var body = new LinkedHashMap<String, Object>();
                    body.put("issueIdsOrKeys", batch);
                    body.put("fieldIds", List.of("status"));
                    body.put("maxResults", HISTORY_BATCH);
                    if (token != null) body.put("nextPageToken", token);
                    JsonNode root = request("/changelog/bulkfetch", body);
                    JsonNode changes = JiraPayload.array(root, "issueChangeLogs", HISTORY_BATCH);
                    gate.acceptedObjects(changes.size());
                    for (JsonNode change : changes) {
                        String issueId = JiraReportPayload.identifier(change, "issueId");
                        if (!requested.contains(issueId)) throw invalidResponse();
                        var events = collected.computeIfAbsent(issueId, ignored -> new LinkedHashMap<>());
                        JsonNode historyValues = change.get("changeHistories");
                        if (historyValues == null || historyValues.isNull()) {
                            unavailable.add(issueId);
                            continue;
                        }
                        JsonNode histories = JiraPayload.array(change, "changeHistories", HISTORY_BATCH);
                        gate.acceptedObjects(histories.size());
                        for (JsonNode history : histories) {
                            String historyId = JiraReportPayload.identifier(history, "id");
                            JsonNode previous = seenHistories.putIfAbsent(issueId + ":" + historyId, history);
                            if (previous != null) {
                                if (!previous.equals(history)) throw invalidResponse();
                                continue;
                            }
                            Instant at = JiraReportPayload.instant(history, "created", false);
                            JsonNode itemValues = history.get("items");
                            if (itemValues == null || itemValues.isNull()) {
                                incomplete.add(issueId);
                                continue;
                            }
                            JsonNode items = JiraPayload.array(history, "items", 1000);
                            gate.acceptedObjects(items.size());
                            int index = 0;
                            for (JsonNode item : items) {
                                JiraPayload.requireObject(item);
                                String field = JiraReportPayload.text(item, "fieldId", false);
                                if (field == null) field = JiraReportPayload.text(item, "field", false);
                                if (!"status".equals(field)) { index++; continue; }
                                String from = JiraReportPayload.text(item, "from", false);
                                String to = JiraReportPayload.text(item, "to", false);
                                String fromName = JiraReportPayload.text(item, "fromString", false);
                                String toName = JiraReportPayload.text(item, "toString", false);
                                if (at == null || from == null || to == null || fromName == null || toName == null) incomplete.add(issueId);
                                if (at != null) events.put(historyId + ":" + index,
                                        new StatusEvent(historyId + ":" + index, at, from, fromName, to, toName));
                                index++;
                                if (eventCounts.merge(issueId, 1, Integer::sum) > limits.maxEventsPerIssue()
                                        || ++totalEvents > limits.maxEvents()) throw limitExceeded();
                            }
                        }
                    }
                    String next = JiraPayload.text(root, "nextPageToken", false);
                    if (next == null) { complete = true; break; }
                    if (changes.isEmpty() || !validToken(next) || !tokens.add(next)) throw invalidResponse();
                    token = next;
                }
                if (!complete) throw limitExceeded();
                collected.forEach((id, events) -> results.put(id, new History(
                        unavailable.contains(id) ? HistoryCoverage.UNAVAILABLE
                                : incomplete.contains(id) ? HistoryCoverage.INCOMPLETE : HistoryCoverage.COMPLETE,
                        events.values().stream().sorted(Comparator.comparing(StatusEvent::at).thenComparing(StatusEvent::id)).toList(),
                        unavailable.contains(id) ? NO_HISTORY.diagnostics()
                                : incomplete.contains(id) ? List.of(new Diagnostic("HISTORY_INCOMPLETE", "status",
                                JiraMessages.format("ticketing.jiraReportAdapter.some.provider.status.history.values.were.missing.or.redacted"))) : List.of())));
            }
            return results;
        }

        OrdinaryDataset ordinary() {
            var versions = new ArrayList<OrdinaryVersion>();
            var issues = new LinkedHashMap<String, JsonNode>();
            var projects = new LinkedHashMap<String, UUID>();
            input.ordinaryProjects().forEach(project -> {
                UUID previous = projects.putIfAbsent(project.externalProjectId(), project.ticketProjectId());
                if (previous != null && !previous.equals(project.ticketProjectId())) throw invalidResponse();
            });
            var knownVersions = new HashMap<UUID, Set<String>>();
            projects.forEach((externalProject, ticketProject) -> {
                List<OrdinaryVersion> projectVersions = versions(externalProject, ticketProject);
                versions.addAll(projectVersions);
                knownVersions.put(ticketProject, projectVersions.stream().map(OrdinaryVersion::externalVersionId).collect(Collectors.toSet()));
                if (!projectVersions.isEmpty()) {
                    var projectIssues = search("project = " + externalProject + " AND fixVersion IS NOT EMPTY",
                            limits.maxOrdinaryIssues() - issues.size(), false);
                    projectIssues.forEach((id, row) -> {
                        JsonNode values = row.get("fields");
                        JiraPayload.requireObject(values);
                        if (!externalProject.equals(JiraReportPayload.identifier(values.get("project"), "id"))) {
                            throw invalidResponse();
                        }
                        JsonNode previous = issues.putIfAbsent(id, row);
                        if (previous != null && !previous.equals(row)) throw invalidResponse();
                    });
                }
            });
            var histories = histories(List.copyOf(issues.keySet()));
            var observed = new ArrayList<OrdinaryIssue>();
            issues.forEach((id, row) -> {
                JsonNode fields = row.get("fields");
                JiraPayload.requireObject(fields);
                String externalProject = JiraReportPayload.identifier(fields.get("project"), "id");
                UUID ticketProject = projects.get(externalProject);
                if (ticketProject == null) throw invalidResponse();
                var versionValues = JiraReportPayload.extract(fields, new FieldRef("fixVersions", Extractor.VERSION_IDS), ticketProject);
                var memberIds = versionValues.versions().stream().map(version -> version.externalVersionId()).toList();
                if (versionValues.state() != FieldState.PRESENT || !knownVersions.get(ticketProject).containsAll(memberIds)) throw invalidResponse();
                var family = JiraReportPayload.extract(fields, input.mapping().family(), ticketProject);
                JsonNode status = fields.get("status");
                var category = status == null ? null : status.get("statusCategory");
                if (category != null && !category.isNull()) JiraPayload.requireObject(category);
                String key = JiraReportPayload.text(row, "key", true);
                observed.add(new OrdinaryIssue(ticketProject, id, key, connection.browse(key).toString(),
                        JiraReportPayload.text(fields, "summary", true), JiraReportPayload.named(fields.get("issuetype")),
                        JiraReportPayload.named(status), JiraReportPayload.text(category, "key", false),
                        JiraReportPayload.assignee(fields), JiraReportPayload.instant(fields, "created", true),
                        JiraReportPayload.instant(fields, "updated", true), memberIds, family.scalar(), family.state(),
                        histories.getOrDefault(id, NO_HISTORY)));
            });
            return new OrdinaryDataset(clock.instant(), versions, observed);
        }

        List<OrdinaryVersion> versions(String projectId, UUID ticketProject) {
            var versions = new LinkedHashMap<String, OrdinaryVersion>();
            int start = 0;
            Integer total = null;
            for (int page = 0; page < limits.maxPages(); page++) {
                JsonNode root = request("/project/" + projectId + "/version?startAt=" + start + "&maxResults=" + PAGE_SIZE, null);
                Integer offset = JiraPayload.offset(root, "startAt");
                Integer reportedTotal = JiraPayload.offset(root, "total");
                Integer size = JiraPayload.offset(root, "maxResults");
                Boolean last = JiraPayload.flag(root, "isLast");
                JsonNode values = JiraPayload.array(root, "values", PAGE_SIZE);
                if (offset == null || offset != start || reportedTotal == null || size == null || size < 1
                        || size > PAGE_SIZE || values.size() > size || last == null || total != null && !total.equals(reportedTotal)
                        || start + values.size() > reportedTotal || last != (start + values.size() == reportedTotal)
                        || !last && values.isEmpty()) throw invalidResponse();
                total = reportedTotal;
                gate.acceptedObjects(values.size());
                for (JsonNode value : values) {
                    var version = JiraPayload.version(value, projectId, clock.instant());
                    var converted = new OrdinaryVersion(ticketProject, version.externalVersionId(), version.name(),
                            version.released(), version.archived(), version.releaseDate());
                    if (versions.putIfAbsent(converted.externalVersionId(), converted) != null) throw invalidResponse();
                }
                if (last) return List.copyOf(versions.values());
                start += values.size();
            }
            throw limitExceeded();
        }

        JsonNode request(String path, Map<String, Object> body) {
            gate.check();
            try {
                byte[] requestBody = body == null ? null : writer.writeValueAsBytes(body);
                byte[] response = transport.read(connection.endpoint(path), connection.authorization(), requestBody, gate, limits.maxResponseBytes());
                JsonNode root = reader.readTree(response);
                if (root == null || !root.isObject() && !root.isArray()) throw invalidResponse();
                return root;
            } catch (JacksonException exception) {
                throw invalidResponse();
            }
        }
    }

    private static void putNamed(Map<String, NamedId> values, NamedId value) {
        NamedId previous = values.putIfAbsent(value.id(), value);
        if (previous != null && !previous.equals(value)) throw invalidResponse();
    }

    private static boolean validToken(String token) {
        return token != null && !token.isBlank() && token.length() <= MAX_TOKEN_LENGTH
                && token.codePoints().noneMatch(Character::isISOControl);
    }

    private static boolean validFieldId(String id) { return id != null && id.matches("[A-Za-z][A-Za-z0-9_]{0,127}"); }

    private static boolean usable(FieldState state) { return state == FieldState.PRESENT || state == FieldState.MISSING; }

    private static void validate(ReportInput input) {
        if (input == null || input.mapping() == null || input.sourceProjects().isEmpty() || input.sourceProjects().size() > 20
                || input.issueTypeIds().size() > 100) throw JiraConnection.invalidConfiguration();
        var externalIds = new HashSet<String>();
        var internalIds = new HashSet<UUID>();
        for (SourceProject source : input.sourceProjects()) {
            if (!JiraPayload.ID.matcher(source.externalProjectId()).matches() || source.ticketProjectId() == null
                    || !externalIds.add(source.externalProjectId()) || !internalIds.add(source.ticketProjectId())) throw JiraConnection.invalidConfiguration();
        }
        for (String type : input.issueTypeIds()) if (!JiraPayload.ID.matcher(type).matches()) throw JiraConnection.invalidConfiguration();
        for (FieldRef mapping : new FieldRef[]{input.mapping().targetProject(), input.mapping().versions()}) {
            if (mapping == null || !validFieldId(mapping.fieldId()) || mapping.extractor() == null) throw JiraConnection.invalidConfiguration();
        }
        if (input.mapping().family() != null && (!validFieldId(input.mapping().family().fieldId())
                || input.mapping().family().extractor() == null)) throw JiraConnection.invalidConfiguration();
        if (input.mapping().targetProject().extractor() == Extractor.VERSION_IDS
                || !Set.of(Extractor.STRING, Extractor.VERSION_IDS).contains(input.mapping().versions().extractor())
                || input.mapping().family() != null && !Set.of(Extractor.STRING, Extractor.OPTION_ID).contains(input.mapping().family().extractor())) {
            throw JiraConnection.invalidConfiguration();
        }
        if (input.ordinaryProjects().size() > 2000) throw JiraConnection.invalidConfiguration();
        input.ordinaryProjects().forEach(project -> {
            if (project.ticketProjectId() == null || !JiraPayload.ID.matcher(project.externalProjectId()).matches()) throw JiraConnection.invalidConfiguration();
        });
    }
}
