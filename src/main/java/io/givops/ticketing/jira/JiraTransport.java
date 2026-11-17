package io.givops.ticketing.jira;

import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.port.TicketRequestGate;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class JiraTransport implements AutoCloseable {
    private final HttpClient client;
    private final long maxResponseBytes;
    private final Clock clock;

    JiraTransport(HttpClient client, long maxResponseBytes, Clock clock) {
        this.client = client;
        this.maxResponseBytes = maxResponseBytes;
        this.clock = clock;
    }

    static HttpClient.Builder clientBuilder(Duration connectTimeout) {
        return HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(HttpClient.Redirect.NEVER);
    }

    byte[] read(URI uri, String authorization, byte[] searchBody, TicketRequestGate gate) {
        return read(uri, authorization, searchBody, gate, maxResponseBytes);
    }

    byte[] read(URI uri, String authorization, byte[] searchBody, TicketRequestGate gate, long responseLimit) {
        return read(uri, authorization, searchBody, gate, responseLimit, false);
    }

    /** Built-in issue type names in create metadata follow the user's locale unless explicitly forced. */
    byte[] readEnglishMetadata(URI uri, String authorization, TicketRequestGate gate) {
        return read(uri, authorization, null, gate, maxResponseBytes, true);
    }

    private byte[] read(URI uri, String authorization, byte[] searchBody, TicketRequestGate gate,
                        long responseLimit, boolean englishMetadata) {
        var request = HttpRequest.newBuilder(uri).header("Accept", "application/json")
                .header("Accept-Encoding", "identity").header("Authorization", authorization);
        if (englishMetadata) {
            request.header("Accept-Language", "en-US").header("X-Force-Accept-Language", "true");
        }
        if (searchBody == null) {
            request.GET();
        } else {
            // Jira search and bulk changelog endpoints use POST payloads for read operations.
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(searchBody));
        }

        HttpResponse<byte[]> response = exchange(request, gate, responseLimit);
        Instant retryAt = retryAt(response);
        if (retryAt != null) {
            Duration delay = Duration.between(clock.instant(), retryAt);
            if (delay.compareTo(gate.remaining()) < 0) {
                pause(delay);
                response = exchange(request, gate, responseLimit);
                retryAt = retryAt(response);
            }
        }
        if (response.statusCode() != 200) {
            throw remoteFailure(response.statusCode(), retryAt);
        }
        return response.body();
    }

    private HttpResponse<byte[]> exchange(HttpRequest.Builder request, TicketRequestGate gate, long responseLimit) {
        Duration timeout = gate.beforeRequest();
        long started = System.nanoTime();
        var pending = client.sendAsync(request.timeout(timeout).build(),
                info -> new LimitedBody(gate, responseLimit, info.statusCode()));
        try {
            // Enforce the deadline through body completion as well as receipt of headers.
            long remaining = Math.max(0, timeout.toNanos() - (System.nanoTime() - started));
            HttpResponse<byte[]> response = pending.get(remaining, TimeUnit.NANOSECONDS);
            gate.check();
            if (System.nanoTime() - started >= timeout.toNanos()) {
                throw timeout();
            }
            return response;
        } catch (TimeoutException exception) {
            pending.cancel(true);
            gate.check();
            throw timeout();
        } catch (InterruptedException exception) {
            pending.cancel(true);
            throw interrupted();
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            while (cause instanceof CompletionException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof TicketException failure) {
                throw failure;
            }
            gate.check();
            if (cause instanceof HttpTimeoutException) {
                throw timeout();
            }
            throw new TicketException(Code.PROVIDER_ERROR, JiraMessages.format("ticketing.jiraTransport.the.jira.cloud.request.could.not.be.completed"));
        }
    }

    private Instant retryAt(HttpResponse<byte[]> response) {
        if (response.statusCode() != 429 && response.statusCode() != 503) {
            return null;
        }
        String header = response.headers().firstValue("Retry-After").orElse("").strip();
        Instant now = clock.instant();
        try {
            if (header.matches("[0-9]{1,18}")) {
                return now.plusSeconds(Long.parseLong(header));
            }
            Instant at = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return at.isAfter(now) ? at : now;
        } catch (DateTimeException | ArithmeticException exception) {
            return null;
        }
    }

    private static void pause(Duration delay) {
        if (delay.isPositive()) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException exception) {
                throw interrupted();
            }
        }
    }

    private static TicketException remoteFailure(int status, Instant retryAt) {
        return switch (status) {
            case 401 -> new TicketException(Code.AUTHENTICATION_FAILED, JiraMessages.format("ticketing.jiraTransport.jira.cloud.rejected.the.configured.credentials"));
            case 403, 404 -> new TicketException(Code.AUTHORIZATION_FAILED,
                    JiraMessages.format("ticketing.jiraTransport.jira.cloud.did.not.expose.the.requested.resource"));
            case 408 -> timeout();
            case 429 -> new TicketException(Code.RATE_LIMITED, JiraMessages.format("ticketing.jiraTransport.jira.cloud.rate.limited.the.request"), retryAt);
            case 503 -> new TicketException(Code.PROVIDER_ERROR, JiraMessages.format("ticketing.jiraTransport.jira.cloud.is.temporarily.unavailable"), retryAt);
            default -> new TicketException(Code.PROVIDER_ERROR, status >= 300 && status < 400
                    ? JiraMessages.format("ticketing.jiraTransport.jira.cloud.returned.a.redirect") : JiraMessages.format("ticketing.jiraTransport.jira.cloud.rejected.the.api.request"));
        };
    }

    private static TicketException timeout() {
        return new TicketException(Code.TIMEOUT, JiraMessages.format("ticketing.jiraTransport.the.jira.cloud.request.timed.out"));
    }

    private static TicketException interrupted() {
        Thread.currentThread().interrupt();
        return new TicketException(Code.PROVIDER_ERROR, JiraMessages.format("ticketing.jiraTransport.the.jira.cloud.request.was.interrupted"));
    }

    static TicketException limitExceeded() {
        return new TicketException(Code.LIMIT_EXCEEDED, JiraMessages.format("ticketing.jiraTransport.the.jira.cloud.read.exceeded.its.resource.budget"));
    }

    @Override
    public void close() {
        client.close();
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final TicketRequestGate gate;
        private final long limit;
        private final int status;
        private final ByteArrayOutputStream bytes;
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long count;

        private LimitedBody(TicketRequestGate gate, long limit, int status) {
            this.gate = gate;
            this.limit = limit;
            this.status = status;
            bytes = status == 200 ? new ByteArrayOutputStream() : null;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (status == 401 || status == 403 || status == 404) {
                // Preserve confirmed access failures even if the error body would exceed a limit.
                result.completeExceptionally(remoteFailure(status, null));
                subscription.cancel();
            } else {
                subscription.request(1);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            try {
                for (ByteBuffer buffer : buffers) {
                    int size = buffer.remaining();
                    gate.receivedBytes(size);
                    count += size;
                    if (count > limit) {
                        throw limitExceeded();
                    }
                    if (bytes != null) {
                        var chunk = new byte[size];
                        buffer.get(chunk);
                        bytes.writeBytes(chunk);
                    }
                }
                subscription.request(1);
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
                subscription.cancel();
            }
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes == null ? new byte[0] : bytes.toByteArray());
        }
    }
}
