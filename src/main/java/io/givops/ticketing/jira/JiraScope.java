package io.givops.ticketing.jira;

import io.givops.adapters.CapabilityInspection.State;
import io.givops.ticketing.model.TicketScopeModels.*;
import io.givops.ticketing.model.TicketModels.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import tools.jackson.databind.JsonNode;

/** Shared predicates and returned-field checks for every scoped read path. */
final class JiraScope {
    private JiraScope() { }
    static void require(Scope scope) {
        if (scope == null || !JiraPayload.ID.matcher(scope.valueId()).matches()) throw JiraConnection.invalidConfiguration();
        Field field = scope.field();
        boolean component = "components".equals(field.id()) && field.valueKind() == ValueKind.COMPONENT
                && field.cardinality() == Cardinality.MANY && field.match() == Match.CONTAINS;
        boolean option = field.id().matches("customfield_[1-9][0-9]{0,18}") && field.valueKind() == ValueKind.OPTION;
        if (field.state() != State.AVAILABLE || !(component || option)) throw JiraConnection.invalidConfiguration();
    }
    static String predicate(Scope scope) {
        require(scope);
        return (scope.field().valueKind() == ValueKind.COMPONENT ? "component" : "cf[" + scope.field().id().substring(12) + "]")
                + " = " + scope.valueId();
    }
    static List<String> fields(List<String> fields, Scope scope) {
        if (scope == null) return fields;
        require(scope);
        var result = new ArrayList<>(fields);
        if (!result.contains(scope.field().id())) result.add(scope.field().id());
        return List.copyOf(result);
    }
    static boolean matches(JsonNode row, Scope scope) {
        if (scope == null) return true;
        require(scope);
        JsonNode fields = row.get("fields"); JiraPayload.requireObject(fields);
        JsonNode value = fields.get(scope.field().id());
        if (value == null || value.isNull()) return false;
        if (scope.field().cardinality() == Cardinality.ONE) {
            JiraPayload.requireObject(value);
            return scope.valueId().equals(JiraReportPayload.identifier(value, "id"));
        }
        if (!value.isArray() || value.size() > 1000) throw JiraPayload.invalidResponse();
        boolean match = false; var ids = new HashSet<String>();
        for (var entry : value) {
            JiraPayload.requireObject(entry);
            String id = JiraReportPayload.identifier(entry, "id");
            if (!ids.add(id)) throw JiraPayload.invalidResponse();
            match |= scope.valueId().equals(id);
        }
        return match;
    }
    static VersionObservation version(VersionObservation version, Scope scope) {
        if (scope == null) return version;
        String name = scope.canonicalName(version.name());
        return name == null ? null : new VersionObservation(version.externalVersionId(), version.name(), version.released(),
                version.archived(), version.releaseDate(), version.observedAt(), version.nativeState(), version.startDate(), version.closedAt(), name);
    }
    static IssueObservation issue(IssueObservation issue, java.util.Set<String> versionIds) {
        return new IssueObservation(issue.identity(), issue.issueKey(), issue.summary(), issue.type(), issue.status(),
                issue.statusCategory(), issue.priority(), issue.resolution(), issue.createdAt(), issue.updatedAt(), issue.resolvedAt(),
                issue.releaseVersionIds().stream().filter(versionIds::contains).toList(), issue.webUrl(), issue.observedAt(),
                issue.assigneeAccountId(), null, issue.labels(), issue.sourceRevision());
    }
}
