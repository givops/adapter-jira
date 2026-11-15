package io.givops.ticketing.jira;

import io.givops.adapters.CredentialField;
import io.givops.adapters.CredentialField.Type;
import io.givops.adapters.CredentialRequest;
import io.givops.adapters.RuntimeCredential;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.port.TicketProviderDefinition;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/** Installed capabilities are independent of a particular user's current authorization. */
public final class JiraProviderDefinition implements TicketProviderDefinition {
    private static final Pattern ISSUE_PATH = Pattern.compile("/browse/(" + IssueReference.KEY_PATTERN + ")");
    private final JiraProperties runtime;

    public JiraProviderDefinition(JiraProperties runtime) { this.runtime = runtime; }

    @Override public TicketProvider provider() { return TicketProvider.JIRA_CLOUD; }
    @Override public String displayName() { return JiraMessages.format("jira.definition.name"); }
    @Override public String adapterId() { return "io.givops.adapter-jira"; }
    @Override public String adapterVersion() { return Artifact.VERSION; }
    @Override public String sdkApiVersion() { return "0.2"; }
    @Override public Map<String, String> commandLabels() { return Map.of("projectOwner", "Project lead account ID"); }
    @Override public List<String> capabilities() {
        return List.of("ticketing.read", "ticketing.version-membership", "ticketing.reports", "ticketing.release-write");
    }
    @Override public List<String> apiProfiles() { return List.of("jira-cloud-rest-v3"); }
    @Override public List<CredentialField> credentialFields() {
        return List.of(new CredentialField("email", JiraMessages.format("jira.definition.email"), Type.TEXT, true, 320),
                new CredentialField("apiToken", JiraMessages.format("jira.definition.token"), Type.SECRET, true, 4096),
                new CredentialField("cloudId", JiraMessages.format("jira.definition.cloud-id"), Type.TEXT, false, 36));
    }
    @Override public String projectKeyPattern() { return JiraPayload.PROJECT_KEY.pattern(); }
    @Override public String externalIdPattern() { return JiraPayload.ID.pattern(); }
    /** Syntax only: the host enforces configured origin, context, ambiguity and resource budgets. */
    @Override public Optional<IssueReference> issueReference(URI candidate) {
        if (candidate == null || candidate.getRawPath() == null) return Optional.empty();
        var match = ISSUE_PATH.matcher(candidate.getRawPath());
        return match.matches() ? Optional.of(new IssueReference(match.group(1).toUpperCase(java.util.Locale.ROOT), "")) : Optional.empty();
    }
    @Override public URI normalizeBaseUrl(URI value) {
        URI normalized = JiraConnection.origin(value);
        if (value.getPort() != -1 && value.getPort() != 443 || normalized.toString().length() > 255)
            throw JiraConnection.invalidConfiguration();
        return normalized;
    }
    @Override public void validateProject(String externalId, String projectKey) {
        if (externalId == null || projectKey == null || !JiraPayload.ID.matcher(externalId).matches()
                || !JiraPayload.PROJECT_KEY.matcher(projectKey).matches()) throw JiraConnection.invalidConfiguration();
    }
    @Override public Map<String, String> validateCredentials(CredentialRequest target, Map<String, String> fields) {
        if (target == null || !provider().name().equals(target.provider()) || fields == null
                || !Set.of("email", "apiToken", "cloudId").containsAll(fields.keySet()))
            throw JiraConnection.invalidConfiguration();
        normalizeBaseUrl(target.baseUrl());
        String email = fields.get("email"), token = fields.get("apiToken");
        if (!JiraConnection.validEmail(email) || email.length() > 320
                || !JiraConnection.validToken(token) || token.length() > 4096)
            throw JiraConnection.invalidConfiguration();
        var result = new LinkedHashMap<String, String>();
        result.put("email", email);
        result.put("apiToken", token);
        String cloudId = JiraConnection.canonicalCloudId(fields.get("cloudId"));
        if (cloudId != null) result.put("cloudId", cloudId);
        return Map.copyOf(result);
    }
    @Override public Optional<RuntimeCredential> runtimeCredentials(CredentialRequest target) {
        var configured = runtime.instances().get(target.instanceKey());
        if (configured == null) return Optional.empty();
        var fields = new LinkedHashMap<String, String>();
        if (configured.email() != null) fields.put("email", configured.email());
        if (configured.apiToken() != null) fields.put("apiToken", configured.apiToken());
        if (configured.cloudId() != null) fields.put("cloudId", configured.cloudId());
        Map<String, String> pins;
        try {
            pins = Map.of("instanceKey", target.instanceKey(),
                    "baseUrl", JiraConnection.origin(URI.create(configured.expectedSiteOrigin())).toString());
        } catch (IllegalArgumentException | NullPointerException | io.givops.ticketing.model.TicketException exception) {
            // Invalid configuration remains identifiable, but cannot match a valid destination.
            pins = Map.of("baseUrl", "");
        }
        return Optional.of(new RuntimeCredential(fields, pins, configured.accessRevision()));
    }

    private static final class Artifact {
        private static final String VERSION = version();
        private static String version() {
            var properties = new Properties();
            try (var stream = JiraProviderDefinition.class.getResourceAsStream("/META-INF/givops/adapter-jira.properties")) {
                if (stream == null) throw new IllegalStateException("Missing Jira artifact metadata.");
                properties.load(stream);
                return properties.getProperty("version");
            } catch (java.io.IOException exception) {
                throw new IllegalStateException("Cannot read Jira artifact metadata.", exception);
            }
        }
    }
}
