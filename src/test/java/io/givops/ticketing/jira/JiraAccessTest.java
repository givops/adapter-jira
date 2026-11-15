package io.givops.ticketing.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.givops.adapters.CredentialAccessException;
import io.givops.adapters.testkit.AdapterTestkit;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class JiraAccessTest {
    private static final InstanceSource SOURCE = new InstanceSource(UUID.randomUUID(), "jira",
            TicketProvider.JIRA_CLOUD, URI.create("https://example.atlassian.net"), 0);
    private static final Map<String, String> FIELDS = Map.of("email", "reader@example.test", "apiToken", "synthetic-token");
    private static final Map<String, String> PINS = Map.of("instanceKey", "jira", "baseUrl", SOURCE.baseUrl().toString());

    @Test void signsTheExistingOrderedContextWithoutGivingTheAdapterTheHostSecret() {
        var lease = AdapterTestkit.lease(FIELDS, PINS, "revision-2");
        var calls = new AtomicInteger();
        var access = new JiraAccess(request -> {
            assertThat(request.instanceId()).isEqualTo(SOURCE.instanceId());
            assertThat(request.provider()).isEqualTo("JIRA_CLOUD");
            return lease;
        }, (provider, namespace, parts) -> {
            calls.incrementAndGet();
            assertThat(provider).isEqualTo("JIRA_CLOUD");
            assertThat(namespace).isEqualTo("givops-jira-cloud-v1");
            assertThat(parts).isEqualTo(List.of(SOURCE.instanceId().toString(), "jira",
                    "https://example.atlassian.net", "https://example.atlassian.net/rest/api/3",
                    "reader@example.test", "synthetic-token", "revision-2"));
            return "opaque-context";
        });
        var captured = access.capture(SOURCE);
        assertThat(captured.context()).isEqualTo("opaque-context");
        assertThat(calls).hasValue(1);
        assertThat(captured.toString()).doesNotContain("reader", "synthetic-token", "opaque-context");
    }

    @Test void destinationAndInstancePinsCannotBeSubstituted() {
        var signer = AdapterTestkit.signer("synthetic-key");
        var wrong = new JiraAccess(request -> AdapterTestkit.lease(FIELDS,
                Map.of("instanceKey", "other", "baseUrl", SOURCE.baseUrl().toString()), "one"), signer);
        assertCode(Code.INVALID_CONFIGURATION, () -> wrong.capture(SOURCE));
        var access = new JiraAccess(request -> AdapterTestkit.lease(FIELDS, PINS, "one"), signer);
        var captured = access.capture(SOURCE);
        var another = new InstanceSource(UUID.randomUUID(), SOURCE.instanceKey(), SOURCE.provider(), SOURCE.baseUrl(), 0);
        assertCode(Code.INVALID_CONFIGURATION, () -> access.connection(another, captured));
    }

    @Test void unavailableCaptureAndSupersededLeaseHaveDistinctSanitizedFailures() {
        var unavailable = new JiraAccess(request -> { throw new CredentialAccessException(); }, AdapterTestkit.signer("synthetic-key"));
        assertCode(Code.MISSING_CREDENTIALS, () -> unavailable.capture(SOURCE));
        var lease = AdapterTestkit.lease(FIELDS, PINS, "one");
        var access = new JiraAccess(request -> lease, AdapterTestkit.signer("synthetic-key"));
        var captured = access.capture(SOURCE);
        access.requireCurrent(captured, true);
        assertThat(lease.lastLocked()).isTrue();
        lease.invalidate();
        assertCode(Code.SUPERSEDED, () -> access.requireCurrent(captured, false));
        assertThat(lease.lastLocked()).isFalse();
        assertCode(Code.SUPERSEDED, () -> access.requireCurrent(captured, true));
        assertThat(lease.lastLocked()).isTrue();
    }

    @Test void unexpectedHostFailuresAreNotMisreportedAsCredentialDenials() {
        var unexpected = new IllegalStateException("synthetic host failure");
        var access = new JiraAccess(request -> { throw unexpected; }, AdapterTestkit.signer("synthetic-key"));
        assertThatThrownBy(() -> access.capture(SOURCE)).isSameAs(unexpected);
    }

    private static void assertCode(Code code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(TicketException.class, error -> {
            assertThat(error.code()).isEqualTo(code);
            assertThat(error.getCause()).isNull();
            assertThat(error.getMessage()).doesNotContain("synthetic-token", "reader@example.test");
        });
    }
}
