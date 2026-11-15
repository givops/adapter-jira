package io.givops.ticketing.jira;

import io.givops.adapters.CredentialAccessException;
import io.givops.adapters.testkit.AdapterTestkit;

final class JiraTestCredentials {
    private JiraTestCredentials() { }

    static JiraAccess access(JiraProperties properties) {
        var definition = new JiraProviderDefinition(properties);
        return new JiraAccess(request -> {
            var runtime = definition.runtimeCredentials(request).orElseThrow(CredentialAccessException::new);
            return AdapterTestkit.lease(runtime.fields(), runtime.expectedDestinations(), runtime.accessRevision());
        }, AdapterTestkit.signer("synthetic-context-secret-at-least-thirty-two-characters"));
    }
}
