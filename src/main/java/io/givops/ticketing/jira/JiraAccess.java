package io.givops.ticketing.jira;

import io.givops.adapters.ContextSigner;
import io.givops.adapters.CredentialAccessException;
import io.givops.adapters.CredentialHost;
import io.givops.adapters.CredentialLease;
import io.givops.adapters.CredentialRequest;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.port.TicketProviderAdapter.Access;
import java.util.Objects;

/** Narrow adapter-side view of host-owned credentials and publication freshness. */
final class JiraAccess {
    private final CredentialHost host;
    private final ContextSigner signer;

    JiraAccess(CredentialHost host, ContextSigner signer) {
        this.host = Objects.requireNonNull(host);
        this.signer = Objects.requireNonNull(signer);
    }

    record Captured(JiraConnection connection, CredentialLease lease, String context, InstanceSource source)
            implements Access {
        @Override public String toString() { return "JiraAccess[redacted]"; }
    }

    Captured capture(InstanceSource source) {
        if (source == null || !TicketProvider.JIRA_CLOUD.equals(source.provider())
                || source.instanceId() == null || source.instanceKey() == null)
            throw JiraConnection.invalidConfiguration();
        var origin = JiraConnection.origin(source.baseUrl());
        try {
            var lease = host.capture(new CredentialRequest(source.instanceId(), source.instanceKey(),
                    TicketProvider.JIRA_CLOUD.name(), origin));
            var destinations = lease.destinations();
            if (!Objects.equals(source.instanceKey(), destinations.get("instanceKey"))
                    || !Objects.equals(origin.toString(), destinations.get("baseUrl")))
                throw JiraConnection.invalidConfiguration();
            var fields = lease.fields();
            var value = new JiraProperties.Instance(destinations.get("baseUrl"), fields.get("email"),
                    fields.get("apiToken"), lease.accessRevision(), fields.get("cloudId"));
            var connection = new JiraConnection(source, value, signer);
            return new Captured(connection, lease, connection.context(source), source);
        } catch (CredentialAccessException exception) {
            throw new TicketException(Code.MISSING_CREDENTIALS,
                    JiraMessages.format("jira.credentials.unavailable"));
        }
    }

    JiraConnection connection(InstanceSource source) { return capture(source).connection(); }

    JiraConnection connection(InstanceSource source, Access access) {
        if (access == null) return connection(source);
        if (!(access instanceof Captured captured)) throw JiraConnection.invalidConfiguration();
        requireSource(source, captured);
        return captured.connection();
    }

    void requireSource(InstanceSource source, Captured captured) {
        var selected = captured.source();
        if (!Objects.equals(source.instanceId(), selected.instanceId())
                || !Objects.equals(source.instanceKey(), selected.instanceKey())
                || !Objects.equals(source.provider(), selected.provider())
                || !JiraConnection.origin(source.baseUrl()).equals(JiraConnection.origin(selected.baseUrl())))
            throw JiraConnection.invalidConfiguration();
    }

    void requireCurrent(Captured captured, boolean locked) {
        try {
            captured.lease().requireCurrent(locked);
        } catch (CredentialAccessException exception) {
            throw new TicketException(Code.SUPERSEDED, JiraMessages.format("jira.credentials.changed"));
        }
    }
}
