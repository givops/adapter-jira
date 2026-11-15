package io.givops.ticketing.jira;

import static io.givops.ticketing.jira.JiraPayload.invalidResponse;

import io.givops.testreports.model.TestReportProviderModels.Assignee;
import io.givops.testreports.model.TestReportProviderModels.Diagnostic;
import io.givops.testreports.model.TestReportProviderModels.Extractor;
import io.givops.testreports.model.TestReportProviderModels.FieldRef;
import io.givops.testreports.model.TestReportProviderModels.FieldState;
import io.givops.testreports.model.TestReportProviderModels.NamedId;
import io.givops.testreports.model.TestReportProviderModels.TypedValue;
import io.givops.testreports.model.TestReportProviderModels.VersionValue;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Converts requested Jira fields without guessing across absent, null and incompatible values. */
final class JiraReportPayload {
    private static final int MAX_TEXT = 32768;

    private JiraReportPayload() {}

    record Value(TypedValue scalar, List<VersionValue> versions, FieldState state) {}

    static Value extract(JsonNode fields, FieldRef mapping, UUID ticketProjectId) {
        if (mapping == null) return new Value(null, List.of(), FieldState.MISSING);
        JsonNode value = fields.get(mapping.fieldId());
        if (value == null) return new Value(null, List.of(), FieldState.UNAVAILABLE);
        if (value.isNull()) return new Value(null, List.of(), FieldState.MISSING);
        if (mapping.extractor() == Extractor.VERSION_IDS) {
            if (!value.isArray()) return invalidValue();
            var versions = new ArrayList<VersionValue>();
            var ids = new java.util.HashSet<String>();
            for (JsonNode item : value) {
                if (!item.isObject() || !validId(item.get("id")) || !validText(item.get("name"))) return invalidValue();
                String id = identifier(item, "id");
                if (!ids.add(id)) return invalidValue();
                versions.add(new VersionValue(ticketProjectId, id, text(item, "name", true)));
            }
            return new Value(null, List.copyOf(versions), versions.isEmpty() ? FieldState.MISSING : FieldState.PRESENT);
        }
        if (value.isArray()) return new Value(null, List.of(), value.size() > 1 ? FieldState.AMBIGUOUS : FieldState.INVALID);
        String extracted;
        String label = null;
        switch (mapping.extractor()) {
            case STRING -> {
                if (!validText(value)) return invalidValue();
                extracted = value.stringValue();
            }
            case PROJECT_ID, OPTION_ID -> {
                if (!value.isObject() || !validId(value.get("id"))) return invalidValue();
                extracted = identifier(value, "id");
                JsonNode labelNode = value.get(mapping.extractor() == Extractor.OPTION_ID ? "value" : "name");
                if (labelNode != null && !labelNode.isNull()) {
                    if (!validText(labelNode)) return invalidValue();
                    label = labelNode.stringValue();
                }
            }
            case PROJECT_KEY -> {
                if (!value.isObject() || !validText(value.get("key"))) return invalidValue();
                extracted = value.get("key").stringValue();
                if (!JiraPayload.PROJECT_KEY.matcher(extracted).matches()) return invalidValue();
                label = text(value, "name", false);
            }
            default -> throw invalidResponse();
        }
        if (extracted.isBlank()) return new Value(null, List.of(), FieldState.MISSING);
        return new Value(new TypedValue(mapping.extractor(), extracted, label), List.of(), FieldState.PRESENT);
    }

    static List<VersionValue> versions(Value extracted) {
        if (extracted.scalar() != null && extracted.scalar().kind() == Extractor.STRING) {
            return List.of(new VersionValue(null, null, extracted.scalar().value()));
        }
        return extracted.versions();
    }

    static List<Diagnostic> diagnostics(FieldRef mapping, FieldState state) {
        if (mapping == null || state == FieldState.PRESENT) return List.of();
        return List.of(new Diagnostic("FIELD_" + state.name(), mapping.fieldId(),
                state == FieldState.MISSING ? JiraMessages.format("ticketing.jiraReportPayload.the.requested.provider.field.has.no.value")
                        : JiraMessages.format("ticketing.jiraReportPayload.the.requested.provider.field.is.unavailable.or.incompatible.with")));
    }

    static NamedId named(JsonNode value) {
        JiraPayload.requireObject(value);
        return new NamedId(identifier(value, "id"), text(value, "name", true));
    }

    static Assignee assignee(JsonNode fields) {
        JsonNode assignee = fields.get("assignee");
        if (assignee == null || assignee.isNull()) return null;
        JiraPayload.requireObject(assignee);
        return new Assignee(text(assignee, "accountId", false), text(assignee, "displayName", true));
    }

    static String identifier(JsonNode value, String field) {
        JsonNode id = value == null ? null : value.get(field);
        if (!validId(id)) throw invalidResponse();
        return id.isString() ? id.stringValue() : id.toString();
    }

    static String text(JsonNode value, String field, boolean required) {
        String result = JiraPayload.text(value, field, required);
        if (result != null && (result.length() > MAX_TEXT || result.codePoints().anyMatch(c -> c == 0))) {
            throw invalidResponse();
        }
        return result;
    }

    static Instant instant(JsonNode value, String field, boolean required) {
        JsonNode raw = value == null ? null : value.get(field);
        if (!required && (raw == null || raw.isNull())) return null;
        try {
            if (raw != null && raw.isIntegralNumber()) {
                if (!raw.toString().matches("[0-9]{1,12}")) throw invalidResponse();
                return Instant.ofEpochSecond(raw.longValue());
            }
            String timestamp = text(value, field, true).replaceFirst("([+-][0-9]{2})([0-9]{2})$", "$1:$2");
            return OffsetDateTime.parse(timestamp).toInstant();
        } catch (java.time.DateTimeException | ArithmeticException exception) {
            throw invalidResponse();
        }
    }

    private static boolean validId(JsonNode value) {
        return value != null && (value.isString() || value.isIntegralNumber())
                && JiraPayload.ID.matcher(value.isString() ? value.stringValue() : value.toString()).matches();
    }

    private static boolean validText(JsonNode value) {
        return value != null && value.isString() && value.stringValue().length() <= MAX_TEXT
                && value.stringValue().indexOf('\0') < 0;
    }

    private static Value invalidValue() { return new Value(null, List.of(), FieldState.INVALID); }
}
