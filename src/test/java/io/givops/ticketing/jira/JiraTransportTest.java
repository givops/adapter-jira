package io.givops.ticketing.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.port.TicketRequestGate;
import java.io.IOException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JiraTransportTest {
    @Test
    void selectsTheRegisteredInstanceClientForReadsRetriesAndWrites() {
        var target = io.givops.adapters.ConnectionTarget.ticketing(java.util.UUID.randomUUID());
        var selected = new java.util.concurrent.atomic.AtomicInteger();
        try (var trusted = JiraTransport.clientBuilder(CONNECT_TIMEOUT).sslContext(clientTls).build()) {
            io.givops.adapters.ConnectionClients clients = (actual, destination, fallback) -> {
                assertThat(destination.getHost()).isEqualTo("localhost");
                if (target.equals(actual)) { selected.incrementAndGet(); return trusted; }
                return fallback;
            };
            try (var transport = new JiraTransport(JiraTransport.clientBuilder(CONNECT_TIMEOUT).build(), 1024, CLOCK, clients);
                 var writes = new JiraWriteTransport(JiraTransport.clientBuilder(CONNECT_TIMEOUT).build(), 1024, clients)) {
                var gate = new Gate();
                gate.target = target;
                assertThat(read(transport, uri("localhost", "/ok"), gate)).isEqualTo("{}");
                assertFailure(Code.PROVIDER_ERROR, () -> read(transport, uri("localhost", "/unavailable"), gate));
                var response = writes.post(uri("localhost", "/created"), AUTHORIZATION, new byte[0], gate);
                assertThat(response.status()).isEqualTo(201);
                assertThat(selected).hasValue(4);
                gate.target = io.givops.adapters.ConnectionTarget.ticketing(java.util.UUID.randomUUID());
                assertFailure(Code.PROVIDER_ERROR, () -> read(transport, uri("localhost", "/ok"), gate));
                assertThat(selected).hasValue(4);
            }
            assertThat(trusted.isTerminated()).isFalse();
        }
    }

    @Test
    void diagnosesHtmlApiRepliesWithoutReturningProviderSecrets() {
        try (var transport = transport(1024)) {
            assertThatExceptionOfType(TicketException.class)
                    .isThrownBy(() -> read(transport, uri("localhost", "/html"), new Gate()))
                    .satisfies(failure -> {
                        assertThat(failure.code()).isEqualTo(Code.INVALID_RESPONSE);
                        assertThat(failure.getMessage()).contains("HTTP 200", "HTML").doesNotContain(RESPONSE_SECRET);
                        assertThat(failure.diagnosticEvidence()).contains("HTTP 200", "HTML");
                    });
        }
    }
    private static final String AUTHORIZATION = "Basic Zml4dHVyZTpleGFtcGxl";
    private static final String RESPONSE_SECRET = "synthetic-error-body-secret";
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AtomicInteger REDIRECT_TARGET_REQUESTS = new AtomicInteger();
    private static final AtomicInteger THROTTLED_REQUESTS = new AtomicInteger();
    private static final CountDownLatch RELEASE_BODY = new CountDownLatch(1);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final char[] STORE_PASSWORD = "test-store".toCharArray();
    private static HttpsServer server;
    private static ExecutorService workers;
    private static SSLContext clientTls;

    @BeforeAll
    static void startServer(@TempDir Path temp) throws Exception {
        KeyStore keys = testKeys(temp);
        var keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManager.init(keys, STORE_PASSWORD);
        var serverTls = SSLContext.getInstance("TLS");
        serverTls.init(keyManager.getKeyManagers(), null, null);

        var trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("localhost", keys.getCertificate("localhost"));
        var trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManager.init(trusted);
        clientTls = SSLContext.getInstance("TLS");
        clientTls.init(null, trustManager.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverTls));
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/", JiraTransportTest::handle);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        RELEASE_BODY.countDown();
        if (server != null) {
            server.stop(0);
        }
        if (workers != null) {
            workers.close();
        }
    }

    @Test
    void verifiesCertificatesAndHostnamesAndCarriesBasicCredentialsOverTrustedTls() {
        try (var productionTrust = new JiraTransport(JiraTransport.clientBuilder(CONNECT_TIMEOUT).build(), 1024, CLOCK);
             var fixtureTrust = transport(1024)) {
            assertFailure(Code.PROVIDER_ERROR, () -> read(productionTrust, uri("localhost", "/ok"), new Gate()));
            assertFailure(Code.PROVIDER_ERROR, () -> read(fixtureTrust, uri("127.0.0.1", "/ok"), new Gate()));
            assertThat(read(fixtureTrust, uri("localhost", "/ok"), new Gate())).isEqualTo("{}");
        }
    }

    @Test
    void refusesRedirectsWithoutVisitingTheTarget() {
        try (var transport = transport(1024)) {
            assertFailure(Code.PROVIDER_ERROR, () -> read(transport, uri("localhost", "/redirect"), new Gate()));
            assertThat(REDIRECT_TARGET_REQUESTS.get()).isZero();
        }
    }

    @Test
    void rejectsAccessAsSoonAsDenialHeadersArrive() {
        try (var transport = transport(1)) {
            var gate = new Gate();
            gate.maxTotalBytes = 1;
            assertFailure(Code.AUTHORIZATION_FAILED, () -> read(transport, uri("localhost", "/denied"), gate));
            assertThat(gate.attempts).isOne();
            assertThat(gate.bytes.get()).isZero();
        }
    }

    @Test
    void limitsStreamedBytesBothPerResponseAndAcrossRequests() {
        try (var smallTransport = transport(8); var aggregateTransport = transport(1024)) {
            var responseBudget = new Gate();
            assertFailure(Code.LIMIT_EXCEEDED, () -> read(smallTransport, uri("localhost", "/large"), responseBudget));
            assertThat(responseBudget.bytes.get()).isGreaterThan(8);

            var sharedBudget = new Gate();
            sharedBudget.maxTotalBytes = 3;
            URI endpoint = uri("localhost", "/ok");
            assertThat(read(aggregateTransport, endpoint, sharedBudget)).isEqualTo("{}");
            assertFailure(Code.LIMIT_EXCEEDED, () -> read(aggregateTransport, endpoint, sharedBudget));
            assertThat(sharedBudget.attempts).isEqualTo(2);
        }
    }

    @Test
    void timesOutAfterReceivingHeadersAndPartOfTheBody() {
        try (var transport = transport(1024)) {
            read(transport, uri("localhost", "/ok"), new Gate());
            var gate = new Gate();
            gate.timeout = Duration.ofSeconds(2);
            try {
                assertFailure(Code.TIMEOUT, () -> read(transport, uri("localhost", "/slow"), gate));
                assertThat(gate.bytes.get()).isEqualTo(1);
            } finally {
                RELEASE_BODY.countDown();
            }
        }
    }

    @Test
    void admitsOneRetryForRateLimitingAndAtMostOneForTemporaryServiceFailures() {
        try (var transport = transport(1024)) {
            var throttled = new Gate();
            assertThat(read(transport, uri("localhost", "/throttled"), throttled)).isEqualTo("{}");
            assertThat(throttled.attempts).isEqualTo(2);
            assertThat(throttled.bytes.get()).isEqualTo(RESPONSE_SECRET.length() + 2);

            var unavailable = new Gate();
            assertFailure(Code.PROVIDER_ERROR, () -> read(transport, uri("localhost", "/unavailable"), unavailable));
            assertThat(unavailable.attempts).isEqualTo(2);
        }
    }

    @Test
    void reportsRetryAfterWhenItExceedsTheRefreshDeadline() {
        try (var transport = transport(1024)) {
            var gate = new Gate();
            assertThatExceptionOfType(TicketException.class)
                    .isThrownBy(() -> read(transport, uri("localhost", "/deferred"), gate))
                    .satisfies(failure -> {
                        assertThat(failure.code()).isEqualTo(Code.RATE_LIMITED);
                        assertThat(failure.retryAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
                        assertThat(failure).hasNoCause().hasMessageNotContaining(RESPONSE_SECRET);
                    });
            assertThat(gate.attempts).isOne();
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!AUTHORIZATION.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401, -1);
                return;
            }
            switch (exchange.getRequestURI().getPath()) {
                case "/ok" -> send(exchange, 200, "{}");
                case "/created" -> send(exchange, 201, "{}");
                case "/html" -> {
                    exchange.getResponseHeaders().set("Content-Type", "text/html; synthetic-secret=" + RESPONSE_SECRET);
                    send(exchange, 200, "<html>" + RESPONSE_SECRET + "</html>");
                }
                case "/denied" -> send(exchange, 403, RESPONSE_SECRET);
                case "/redirect" -> {
                    exchange.getResponseHeaders().set("Location", "/redirect-target");
                    exchange.sendResponseHeaders(302, -1);
                }
                case "/redirect-target" -> {
                    REDIRECT_TARGET_REQUESTS.incrementAndGet();
                    send(exchange, 200, "{}");
                }
                case "/large" -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(new byte[16]);
                }
                case "/slow" -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                    try {
                        RELEASE_BODY.await(8, TimeUnit.SECONDS);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }
                case "/throttled" -> {
                    if (THROTTLED_REQUESTS.getAndIncrement() == 0) {
                        retry(exchange, 429, "0");
                    } else {
                        send(exchange, 200, "{}");
                    }
                }
                case "/unavailable" -> retry(exchange, 503, "0");
                case "/deferred" -> retry(exchange, 429, "3600");
                default -> exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    private static void retry(HttpExchange exchange, int status, String retryAfter) throws IOException {
        exchange.getResponseHeaders().set("Retry-After", retryAfter);
        send(exchange, status, RESPONSE_SECRET);
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static void assertFailure(Code code, Runnable action) {
        assertThatExceptionOfType(TicketException.class).isThrownBy(action::run)
                .satisfies(failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static JiraTransport transport(long maxBytes) {
        return new JiraTransport(JiraTransport.clientBuilder(CONNECT_TIMEOUT).sslContext(clientTls).build(),
                maxBytes, CLOCK);
    }

    private static String read(JiraTransport transport, URI endpoint, Gate gate) {
        return new String(transport.read(endpoint, AUTHORIZATION, null, gate), StandardCharsets.UTF_8);
    }

    private static URI uri(String host, String path) {
        return URI.create("https://" + host + ":" + server.getAddress().getPort() + path);
    }

    private static KeyStore testKeys(Path temp) throws Exception {
        var storeFile = temp.resolve("localhost.p12");
        var keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool");
        Process process = new ProcessBuilder(keytool.toString(), "-genkeypair", "-noprompt",
                "-alias", "localhost", "-keyalg", "EC", "-groupname", "secp256r1",
                "-storetype", "PKCS12", "-keystore", storeFile.toString(),
                "-storepass", String.valueOf(STORE_PASSWORD), "-dname", "CN=localhost", "-ext", "SAN=dns:localhost",
                "-startdate", "2020/01/01 00:00:00", "-validity", "7305").redirectErrorStream(true).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Test TLS certificate generation timed out.");
        }
        try (var output = process.getInputStream()) {
            assertThat(process.exitValue()).as(new String(output.readAllBytes(), StandardCharsets.UTF_8)).isZero();
        }
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(storeFile)) {
            keys.load(input, STORE_PASSWORD);
        }
        return keys;
    }

    private static final class Gate implements TicketRequestGate {
        private io.givops.adapters.ConnectionTarget target;
        @Override public io.givops.adapters.ConnectionTarget connectionTarget() { return target; }
        private int attempts;
        private final AtomicLong bytes = new AtomicLong();
        private long maxTotalBytes = Long.MAX_VALUE;
        private Duration timeout = Duration.ofSeconds(10);

        @Override
        public Duration beforeRequest() {
            attempts++;
            return timeout;
        }

        @Override
        public Duration remaining() {
            return Duration.ofSeconds(120);
        }

        @Override
        public void receivedBytes(long count) {
            if (bytes.addAndGet(count) > maxTotalBytes) {
                throw new TicketException(Code.LIMIT_EXCEEDED, "Fixture refresh exhausted its byte budget.");
            }
        }

        @Override
        public void acceptedObjects(int count) {}

        @Override
        public void check() {}
    }
}
