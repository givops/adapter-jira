package io.givops.ticketing.jira;

import io.givops.adapters.CapabilityInspection.State;
import io.givops.ticketing.model.TicketScopeModels.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class JiraScopeTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static Scope scope(String field, Cardinality cardinality, String value) {
        return new Scope(UUID.randomUUID(), new Field(field, "Label", field.equals("components") ? ValueKind.COMPONENT : ValueKind.OPTION,
                cardinality, cardinality == Cardinality.ONE ? Match.EQUALS : Match.CONTAINS, State.AVAILABLE, null), value, "Back [.*] ", 1, 1);
    }
    @Test void stableIdsSupportSingleMultiAndBothBindingsWithoutLabelEquality() {
        for (String field : new String[]{"components", "customfield_102"}) {
            var row = JSON.readTree("{\"fields\":{\"" + field + "\":[{\"id\":\"41\",\"value\":\"Renamed\"},{\"id\":\"42\",\"value\":\"Renamed\"}]}}");
            assertThat(JiraScope.matches(row, scope(field, Cardinality.MANY, "41"))).isTrue();
            assertThat(JiraScope.matches(row, scope(field, Cardinality.MANY, "42"))).isTrue();
            assertThat(JiraScope.matches(row, scope(field, Cardinality.MANY, "43"))).isFalse();
        }
        assertThat(JiraScope.matches(JSON.readTree("{\"fields\":{\"customfield_101\":{\"id\":\"41\"}}}"), scope("customfield_101", Cardinality.ONE, "41"))).isTrue();
    }
    @Test void missingAndChangedShapesFailClosedAndJqlNeverUsesLabels() {
        var scope = scope("customfield_101", Cardinality.ONE, "41");
        assertThat(JiraScope.matches(JSON.readTree("{\"fields\":{}}"), scope)).isFalse();
        assertThatThrownBy(() -> JiraScope.matches(JSON.readTree("{\"fields\":{\"customfield_101\":[{\"id\":\"41\"}]}}"), scope)).isInstanceOf(io.givops.ticketing.model.TicketException.class);
        assertThat(JiraScope.predicate(scope)).isEqualTo("cf[101] = 41");
        assertThatThrownBy(() -> JiraScope.require(scope("customfield_101", Cardinality.ONE, "41 OR project=2"))).isInstanceOf(io.givops.ticketing.model.TicketException.class);
        assertThatThrownBy(() -> JiraScope.require(scope("customfield_101] OR project=2", Cardinality.ONE, "41"))).isInstanceOf(io.givops.ticketing.model.TicketException.class);
    }
}
