package io.givops.ticketing.jira;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("givops.ticketing.jira")
public record JiraProperties(Map<String, Instance> instances) {
    public JiraProperties {
        instances = instances == null ? Map.of() : Map.copyOf(instances);
    }

    @Override
    public String toString() {
        return "JiraProperties[redacted]";
    }

    public record Instance(String expectedSiteOrigin, String email, String apiToken, String accessRevision,
                           String cloudId) {
        @Override
        public String toString() {
            return "Instance[redacted]";
        }
    }
}
