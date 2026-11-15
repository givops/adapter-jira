package io.givops.ticketing.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.givops.adapters.ContextSigner;
import io.givops.adapters.CredentialAccessException;
import io.givops.adapters.CredentialHost;
import io.givops.adapters.CredentialRequest;
import io.givops.adapters.testkit.AdapterTestkit;
import io.givops.testreports.config.TestReportProperties;
import io.givops.testreports.port.TestReportProviderAdapter;
import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.port.ReleaseWriteAdapter;
import io.givops.ticketing.port.TicketProviderAdapter;
import io.givops.ticketing.port.TicketProviderDefinition;
import io.givops.ticketing.port.TicketWriteAdapter;
import java.net.URI;
import java.time.Clock;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

class JiraRegistrationTest {
    @Test void externalJarRegistersAllImplementedPortsWithoutComponentScanningOrCredentials() {
        var captures = new AtomicInteger();
        new ApplicationContextRunner().withUserConfiguration(Host.class)
                .withBean(CredentialHost.class, () -> request -> {
                    captures.incrementAndGet();
                    throw new CredentialAccessException();
                }).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(TicketProviderDefinition.class)
                            .hasSingleBean(TicketProviderAdapter.class).hasSingleBean(TestReportProviderAdapter.class)
                            .hasSingleBean(ReleaseWriteAdapter.class).doesNotHaveBean(TicketWriteAdapter.class);
                    var definition = context.getBean(TicketProviderDefinition.class);
                    AdapterTestkit.verifyDefinition(definition);
                    assertThat(definition.sdkApiVersion()).isEqualTo("0.1");
                    assertThat(definition.capabilities()).containsExactly("ticketing.read", "ticketing.version-membership",
                            "ticketing.reports", "ticketing.release-write");
                    assertThat(definition.apiProfiles()).containsExactly("jira-cloud-rest-v3");
                    assertThat(definition.adapterVersion()).isEqualTo("0.1.0-SNAPSHOT");
                    assertThat(captures).hasValue(0);
                });
    }

    @Test void packagedMetadataMatchesTheRegisteredDefinition() throws Exception {
        var definition = new JiraProviderDefinition(new JiraProperties(Map.of()));
        var metadata = new Properties();
        try (var stream = getClass().getResourceAsStream("/META-INF/givops-adapter.properties")) {
            assertThat(stream).isNotNull();
            metadata.load(stream);
        }
        assertThat(metadata).containsEntry("adapterId", definition.adapterId())
                .containsEntry("provider", definition.provider().name())
                .containsEntry("adapterVersion", definition.adapterVersion())
                .containsEntry("sdkApiVersion", definition.sdkApiVersion());
    }

    @Test void validatesProviderCredentialsWithoutPrivateHostServicesOrQueries() {
        var definition = new JiraProviderDefinition(new JiraProperties(Map.of()));
        var target = new CredentialRequest(UUID.randomUUID(), "jira", "JIRA_CLOUD", URI.create("https://example.atlassian.net/"));
        assertThat(definition.normalizeBaseUrl(target.baseUrl())).hasToString("https://example.atlassian.net");
        assertThat(definition.validateCredentials(target, Map.of("email", "reader@example.test", "apiToken", "synthetic-token")))
                .containsOnlyKeys("email", "apiToken");
        assertThatThrownBy(() -> definition.validateCredentials(target,
                Map.of("email", "reader@example.test", "apiToken", "synthetic-token", "cloudId", "not-a-uuid")))
                .isInstanceOf(TicketException.class);
        assertThatThrownBy(() -> definition.validateProject("101 OR 1=1", "APP"))
                .isInstanceOf(TicketException.class);
        assertThat(definition.runtimeCredentials(target)).isEmpty();
    }

    @Test void messagesAreOwnedByThisArtifactAndNeverContainProviderQueries() {
        assertThat(JiraMessages.patterns()).isNotEmpty().doesNotContainKey(
                "ticketing.jiraAdapter.assignee.is.not.empty.and.statuscategory.done");
        assertThat(JiraMessages.format("ticketing.jiraTransport.jira.cloud.rate.limited.the.request"))
                .isEqualTo("Jira Cloud rate limited the request.");
        assertThatThrownBy(() -> JiraMessages.format("core.private.message"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableConfigurationProperties({TicketProperties.class, TestReportProperties.class})
    static class Host {
        // The private host retains its legacy credential-controller facade under this bean name.
        @Bean Object jiraCredentials() { return new Object(); }
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean JsonMapper jsonMapper() { return JsonMapper.builder().build(); }
        @Bean ContextSigner signer() { return AdapterTestkit.signer("synthetic-signing-key"); }
    }
}
