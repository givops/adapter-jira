package io.givops.ticketing.jira;

import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import java.net.URI;
import java.util.List;
import io.givops.adapters.ContextSigner;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

final class JiraConnection {
    private final URI site;
    private final String api;
    private final JiraProperties.Instance credentials;
    private final ContextSigner signer;

    JiraConnection(InstanceSource source, JiraProperties.Instance credentials, ContextSigner signer) {
        if (!TicketProvider.JIRA_CLOUD.equals(source.provider()) || source.instanceId() == null
                || source.instanceKey() == null) {
            throw invalidConfiguration();
        }
        this.credentials = credentials;
        if (credentials == null || missing(credentials.email()) || missing(credentials.apiToken())) {
            throw new TicketException(Code.MISSING_CREDENTIALS, JiraMessages.format("ticketing.jiraConnection.jira.credentials.are.missing"));
        }
        this.signer = java.util.Objects.requireNonNull(signer);
        if (!validEmail(credentials.email()) || !validToken(credentials.apiToken())) {
            throw invalidConfiguration();
        }
        try {
            site = origin(source.baseUrl());
            if (!site.equals(origin(URI.create(credentials.expectedSiteOrigin())))) {
                throw invalidConfiguration();
            }
            String cloudId = credentials.cloudId();
            String apiBase = site.toString();
            if (cloudId != null) {
                apiBase = "https://api.atlassian.com/ex/jira/" + canonicalCloudId(cloudId);
            }
            api = apiBase + "/rest/api/3";
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalidConfiguration();
        }
    }

    static boolean validEmail(String email) {
        return validToken(email) && email.contains("@") && !email.contains(":");
    }

    static boolean validToken(String token) {
        return !missing(token) && token.codePoints().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c));
    }

    static String canonicalCloudId(String cloudId) {
        if (cloudId == null) {
            return null;
        }
        try {
            String canonical = UUID.fromString(cloudId).toString();
            if (canonical.equalsIgnoreCase(cloudId)) {
                return canonical;
            }
        } catch (IllegalArgumentException exception) {
            throw invalidConfiguration();
        }
        throw invalidConfiguration();
    }

    URI endpoint(String path) {
        return URI.create(api + path);
    }

    URI browse(String key) {
        return site.resolve("/browse/" + key);
    }

    String authorization() {
        String userToken = credentials.email() + ":" + credentials.apiToken();
        return "Basic " + Base64.getEncoder().encodeToString(userToken.getBytes(StandardCharsets.UTF_8));
    }

    String context(InstanceSource source) {
        return signer.sign(TicketProvider.JIRA_CLOUD.name(), "givops-jira-cloud-v1", List.of(
                source.instanceId().toString(), source.instanceKey(), site.toString(), api,
                credentials.email(), credentials.apiToken(),
                credentials.accessRevision() == null ? "" : credentials.accessRevision()));
    }

    static TicketException invalidConfiguration() {
        return new TicketException(Code.INVALID_CONFIGURATION, JiraMessages.format("ticketing.jiraConnection.the.jira.cloud.connection.configuration.is.invalid"));
    }

    static URI origin(URI uri) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getRawPath().isEmpty() || uri.getRawPath().equals("/"))
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw invalidConfiguration();
        }
        String host = uri.getHost();
        int port = uri.getPort();
        String authority = host + (port == -1 ? "" : ":" + port);
        if (!authority.equalsIgnoreCase(uri.getRawAuthority())) {
            throw invalidConfiguration();
        }
        return URI.create("https://" + host.toLowerCase(Locale.ROOT)
                + (port == -1 || port == 443 ? "" : ":" + port));
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }
}
