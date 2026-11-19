package io.givops.ticketing.jira;

import io.givops.adapters.ConnectionClients;
import io.givops.adapters.ConnectionFailure;

import io.givops.ticketing.port.TicketRequestGate;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One network attempt only. A write whose delivery is uncertain must never be replayed. */
final class JiraWriteTransport implements AutoCloseable {
    record Response(int status, byte[] body, String evidence) {
        Response(int status, byte[] body) { this(status, body, ConnectionFailure.httpEvidence(status, null)); }
    }
    static final class Failure extends RuntimeException {
        private final String code;
        private final boolean uncertain;
        Failure(String code, boolean uncertain) { this(code, uncertain, JiraMessages.format("ticketing.jiraWriteTransport.the.jira.write.could.not.be.confirmed")); }
        Failure(String code, boolean uncertain, String evidence) { super(evidence); this.code = code; this.uncertain = uncertain; }
        String code() { return code; }
        boolean uncertain() { return uncertain; }
    }
    private final HttpClient client;
    private final ConnectionClients clients;
    private final long limit;
    JiraWriteTransport(HttpClient client, long limit) { this(client, limit, ConnectionClients.SYSTEM); }
    JiraWriteTransport(HttpClient client, long limit, ConnectionClients clients) { this.client = client; this.limit = limit; this.clients = clients; }

    Response post(URI endpoint, String authorization, byte[] body, TicketRequestGate gate) {
        Duration timeout = gate.beforeRequest();
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("Accept", "application/json")
                .header("Accept-Encoding", "identity").header("Authorization", authorization)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        var pending = clients.select(gate.connectionTarget(), request.uri(), client).sendAsync(request, info -> new Body(limit, info.statusCode(), gate));
        try {
            var response = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            String mediaType = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip();
            String evidence = ConnectionFailure.httpEvidence(response.statusCode(), mediaType);
            if (response.statusCode() == 201 && (mediaType.equalsIgnoreCase("text/html") || mediaType.equalsIgnoreCase("application/xhtml+xml"))) {
                throw new Failure("release_jira_outcome_unknown", true, evidence);
            }
            return new Response(response.statusCode(), response.body(), evidence);
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new Failure("release_jira_interrupted", true, ConnectionFailure.evidence(e));
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new Failure("release_jira_timeout", true, ConnectionFailure.evidence(e));
        } catch (ExecutionException e) {
            throw new Failure("release_jira_outcome_unknown", true, ConnectionFailure.evidence(e.getCause()));
        }
    }
    @Override public void close() { client.close(); }

    private static final class Body implements HttpResponse.BodySubscriber<byte[]> {
        private final long limit;
        private final int status;
        private final TicketRequestGate gate;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        Body(long limit, int status, TicketRequestGate gate) { this.limit = limit; this.status = status; this.gate = gate; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (status != 201 && status != 204) {
                // The status is sufficient; never retain or expose provider error bodies.
                result.complete(new byte[0]);
                subscription.cancel();
            } else subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            try {
                for (var buffer : buffers) {
                    int count = buffer.remaining();
                    if (count > limit - bytes.size()) throw new Failure("release_jira_response_limit", true);
                    gate.receivedBytes(count);
                    byte[] chunk = new byte[count]; buffer.get(chunk); bytes.writeBytes(chunk);
                }
                subscription.request(1);
            } catch (RuntimeException e) { result.completeExceptionally(e); subscription.cancel(); }
        }
        @Override public void onError(Throwable e) { result.completeExceptionally(e); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
