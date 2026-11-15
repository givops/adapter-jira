package io.givops.ticketing.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

import io.givops.release.commands.ReleaseCommandModels.Action;
import io.givops.release.commands.ReleaseCommandModels.Configuration;
import io.givops.release.commands.ReleaseCommandModels.Kind;
import io.givops.release.commands.ReleaseCommandModels.Request;
import io.givops.release.commands.ReleaseCommandModels.VersionShape;
import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.port.TicketRequestGate;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JiraReleaseWriterTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Gate GATE = new Gate();
    private static final String VERSION = "{\"id\":\"55\",\"projectId\":100,\"name\":\"1.2\"}";

    @Test void createsExplicitVersionAndReportFieldsWithTextOnlyAdfAndMappedAssignee() {
        var f = new Fixture(new Reply(201, VERSION), new Reply(201, "{\"id\":\"900\",\"key\":\"QA-9\"}"));
        var writer = f.writer();
        assertThat(writer.createVersion(f.session, "100", "1.2", LocalDate.parse("2026-12-01"), GATE)).isEqualTo("55");
        var request = request("<script>text</script>\nSecond line");
        var result = writer.createReport(f.session, "200", config(VersionShape.STRING), request, "55", "mapped-account", GATE);
        assertThat(result.issueKey()).isEqualTo("QA-9");
        assertThat(result.browseUrl()).isEqualTo("https://fixture.atlassian.net/browse/QA-9");
        assertThat(f.body(0).path("projectId").intValue()).isEqualTo(100);
        assertThat(f.body(0).path("releaseDate").stringValue()).isEqualTo("2026-12-01");
        var fields = f.body(1).path("fields");
        assertThat(fields.path("project").path("id").stringValue()).isEqualTo("200");
        assertThat(fields.path("issuetype").path("id").stringValue()).isEqualTo("7");
        assertThat(fields.path("assignee").path("accountId").stringValue()).isEqualTo("mapped-account");
        assertThat(fields.path("customfield_1").stringValue()).isEqualTo("PRODUCT");
        assertThat(fields.path("customfield_2").stringValue()).isEqualTo("1.2");
        assertThat(fields.path("customfield_3").stringValue()).isEqualTo("2026-12-01");
        assertThat(fields.path("duedate").stringValue()).isEqualTo("2026-12-02");
        assertThat(fields.path("description").path("content").get(0).path("content").get(0).path("text").stringValue()).isEqualTo("<script>text</script>");
        assertThat(fields.has("reporter")).isFalse();
    }

    @Test void versionPickerUsesOnlyExplicitVersionIdsAndHotfixType() {
        var f = new Fixture(new Reply(201, "{\"key\":\"QA-10\"}"));
        var r = request("");
        var hotfix = new Request(r.idempotencyKey(), r.context(), r.definitionId(), r.ticketProjectId(), r.versionName(), Kind.HOTFIX,
                r.action(), r.summary(), r.plannedDate(), r.desiredDeliveryDate(), r.description());
        f.writer().createReport(f.session, "200", config(VersionShape.VERSION_IDS), hotfix, "55", "mapped", GATE);
        var fields = f.body(0).path("fields");
        assertThat(fields.path("issuetype").path("id").stringValue()).isEqualTo("8");
        assertThat(fields.path("customfield_2").get(0).path("id").stringValue()).isEqualTo("55");
    }

    @Test void confirmedVersionConflictRecoversByReadingRatherThanReplayingPost() {
        var f = new Fixture(new Reply(400, "private validation details"), new Reply(200,
                "{\"startAt\":0,\"total\":1,\"isLast\":true,\"values\":[" + VERSION + "]}"));
        assertThat(f.writer().createVersion(f.session, "100", "1.2", LocalDate.now(), GATE)).isEqualTo("55");
        assertThat(f.requests).extracting(HttpRequest::method).containsExactly("POST", "GET");
    }

    @Test void write503And429AreNeverRetriedAndProviderBodiesAreNotExposed() {
        for (int status : List.of(503, 429, 302, 403)) {
            var f = new Fixture(new Reply(status, "sensitive-provider-body"));
            assertThatThrownBy(() -> f.writer().createVersion(f.session, "100", "1.2", LocalDate.now(), GATE))
                    .isInstanceOfSatisfying(JiraReleaseWriter.WriteFailure.class, e -> {
                        assertThat(e.uncertain()).isEqualTo(status == 503);
                        assertThat(e.getMessage()).doesNotContain("sensitive");
                    });
            assertThat(f.requests).hasSize(1);
        }
    }

    @Test void malformedOrOversizedAcceptedResponseIsUnknown() {
        for (String body : List.of("{bad", "x".repeat(2048))) {
            var f = new Fixture(new Reply(201, body));
            assertThatThrownBy(() -> f.writer().createVersion(f.session, "100", "1.2", LocalDate.now(), GATE))
                    .isInstanceOfSatisfying(JiraReleaseWriter.WriteFailure.class, e -> assertThat(e.uncertain()).isTrue());
            assertThat(f.requests).hasSize(1);
        }
    }

    @Test void uncertainConnectionFailureAndBodyTimeoutAreNeverReplayed() {
        var f = new Fixture();
        doReturn(new CompletableFuture<byte[]>()).when(f.client).sendAsync(any(), any());
        assertThatThrownBy(() -> f.writer().createVersion(f.session, "100", "1.2", LocalDate.now(), new Gate() {
            @Override public Duration beforeRequest() { return Duration.ofMillis(5); }
        })).isInstanceOfSatisfying(JiraReleaseWriter.WriteFailure.class, e -> assertThat(e.uncertain()).isTrue());
    }

    @Test void readsEveryVersionPageAndDoesNotTreatAmbiguousNamesAsMissing() {
        var f = new Fixture(new Reply(200, "{\"startAt\":0,\"total\":2,\"isLast\":false,\"values\":[" + VERSION + "]}"),
                new Reply(200, "{\"startAt\":1,\"total\":2,\"isLast\":true,\"values\":[" + VERSION.replace("55", "56") + "]}"));
        assertThatThrownBy(() -> f.writer().version(f.session, "100", "1.2", GATE)).isInstanceOf(JiraReleaseWriter.WriteFailure.class);
        assertThat(f.requests).hasSize(2);
    }

    @Test void duplicatesRequireExactDestinationTypeTargetAndVersionAndReturnObservedSummary() {
        var f = new Fixture(new Reply(200, """
                {"isLast":true,"issues":[{"key":"QA-1","fields":{"project":{"id":"200"},"issuetype":{"id":"7"},
                "summary":"Acquired summary","customfield_1":"PRODUCT","customfield_2":"1.2"}}]}
                """));
        var duplicate = f.writer().duplicate(f.session, "200", config(VersionShape.STRING), Kind.CAMPAIGN, "1.2", "55", GATE);
        assertThat(duplicate.summary()).isEqualTo("Acquired summary");
        assertThat(f.body(0).path("jql").stringValue()).isEqualTo("project = 200 AND issuetype = 7 ORDER BY id ASC");
    }

    private static Request request(String description) {
        return new Request(UUID.randomUUID(), "a".repeat(64), UUID.randomUUID(), UUID.randomUUID(), "1.2", Kind.CAMPAIGN,
                Action.CREATE_REPORT, "Test 1.2", LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-02"), description);
    }
    private static Configuration config(VersionShape shape) {
        return new Configuration(1, true, UUID.randomUUID(), UUID.randomUUID(), "7", "8", "customfield_1", "PRODUCT", "customfield_2",
                shape, "customfield_3", "duedate");
    }
    private record Reply(int status, String body) { }
    private static final class Fixture {
        final HttpClient client = mock(HttpClient.class);
        final List<HttpRequest> requests = new ArrayList<>();
        final JiraReleaseWriter.Session session;
        Fixture(Reply... replies) {
            var remaining = new ArrayDeque<>(List.of(replies));
            var source = new InstanceSource(UUID.randomUUID(), "fixture", TicketProvider.JIRA_CLOUD, URI.create("https://fixture.atlassian.net"), 0);
            var settings = new JiraProperties(Map.of("fixture",
                    new JiraProperties.Instance(source.baseUrl().toString(), "fixture@example.test", "synthetic-token", "one", null)));
            session = new JiraReleaseWriter.Session(JiraTestCredentials.access(settings).capture(source));
            when(client.<byte[]>sendAsync(any(), any())).thenAnswer(invocation -> {
                requests.add(invocation.getArgument(0));
                HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                var reply = remaining.remove();
                var info = mock(HttpResponse.ResponseInfo.class);
                when(info.statusCode()).thenReturn(reply.status());
                var headers = HttpHeaders.of(Map.of(), (a,b) -> true);
                when(info.headers()).thenReturn(headers);
                var subscriber = handler.apply(info);
                subscriber.onSubscribe(mock(Flow.Subscription.class));
                if (!subscriber.getBody().toCompletableFuture().isDone()) {
                    subscriber.onNext(List.of(ByteBuffer.wrap(reply.body().getBytes(StandardCharsets.UTF_8))));
                    subscriber.onComplete();
                }
                return subscriber.getBody().thenApply(bytes -> {
                    @SuppressWarnings("unchecked") HttpResponse<byte[]> response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(reply.status()); when(response.body()).thenReturn(bytes); when(response.headers()).thenReturn(headers);
                    return response;
                }).toCompletableFuture();
            });
        }
        JiraReleaseWriter writer() {
            var limits = new TicketProperties(true, Duration.ofMinutes(2), Duration.ofSeconds(10), 100, 20, 1024, 16384,
                    500, 500, 1000, 5000, 8388608, 20, 500, 10000, Duration.ofMinutes(15), 50, 500);
            return new JiraReleaseWriter(mock(JiraAccess.class), new JiraTransport(client, 1024, Clock.systemUTC()),
                    new JiraWriteTransport(client, 1024), JSON, limits);
        }
        JsonNode body(int index) {
            var bytes = new ByteArrayOutputStream();
            requests.get(index).bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                @Override public void onNext(ByteBuffer b) { byte[] chunk = new byte[b.remaining()]; b.get(chunk); bytes.writeBytes(chunk); }
                @Override public void onError(Throwable e) { throw new AssertionError(e); }
                @Override public void onComplete() { }
            });
            return JSON.readTree(bytes.toByteArray());
        }
    }
    private static class Gate implements TicketRequestGate {
        @Override public Duration beforeRequest() { return Duration.ofSeconds(2); }
        @Override public Duration remaining() { return Duration.ofSeconds(10); }
        @Override public void receivedBytes(long count) { }
        @Override public void acceptedObjects(int count) { }
        @Override public void check() { }
    }
}
