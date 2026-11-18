package io.givops.ticketing.jira;

import io.givops.adapters.CapabilityInspection;
import io.givops.adapters.CapabilityInspection.State;
import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketScopeModels;
import io.givops.ticketing.model.TicketScopeModels.*;
import io.givops.ticketing.port.TicketProviderAdapter.Access;
import io.givops.ticketing.port.TicketRequestGate;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Configuration-only discovery. Ordinary reads do not need Jira administration permissions. */
final class JiraScopeMetadata {
    private final JiraAccess credentials;
    private final TicketProperties limits;
    private final JiraTransport transport;
    private final JsonMapper mapper;
    private final Clock clock;
    JiraScopeMetadata(JiraAccess credentials, TicketProperties limits, JiraTransport transport, JsonMapper mapper, Clock clock) {
        this.credentials = credentials; this.limits = limits; this.transport = transport; this.mapper = mapper; this.clock = clock;
    }
    Fields fields(InstanceSource source, String project, Access access, TicketRequestGate gate) {
        try {
            var session = new Session(source, project, access, gate);
            JsonNode rows = session.request("/field");
            if (!rows.isArray() || rows.size() > limits.maxObjects()) throw JiraPayload.invalidResponse();
            gate.acceptedObjects(rows.size());
            var fields = new ArrayList<Field>(); var ids = new HashSet<String>();
            for (var row : rows) {
                String id = JiraPayload.text(row, "id", true);
                if (!ids.add(id)) throw JiraPayload.invalidResponse();
                if (!id.equals("components") && !id.matches("customfield_[1-9][0-9]{0,18}")) continue;
                String label = JiraPayload.text(row, "name", true);
                JsonNode schema = row.get("schema");
                String type = JiraPayload.text(schema, "type", false), items = JiraPayload.text(schema, "items", false);
                String custom = JiraPayload.text(schema, "custom", false);
                boolean component = id.equals("components") && "array".equals(type) && "component".equals(items);
                boolean single = "option".equals(type) && "com.atlassian.jira.plugin.system.customfieldtypes:select".equals(custom);
                boolean multi = "array".equals(type) && "option".equals(items)
                        && "com.atlassian.jira.plugin.system.customfieldtypes:multiselect".equals(custom);
                boolean supported = component || single || multi;
                fields.add(new Field(id, label, component ? ValueKind.COMPONENT : ValueKind.OPTION,
                        single ? Cardinality.ONE : Cardinality.MANY, single ? Match.EQUALS : Match.CONTAINS,
                        supported ? State.AVAILABLE : State.UNSUPPORTED, supported ? null : "UNSUPPORTED_FIELD_SCHEMA"));
            }
            session.finish(); return new Fields(State.AVAILABLE, fields, null);
        } catch (TicketException failure) { return new Fields(state(failure), List.of(), failure.code().name()); }
    }
    Values values(InstanceSource source, String project, String fieldId, Access access, TicketRequestGate gate) {
        if (fieldId == null || !fieldId.equals("components") && !fieldId.matches("customfield_[1-9][0-9]{0,18}"))
            return new Values(State.UNSUPPORTED, List.of(), "UNSUPPORTED_FIELD");
        try {
            var session = new Session(source, project, access, gate);
            var result = new ArrayList<Value>();
            if (fieldId.equals("components")) {
                for (var row : session.pages("/project/" + project + "/component")) {
                    String owner = optionalIdentifier(row, "projectId");
                    if (owner != null && !project.equals(owner)) throw JiraPayload.invalidResponse();
                    result.add(new Value(JiraReportPayload.identifier(row, "id"), JiraPayload.text(row, "name", true), false, State.AVAILABLE, null));
                }
            } else {
                var described = fields(source, project, access, gate);
                if (described.state() != State.AVAILABLE) return new Values(described.state(), List.of(), described.diagnostic());
                var field = described.fields().stream().filter(candidate -> fieldId.equals(candidate.id())).findFirst().orElse(null);
                if (field == null || field.state() != State.AVAILABLE) return new Values(State.UNSUPPORTED, List.of(), "UNSUPPORTED_FIELD_SCHEMA");
                var contexts = new HashSet<String>();
                for (var mapping : session.pages("/field/" + fieldId + "/context/projectmapping")) {
                    if (Boolean.TRUE.equals(JiraPayload.flag(mapping, "isGlobalContext"))
                            || project.equals(optionalIdentifier(mapping, "projectId")))
                        contexts.add(JiraReportPayload.identifier(mapping, "contextId"));
                }
                if (contexts.size() != 1) return new Values(State.UNKNOWN, List.of(), contexts.isEmpty() ? "NO_PROJECT_CONTEXT" : "AMBIGUOUS_PROJECT_CONTEXT");
                String context = contexts.iterator().next();
                var verified = session.pages("/field/" + fieldId + "/context").stream()
                        .filter(row -> context.equals(JiraPayload.text(row, "id", true))).toList();
                if (verified.size() != 1 || !Boolean.TRUE.equals(JiraPayload.flag(verified.getFirst(), "isAnyIssueType")))
                    return new Values(State.UNKNOWN, List.of(), "ISSUE_TYPE_CONTEXT_UNVERIFIED");
                for (var row : session.pages("/field/" + fieldId + "/context/" + context + "/option")) {
                    if (row.has("optionId")) throw JiraPayload.invalidResponse();
                    boolean disabled = Boolean.TRUE.equals(JiraPayload.flag(row, "disabled"));
                    result.add(new Value(JiraReportPayload.identifier(row, "id"), JiraPayload.text(row, "value", true),
                            disabled, disabled ? State.DISABLED : State.AVAILABLE, disabled ? "OPTION_DISABLED" : null));
                }
            }
            var ids = new HashSet<String>();
            for (var value : result) if (!ids.add(value.id())) throw JiraPayload.invalidResponse();
            session.finish(); return new Values(State.AVAILABLE, result, null);
        } catch (TicketException failure) { return new Values(state(failure), List.of(), failure.code().name()); }
    }
    CapabilityInspection validate(InstanceSource source, String project, Scope scope, Access access, TicketRequestGate gate) {
        JiraScope.require(scope);
        var described = fields(source, project, access, gate);
        State state = described.state(); String diagnostic = described.diagnostic();
        if (state == State.AVAILABLE) {
            var field = described.fields().stream().filter(value -> value.id().equals(scope.field().id())).findFirst().orElse(null);
            if (field == null || field.state() != State.AVAILABLE || field.valueKind() != scope.field().valueKind()
                    || field.cardinality() != scope.field().cardinality() || field.match() != scope.field().match()) {
                state = State.UNSUPPORTED; diagnostic = "FIELD_SCHEMA_CHANGED";
            } else {
                var values = values(source, project, field.id(), access, gate);
                state = values.state(); diagnostic = values.diagnostic();
                if (state == State.AVAILABLE) {
                    var value = values.values().stream().filter(candidate -> candidate.id().equals(scope.valueId())).findFirst().orElse(null);
                    state = value == null ? State.UNKNOWN : value.state(); diagnostic = value == null ? "VALUE_NOT_FOUND" : value.diagnostic();
                }
            }
        }
        return new CapabilityInspection(source.provider().name(), TicketScopeModels.CAPABILITY, state, diagnostic,
                null, "jira-cloud-rest-v3", access.context(), clock.instant());
    }
    private static String optionalIdentifier(JsonNode row, String field) {
        JsonNode value = row.get(field);
        return value == null || value.isNull() ? null : JiraReportPayload.identifier(row, field);
    }
    private static State state(TicketException failure) {
        return switch (failure.code()) {
            case AUTHORIZATION_FAILED, AUTHENTICATION_FAILED, MISSING_CREDENTIALS -> State.FORBIDDEN;
            case DISABLED -> State.DISABLED;
            case UNSUPPORTED_PROVIDER -> State.UNSUPPORTED;
            case NOT_FOUND, INVALID_CONFIGURATION, INVALID_RESPONSE -> State.UNKNOWN;
            default -> State.TEMPORARILY_UNAVAILABLE;
        };
    }
    private final class Session {
        final JiraConnection connection; final Access access; final TicketRequestGate gate;
        Session(InstanceSource source, String project, Access access, TicketRequestGate gate) {
            if (!limits.enabled()) throw new TicketException(TicketException.Code.DISABLED, JiraMessages.format("ticketing.jiraAdapter.ticketing.access.is.disabled"));
            if (project == null || !JiraPayload.ID.matcher(project).matches() || !(access instanceof JiraAccess.Captured))
                throw JiraConnection.invalidConfiguration();
            this.access = access; this.gate = gate; connection = credentials.connection(source, access); finish();
        }
        void finish() { gate.check(); credentials.requireCurrent((JiraAccess.Captured) access, false); }
        JsonNode request(String path) {
            finish();
            byte[] bytes = transport.read(connection.endpoint(path), connection.authorization(), null, gate);
            try { JsonNode result = mapper.reader()
                    .with(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).readTree(bytes);
                finish(); return result; }
            catch (JacksonException exception) { throw JiraPayload.invalidResponse(); }
        }
        List<JsonNode> pages(String path) {
            int start = 0; Integer total = null; var result = new ArrayList<JsonNode>();
            for (int page = 0; page < limits.maxPages(); page++) {
                JsonNode root = request(path + "?startAt=" + start + "&maxResults=100");
                JsonNode rows = JiraPayload.array(root, "values", 100);
                Integer offset = JiraPayload.offset(root, "startAt"), reported = JiraPayload.offset(root, "total");
                Boolean last = JiraPayload.flag(root, "isLast");
                if (offset == null || offset != start || reported == null || total != null && !total.equals(reported)
                        || last == null || start + rows.size() > reported || last != (start + rows.size() == reported)
                        || !last && rows.isEmpty()) throw JiraPayload.invalidResponse();
                total = reported; gate.acceptedObjects(rows.size()); rows.forEach(result::add); start += rows.size();
                if (last) return result;
            }
            throw JiraTransport.limitExceeded();
        }
    }
}
