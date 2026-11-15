package io.givops.ticketing.jira;

import io.givops.adapters.ContextSigner;
import io.givops.adapters.CredentialHost;
import io.givops.testreports.config.TestReportProperties;
import io.givops.ticketing.config.TicketProperties;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/** Explicit external-JAR registration; the host need not scan this package. */
@AutoConfiguration
@EnableConfigurationProperties(JiraProperties.class)
public class JiraAutoConfiguration {
    @Bean JiraProviderDefinition jiraProviderDefinition(JiraProperties properties) {
        return new JiraProviderDefinition(properties);
    }
    @Bean JiraAccess jiraAccess(CredentialHost host, ContextSigner signer) {
        return new JiraAccess(host, signer);
    }
    @Bean JiraTransport jiraTransport(TicketProperties limits, Clock clock) {
        return new JiraTransport(JiraTransport.clientBuilder(limits.requestTimeout()).build(), limits.maxResponseBytes(), clock);
    }
    @Bean JiraAdapter jiraAdapter(JiraAccess credentials, TicketProperties limits,
                                 JiraTransport transport, JsonMapper mapper, Clock clock) {
        return new JiraAdapter(credentials, limits, transport, mapper, clock);
    }
    @Bean JiraReportAdapter jiraReportAdapter(JiraAccess credentials, JiraTransport transport,
                                              TestReportProperties limits, JsonMapper mapper, Clock clock) {
        return new JiraReportAdapter(credentials, transport, limits, mapper, clock);
    }
    @Bean JiraReleaseWriter jiraReleaseWriter(JiraAccess credentials, JiraTransport transport,
                                              JsonMapper mapper, TicketProperties limits) {
        return new JiraReleaseWriter(credentials, transport, mapper, limits);
    }
}
