package io.givops.ticketing.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import io.givops.ticketing.port.TicketRequestGate;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Loopback HTTP fixtures exercise real transport delivery without any external provider. */
class JiraWriteTransportHttpTest {
    @Test void real429And503AndRedirectResponsesNeverReplayOrFollowLocation() throws Exception {
        var requests = new AtomicInteger(); var redirected = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/target", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(201, -1); exchange.close(); });
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            int status = Integer.parseInt(exchange.getRequestURI().getPath().substring(1));
            exchange.getResponseHeaders().add("Location", "/target");
            exchange.getResponseHeaders().add("Retry-After", "0");
            exchange.sendResponseHeaders(status, -1); exchange.close();
        });
        server.start();
        try (var transport = new JiraWriteTransport(JiraTransport.clientBuilder(Duration.ofSeconds(2)).build(), 1024)) {
            for (int status : new int[] {429, 503, 302}) {
                var result = transport.post(uri(server, "/" + status), "Basic synthetic-fixture", "{}".getBytes(StandardCharsets.UTF_8), new Gate());
                assertThat(result.status()).isEqualTo(status);
            }
            assertThat(requests).hasValue(3); assertThat(redirected).hasValue(0);
            assertThat(JiraTransport.clientBuilder(Duration.ofSeconds(1)).build().followRedirects()).isEqualTo(HttpClient.Redirect.NEVER);
        } finally { server.stop(0); }
    }

    @Test void acceptedOversizedRealBodyIsUnknownAndNeverReplayed() throws Exception {
        var requests = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet(); byte[] body = "x".repeat(2048).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(201, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            exchange.close();
        });
        server.start();
        try (var transport = new JiraWriteTransport(JiraTransport.clientBuilder(Duration.ofSeconds(2)).build(), 32)) {
            assertThatThrownBy(() -> transport.post(uri(server, "/"), "Basic synthetic-fixture", new byte[0], new Gate()))
                    .isInstanceOfSatisfying(JiraWriteTransport.Failure.class, failure -> assertThat(failure.uncertain()).isTrue());
            assertThat(requests).hasValue(1);
        } finally { server.stop(0); }
    }

    private static URI uri(HttpServer server, String path) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path); }
    private static class Gate implements TicketRequestGate {
        @Override public Duration beforeRequest() { return Duration.ofSeconds(2); }
        @Override public Duration remaining() { return Duration.ofSeconds(10); }
        @Override public void receivedBytes(long count) { }
        @Override public void acceptedObjects(int count) { }
        @Override public void check() { }
    }
}
