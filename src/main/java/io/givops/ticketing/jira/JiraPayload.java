package io.givops.ticketing.jira;

import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.IssueIdentity;
import io.givops.ticketing.model.TicketModels.IssueObservation;
import io.givops.ticketing.model.TicketModels.ProjectObservation;
import io.givops.ticketing.model.TicketModels.VersionObservation;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

final class JiraPayload {
    static final Pattern ID = Pattern.compile("[1-9][0-9]{0,18}");
    static final Pattern PROJECT_KEY = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    static final Pattern ISSUE_KEY = Pattern.compile(PROJECT_KEY.pattern() + "-[1-9][0-9]{0,18}");
    private static final Pattern OFFSET_WITHOUT_COLON = Pattern.compile("([+-][0-9]{2})([0-9]{2})$");

    private JiraPayload() {}

    static ProjectObservation project(JsonNode node, String projectId, JiraConnection connection, Instant observedAt) {
        String id = id(node, "id");
        String key = text(node, "key", true);
        if (!id.equals(projectId) || !PROJECT_KEY.matcher(key).matches()) {
            throw invalidResponse();
        }
        return new ProjectObservation(id, key, text(node, "name", true), connection.browse(key), observedAt);
    }

    static VersionObservation version(JsonNode node, String projectId, Instant observedAt) {
        requireObject(node);
        if (!missing(node.get("projectId")) && !id(node, "projectId").equals(projectId)) {
            throw invalidResponse();
        }
        String date = text(node, "releaseDate", false);
        Boolean released = flag(node, "released");
        Boolean archived = flag(node, "archived");
        if (released == null || archived == null) {
            throw invalidResponse();
        }
        try {
            return new VersionObservation(id(node, "id"), text(node, "name", true), released, archived,
                    date == null ? null : LocalDate.parse(date), observedAt);
        } catch (DateTimeParseException exception) {
            throw invalidResponse();
        }
    }

    static IssueObservation issue(JsonNode node, String projectId, JiraConnection connection, Instant observedAt) {
        requireObject(node);
        JsonNode fields = node.get("fields");
        requireObject(fields);
        JsonNode project = fields.get("project");
        requireObject(project);
        String key = text(node, "key", true);
        if (!id(project, "id").equals(projectId) || !ISSUE_KEY.matcher(key).matches()) {
            throw invalidResponse();
        }
        JsonNode status = child(fields, "status");
        JsonNode category = child(status, "statusCategory");
        JsonNode assignee = child(fields, "assignee");
        return new IssueObservation(new IssueIdentity(projectId, id(node, "id")), key,
                text(fields, "summary", false), label(fields, "issuetype"), text(status, "name", false),
                text(category, "key", false), label(fields, "priority"), label(fields, "resolution"),
                timestamp(fields, "created"), timestamp(fields, "updated"), timestamp(fields, "resolutiondate"),
                fixVersions(fields.get("fixVersions")), connection.browse(key), observedAt,
                assignee == null ? null : text(assignee, "accountId", true));
    }

    static void requireObject(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalidResponse();
        }
    }

    static JsonNode array(JsonNode node, String name, int maxSize) {
        JsonNode field = node.get(name);
        if (field == null || !field.isArray() || field.size() > maxSize) {
            throw invalidResponse();
        }
        return field;
    }

    static String text(JsonNode node, String name, boolean required) {
        JsonNode field = node == null ? null : node.get(name);
        if (!required && missing(field)) {
            return null;
        }
        if (missing(field) || !field.isString() || required && field.stringValue().isBlank()) {
            throw invalidResponse();
        }
        return field.stringValue();
    }

    static Boolean flag(JsonNode node, String name) {
        JsonNode field = node.get(name);
        if (missing(field)) {
            return null;
        }
        if (!field.isBoolean()) {
            throw invalidResponse();
        }
        return field.booleanValue();
    }

    static Integer offset(JsonNode node, String name) {
        JsonNode field = node.get(name);
        if (missing(field)) {
            return null;
        }
        if (field.isIntegralNumber()) {
            int value = field.intValueOpt().orElse(-1);
            if (value >= 0) {
                return value;
            }
        }
        throw invalidResponse();
    }

    static TicketException invalidResponse() {
        return new TicketException(Code.INVALID_RESPONSE, JiraMessages.format("ticketing.jiraPayload.jira.cloud.returned.an.invalid.or.out.of.scope"));
    }

    static String id(JsonNode node, String name) {
        JsonNode field = node.get(name);
        if (field != null && (field.isString() || field.isIntegralNumber())) {
            String value = field.isString() ? field.stringValue() : field.toString();
            if (ID.matcher(value).matches()) {
                return value;
            }
        }
        throw invalidResponse();
    }

    private static List<String> fixVersions(JsonNode node) {
        if (missing(node)) {
            return List.of();
        }
        if (!node.isArray()) {
            throw invalidResponse();
        }
        var ids = new LinkedHashSet<String>();
        for (JsonNode item : node) {
            requireObject(item);
            if (!ids.add(id(item, "id"))) {
                throw invalidResponse();
            }
        }
        return List.copyOf(ids);
    }

    private static String label(JsonNode node, String name) {
        return text(child(node, name), "name", false);
    }

    private static JsonNode child(JsonNode node, String name) {
        JsonNode field = node == null ? null : node.get(name);
        if (missing(field)) {
            return null;
        }
        requireObject(field);
        return field;
    }

    private static Instant timestamp(JsonNode node, String name) {
        String value = text(node, name, false);
        if (value == null) {
            return null;
        }
        try {
            String withIsoOffset = OFFSET_WITHOUT_COLON.matcher(value).replaceFirst("$1:$2");
            return OffsetDateTime.parse(withIsoOffset).toInstant();
        } catch (DateTimeParseException exception) {
            throw invalidResponse();
        }
    }

    private static boolean missing(JsonNode node) {
        return node == null || node.isNull();
    }
}
