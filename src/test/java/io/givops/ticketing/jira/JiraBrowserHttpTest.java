package io.givops.ticketing.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.givops.adapters.CredentialAccessException;
import io.givops.adapters.CredentialLease;
import io.givops.adapters.testkit.AdapterTestkit;
import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketBrowserModels.*;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.port.TicketRequestGate;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Real HTTPS fixtures cover browser payloads, origin-scoped auth, pagination and transient content. */
class JiraBrowserHttpTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-17T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static SSLContext serverTls, clientTls;
    private static final TicketProperties LIMITS = new TicketProperties(true, Duration.ofSeconds(30),
            Duration.ofSeconds(5), 100, 20, 2 * 1024 * 1024, 16 * 1024 * 1024, 500, 500, 1000,
            5000, 8 * 1024 * 1024, 20, 500, 10000, Duration.ofMinutes(15), 50, 500);

    @BeforeAll static void tls(@TempDir Path temp) throws Exception {
        char[] password = "synthetic-store".toCharArray();
        Path store = temp.resolve("fixture.p12");
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-noprompt", "-alias", "localhost", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-storetype", "PKCS12",
                "-keystore", store.toString(), "-storepass", new String(password)).redirectErrorStream(true).start();
        assertThat(process.waitFor()).isZero();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, password); }
        var km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(keys, password);
        serverTls = SSLContext.getInstance("TLS"); serverTls.init(km.getKeyManagers(), null, null);
        var trusted = KeyStore.getInstance("PKCS12"); trusted.load(null, null);
        trusted.setCertificateEntry("localhost", keys.getCertificate("localhost"));
        var tm = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); tm.init(trusted);
        clientTls = SSLContext.getInstance("TLS"); clientTls.init(null, tm.getTrustManagers(), null);
    }

    private static io.givops.ticketing.model.TicketScopeModels.Scope scope(UUID binding, String value) {
        return new io.givops.ticketing.model.TicketScopeModels.Scope(binding,
                new io.givops.ticketing.model.TicketScopeModels.Field("components", "Components",
                io.givops.ticketing.model.TicketScopeModels.ValueKind.COMPONENT,
                io.givops.ticketing.model.TicketScopeModels.Cardinality.MANY,
                io.givops.ticketing.model.TicketScopeModels.Match.CONTAINS,
                io.givops.adapters.CapabilityInspection.State.AVAILABLE, null), value, "Back ", 2, 3);
    }
    private static String component(String row, String values) {
        return row.replace("\"fields\":{", "\"fields\":{\"components\":" + values + ",");
    }
    @Test void scopedDirectDetailAndContentCannotReadKnownForeignIssueIds() throws Exception {
        try (var f = new Fixture(page("", null), component(issue("30001", "APP-1", "10101", "Foreign", "[]"), "[{\"id\":\"42\"}]"),
                component(contentRow("30001", "10101", "\"SECRET-FOREIGN-BODY\""), "[{\"id\":\"42\"}]"))) {
            var scope = scope(f.binding, "41");
            var page = f.adapter.listScoped(f.request(null), scope, query("", null), f.access, f.gate);
            failure(Code.NOT_FOUND, () -> f.adapter.detailScoped(f.request(null), scope, f.ref("30001"), f.access, f.gate));
            failure(Code.NOT_FOUND, () -> f.adapter.contentScoped(f.request(page.revision()), scope, f.ref("30001"), f.access, f.gate));
            assertThat(f.calls.get(2).path()).contains("components");
        }
    }
    @Test void scopedCursorAndContentRevisionBindValueAndPolicy() throws Exception {
        String row = component(issue("30001", "APP-1", "10101", "Shared", "[]"), "[{\"id\":\"41\"},{\"id\":\"42\"}]")
                .replace("\"id\":\"20001\"", "\"id\":\"20001\",\"name\":\"Back 1.0.0\"");
        try (var f = new Fixture(page(row, "next"), component(contentRow("30001", "10101", "\"Scoped body\""), "[{\"id\":\"41\"}]"))) {
            var back = scope(f.binding, "41"); var front = scope(f.binding, "42");
            var page = f.adapter.listScoped(f.request(null), back, query("", null), f.access, f.gate);
            assertThat(page.items()).hasSize(1);
            assertThat(JSON.readTree(f.calls.getFirst().body()).get("jql").stringValue()).contains("component = 41");
            failure(Code.INVALID_CONFIGURATION, () -> f.adapter.listScoped(f.request(null), front, query("", page.nextContinuation()), f.access, f.gate));
            failure(Code.STALE_CONTEXT, () -> f.adapter.contentScoped(f.request(page.revision()), front, f.ref("30001"), f.access, f.gate));
            assertThat(f.adapter.contentScoped(f.request(page.revision()), back, f.ref("30001"), f.access, f.gate).text()).isEqualTo("Scoped body");
        }
    }
    @Test void describesCapabilitiesWithoutHttpAndRejectsForgedSnapshotRevision() throws Exception {
        try (var f = new Fixture()) {
            var descriptor = f.adapter.describe(f.request(null), f.access, f.gate);
            assertThat(descriptor.queryFields()).containsExactly("text", "statuses", "categories", "labels:any");
            assertThat(descriptor.lifecycles()).containsExactly(Lifecycle.ACTIVE);
            assertThat(descriptor.sorts()).containsExactly("key-asc", "updated-desc");
            assertThat(descriptor.snapshot()).isFalse(); assertThat(descriptor.content()).isTrue(); assertThat(descriptor.assets()).isFalse();
            for (var revision : List.of(new SourceRevision("git", "bad", true),
                    new SourceRevision("jira-live", f.source.instanceId() + ":10101", true), new SourceRevision("jira-live", "other", false))) {
                failure(Code.STALE_CONTEXT, () -> f.adapter.describe(f.request(revision), f.access, f.gate));
            }
            assertThat(f.calls).isEmpty();
        }
    }

    @Test void combinesFilterFamiliesAndQuotesLiteralSearchAndFollowsOpaqueCloudPagination() throws Exception {
        try (var f = new Fixture(page(issue("30001", "APP-1", "10101", "Synthetic title", "[]"), "opaque-token"),
                page(issue("30002", "RENAMED-2", "10101", "Another title", "[]"), null))) {
            var query = new BrowseQuery("release", null, List.of("Open", "In Progress"), List.of("Task", "Bug"),
                    List.of("frontend", "urgent"), "updated-desc", 2, null);
            var first = f.adapter.list(f.request(null), query, f.access, f.gate);
            assertThat(first.items()).singleElement().satisfies(item -> {
                assertThat(item.ref().externalIssueId()).isEqualTo("30001");
                assertThat(item.title()).isEqualTo("Synthetic title"); assertThat(item.outcome()).isEqualTo(Outcome.IN_PROGRESS);
                assertThat(item.category()).isEqualTo("Task"); assertThat(item.contentAvailable()).isTrue();
                assertThat(item.revision().immutable()).isFalse();
            });
            assertThat(first.exactTotal()).isNull(); assertThat(first.capturedAt()).isEqualTo(NOW);
            assertThat(first.nextContinuation()).isNotEqualTo("opaque-token").hasSizeLessThanOrEqualTo(4096); assertThat(first.effectiveQuery()).isEqualTo(query);
            var body = JSON.readTree(f.calls.getFirst().body());
            assertThat(body.get("jql").stringValue()).isEqualTo("project = 10101 AND summary ~ \"\\\"release\\\"\" AND status in (\"In Progress\", \"Open\") AND issuetype in (\"Bug\", \"Task\") AND labels in (\"frontend\", \"urgent\") ORDER BY updated DESC, key ASC");
            assertThat(body.get("fields")).extracting(JsonNode::stringValue).contains("labels", "summary").doesNotContain("description", "comment");
            assertThat(body.get("maxResults").intValue()).isEqualTo(2);
            var secondQuery = new BrowseQuery(query.text(), query.lifecycle(), query.statuses(), query.categories(), query.labels(),
                    query.sort(), query.pageSize(), first.nextContinuation());
            var second = f.adapter.list(f.request(first.revision()), secondQuery, f.access, f.gate);
            assertThat(second.revision()).isEqualTo(first.revision()); assertThat(second.nextContinuation()).isNull();
            assertThat(second.items().getFirst().displayKey()).isEqualTo("RENAMED-2");
            assertThat(JSON.readTree(f.calls.getLast().body()).get("nextPageToken").stringValue()).isEqualTo("opaque-token");
            assertThat(f.calls).allSatisfy(call -> {
                assertThat(call.method()).isEqualTo("POST"); assertThat(call.path()).isEqualTo("/rest/api/3/search/jql");
                assertThat(call.authorization()).isEqualTo("Basic " + Base64.getEncoder().encodeToString("reader@example.test:synthetic-token".getBytes(StandardCharsets.UTF_8)));
            });
            assertThat(f.gate.attempts).isEqualTo(2); assertThat(f.gate.objects).isEqualTo(2); assertThat(f.gate.bytes).isPositive();
        }
    }

    @Test void quotesKeyAndAllQueryValuesWithoutAcceptingRawJql() throws Exception {
        try (var f = new Fixture(page("", null), page("", null))) {
            f.adapter.list(f.request(null), new BrowseQuery("APP-7", null, List.of("\" OR project = 999"), null, null, null, 1, null), f.access, f.gate);
            assertThat(JSON.readTree(f.calls.getFirst().body()).get("jql").stringValue())
                    .startsWith("project = 10101 AND (key = \"APP-7\" OR summary ~ ").contains("status in (\"\\\" OR project = 999\")");
            f.adapter.list(f.request(null), query("\\\" OR project = 999", null), f.access, f.gate);
            assertThat(JSON.readTree(f.calls.getLast().body()).get("jql").stringValue()).startsWith("project = 10101 AND summary ~ ");
        }
    }

    @Test void readsDetailByStableIdAndKeepsDescriptionOnSeparateContentPath() throws Exception {
        String row = issue("30001", "RENAMED-7", "10101", "Title", "[\"urgent\"]");
        String adf = """
            {"version":1,"type":"doc","content":[
              {"type":"paragraph","content":[{"type":"text","text":"<script>literal</script>","marks":[{"type":"link","attrs":{"href":"javascript:bad()"}}]},
                {"type":"hardBreak"},{"type":"mention","attrs":{"text":"@Reader","id":"secret-user"}}]},
              {"type":"codeBlock","content":[{"type":"text","text":"echo test"}]},
              {"type":"mediaSingle","content":[{"type":"media","attrs":{"url":"https://bad.test/secret","id":"secret-asset"}}]},
              {"type":"extension","attrs":{"text":"hidden-extension"}},
              {"type":"paragraph","content":[{"type":"text","text":"Last"}]}]}
            """;
        try (var f = new Fixture(row, contentRow("30001", "10101", adf))) {
            var detail = f.adapter.detail(f.request(null), f.ref("30001"), f.access, f.gate);
            assertThat(detail.ticket().displayKey()).isEqualTo("RENAMED-7"); assertThat(detail.ticket().labels()).containsExactly("urgent");
            assertThat(detail.ticket().ref()).isEqualTo(f.ref("30001")); assertThat(detail.releaseVersionIds()).containsExactly("20001");
            assertThat(detail.createdAt()).isEqualTo(Instant.parse("2026-10-01T12:30:00Z"));
            assertThat(detail.navigation().ticketUrl()).isEqualTo(f.source.baseUrl().resolve("/browse/RENAMED-7"));
            assertThat(f.calls.getFirst().path()).startsWith("/rest/api/3/issue/30001?fields=").doesNotContain("description");
            var content = f.adapter.content(new BrowserRequest(f.source, f.binding, "10101", "APP", detail.ticket().revision(), NOW.plusSeconds(1)), f.ref("30001"), f.access, f.gate);
            assertThat(content.text()).isEqualTo("<script>literal</script>\n@Reader\necho test\nLast");
            assertThat(content.format()).isEqualTo(ContentFormat.PLAIN_TEXT); assertThat(content.assets()).isEmpty(); assertThat(content.links()).isEmpty();
            assertThat(content.revision()).isEqualTo(detail.ticket().revision());
            assertThat(content.toString()).isEqualTo("TicketContent[redacted]");
            assertThat(f.calls.getLast().path()).isEqualTo("/rest/api/3/issue/30001?fields=project,description");
            assertThat(f.calls).allSatisfy(call -> assertThat(call.method()).isEqualTo("GET"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"null", "\"Legacy plain text\"", "{\"version\":1,\"type\":\"doc\",\"content\":[]}"})
    void supportsAbsentAndLegacyPlainDescriptions(String description) throws Exception {
        try (var f = new Fixture(contentRow("30001", "10101", description))) {
            assertThat(f.adapter.content(f.request(null), f.ref("30001"), f.access, f.gate).text())
                    .isEqualTo(description.startsWith("\"") ? "Legacy plain text" : "");
        }
    }

    @ParameterizedTest @ValueSource(strings = {
            "{\"issues\":[],\"isLast\":false}", "{\"issues\":[],\"isLast\":false,\"nextPageToken\":\"next\"}",
            "{\"issues\":[],\"isLast\":true,\"nextPageToken\":\"next\"}", "{\"issues\":[]}",
            "{\"issues\":[],\"isLast\":true,\"isLast\":true}", "{\"issues\":[],\"isLast\":true} {}"})
    void rejectsMalformedCloudPages(String response) throws Exception {
        try (var f = new Fixture(response)) { failure(Code.INVALID_RESPONSE, () -> f.adapter.list(f.request(null), query("", null), f.access, f.gate)); }
    }

    @Test void rejectsRepeatedTokenDuplicatesOversizedPagesAndCrossProjectIdentities() throws Exception {
        String row = issue("30001", "APP-1", "10101", "Title", "[]");
        for (String response : List.of(page(row + "," + row, null),
                page(issue("30001", "APP-1", "99999", "Title", "[]"), null))) {
            try (var f = new Fixture(response)) { failure(Code.INVALID_RESPONSE, () -> f.adapter.list(f.request(null), query("", null), f.access, f.gate)); }
        }
        try (var f = new Fixture(page(row + "," + row, null))) {
            var q = new BrowseQuery("", null, null, null, null, null, 1, null);
            failure(Code.INVALID_RESPONSE, () -> f.adapter.list(f.request(null), q, f.access, f.gate));
        }
        try (var f = new Fixture(contentRow("30002", "10101", "null"), contentRow("30001", "99999", "null"))) {
            failure(Code.INVALID_RESPONSE, () -> f.adapter.content(f.request(null), f.ref("30001"), f.access, f.gate));
            failure(Code.INVALID_RESPONSE, () -> f.adapter.content(f.request(null), f.ref("30001"), f.access, f.gate));
        }
    }


    @Test void bindsContinuationToScopeAndQueryAndRejectsRepeatedAndOversizedProviderTokens() throws Exception {
        String row = issue("30001", "APP-1", "10101", "Title", "[]");
        try (var f = new Fixture(page(row, "same"), page(row, "same"))) {
            var first = f.adapter.list(f.request(null), query("", null), f.access, f.gate);
            failure(Code.INVALID_CONFIGURATION, () -> f.adapter.list(f.request(first.revision()), query("changed", first.nextContinuation()), f.access, f.gate));
            var wrongBinding = new BrowserRequest(f.source, UUID.randomUUID(), "10101", "APP", null, NOW);
            failure(Code.INVALID_CONFIGURATION, () -> f.adapter.list(wrongBinding, query("", first.nextContinuation()), f.access, f.gate));
            failure(Code.INVALID_CONFIGURATION, () -> f.adapter.list(f.request(null), query("", "bare-provider-token"), f.access, f.gate));
            failure(Code.INVALID_RESPONSE, () -> f.adapter.list(f.request(first.revision()), query("", first.nextContinuation()), f.access, f.gate));
            assertThat(f.calls).hasSize(2);
        }
        try (var f = new Fixture(page(row, "x".repeat(3000)))) {
            failure(Code.LIMIT_EXCEEDED, () -> f.adapter.list(f.request(null), query("", null), f.access, f.gate));
        }
    }

    @Test void checksAccessGateAndInputBeforeHttp() throws Exception {
        try (var f = new Fixture()) {
            failure(Code.INVALID_CONFIGURATION, () -> f.adapter.detail(f.request(null), f.ref("../secret"), f.access, f.gate));
            failure(Code.INVALID_CONFIGURATION, () -> f.adapter.content(f.request(null), new TicketRef(UUID.randomUUID(), "10101", "30001"), f.access, f.gate));
            failure(Code.INVALID_CONFIGURATION, () -> f.adapter.list(f.request(null), new BrowseQuery("", Lifecycle.ARCHIVED, null, null, null, null, 2, null), f.access, f.gate));
            failure(Code.INVALID_CONFIGURATION, () -> f.adapter.list(f.request(null), new BrowseQuery("", null, null, null, null, "title-asc", 2, null), f.access, f.gate));
            f.current.set(false);
            failure(Code.SUPERSEDED, () -> f.adapter.describe(f.request(null), f.access, f.gate));
            failure(Code.SUPERSEDED, () -> f.adapter.content(f.request(null), f.ref("30001"), f.access, f.gate));
            f.current.set(true); f.gate.cancelled = true;
            failure(Code.TIMEOUT, () -> f.adapter.list(f.request(null), query("", null), f.access, f.gate));
            assertThat(f.calls).isEmpty();
        }
    }

    @Test void revalidatesAccessAfterHttpAndRejectsOverBudgetContent() throws Exception {
        try (var f = new Fixture(page("", null))) {
            f.afterResponse = () -> f.current.set(false);
            failure(Code.SUPERSEDED, () -> f.adapter.list(f.request(null), query("", null), f.access, f.gate));
        }
        try (var f = new Fixture(contentRow("30001", "10101", JSON.writeValueAsString("é".repeat(530_000))))) {
            failure(Code.LIMIT_EXCEEDED, () -> f.adapter.content(f.request(null), f.ref("30001"), f.access, f.gate));
        }
        String nested = "{\"type\":\"text\",\"text\":\"leaf\"}";
        for (int i = 0; i < 66; i++) nested = "{\"type\":\"paragraph\",\"content\":[" + nested + "]}";
        try (var f = new Fixture(contentRow("30001", "10101", "{\"type\":\"doc\",\"version\":1,\"content\":[" + nested + "]}"))) {
            failure(Code.LIMIT_EXCEEDED, () -> f.adapter.content(f.request(null), f.ref("30001"), f.access, f.gate));
        }
    }

    private static BrowseQuery query(String text, String continuation) { return new BrowseQuery(text, null, null, null, null, null, 2, continuation); }
    private static String page(String rows, String token) { return "{\"issues\":[" + rows + "],\"isLast\":" + (token == null) + (token == null ? "" : ",\"nextPageToken\":\"" + token + "\"") + "}"; }
    private static String issue(String id, String key, String project, String title, String labels) {
        return """
            {"id":"%s","key":"%s","fields":{"project":{"id":"%s"},"summary":"%s","issuetype":{"name":"Task"},
            "status":{"name":"In Progress","statusCategory":{"key":"indeterminate"}},"labels":%s,
            "created":"2026-10-01T12:30:00Z","updated":"2026-10-09T10:00:00Z","fixVersions":[{"id":"20001"}]}}
            """.formatted(id, key, project, title, labels);
    }
    private static String contentRow(String id, String project, String description) {
        return "{\"id\":\"" + id + "\",\"key\":\"APP-1\",\"fields\":{\"project\":{\"id\":\"" + project + "\"},\"description\":" + description + "}}";
    }
    private static void failure(Code code, Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(TicketException.class, ex -> assertThat(ex.code()).isEqualTo(code));
    }
    private record Call(String method, String path, String body, String authorization) { }
    private static class Gate implements TicketRequestGate {
        int attempts, objects; long bytes; boolean cancelled;
        @Override public Duration beforeRequest() { check(); attempts++; return Duration.ofSeconds(5); }
        @Override public Duration remaining() { return Duration.ofSeconds(30); }
        @Override public void receivedBytes(long count) { bytes += count; }
        @Override public void acceptedObjects(int count) { objects += count; }
        @Override public void check() { if (cancelled) throw new TicketException(Code.TIMEOUT, "Synthetic cancellation"); }
    }
    private static final class Fixture implements AutoCloseable {
        final HttpsServer server;
        final JiraTransport transport;
        final JiraAdapter adapter;
        final InstanceSource source;
        final UUID binding = UUID.randomUUID();
        final JiraAccess.Captured access;
        final AtomicBoolean current = new AtomicBoolean(true);
        final Gate gate = new Gate();
        final List<Call> calls = new ArrayList<>();
        Runnable afterResponse = () -> { };
        Fixture(String... replies) throws Exception {
            var remaining = new ArrayDeque<>(List.of(replies));
            server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
            server.createContext("/", exchange -> {
                try (exchange) {
                    calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                            new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), exchange.getRequestHeaders().getFirst("Authorization")));
                    byte[] bytes = remaining.removeFirst().getBytes(StandardCharsets.UTF_8);
                    afterResponse.run();
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                }
            });
            server.start();
            URI site = URI.create("https://localhost:" + server.getAddress().getPort());
            source = new InstanceSource(UUID.randomUUID(), "jira", TicketProvider.JIRA_CLOUD, site, 0);
            var fields = Map.of("email", "reader@example.test", "apiToken", "synthetic-token");
            var destinations = Map.of("instanceKey", "jira", "baseUrl", site.toString());
            var credentials = new JiraAccess(request -> new CredentialLease() {
                public Map<String, String> fields() { return fields; }
                public Map<String, String> destinations() { return destinations; }
                public String accessRevision() { return "synthetic-revision"; }
                public boolean legacyRuntime() { return false; }
                public String mode() { return "SYNTHETIC"; }
                public void requireCurrent(boolean locked) { if (!current.get()) throw new CredentialAccessException(); }
            }, AdapterTestkit.signer("synthetic-context-signing-key-at-least-32-characters"));
            transport = new JiraTransport(JiraTransport.clientBuilder(Duration.ofSeconds(5)).sslContext(clientTls).build(), LIMITS.maxResponseBytes(), CLOCK);
            adapter = new JiraAdapter(credentials, LIMITS, transport, JSON, CLOCK); access = credentials.capture(source);
        }
        BrowserRequest request(SourceRevision revision) { return new BrowserRequest(source, binding, "10101", "APP", revision, NOW); }
        TicketRef ref(String id) { return new TicketRef(source.instanceId(), "10101", id); }
        @Override public void close() { transport.close(); server.stop(0); }
    }
}
