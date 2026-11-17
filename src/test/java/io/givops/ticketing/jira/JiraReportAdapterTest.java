package io.givops.ticketing.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.givops.testreports.config.TestReportProperties;
import io.givops.testreports.model.TestReportProviderModels.Extractor;
import io.givops.testreports.model.TestReportProviderModels.FieldMapping;
import io.givops.testreports.model.TestReportProviderModels.FieldRef;
import io.givops.testreports.model.TestReportProviderModels.FieldState;
import io.givops.testreports.model.TestReportProviderModels.HistoryCoverage;
import io.givops.testreports.model.TestReportProviderModels.OrdinaryProject;
import io.givops.testreports.model.TestReportProviderModels.ReportInput;
import io.givops.testreports.model.TestReportProviderModels.SourceProject;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JiraReportAdapterTest {
    private static final Instant NOW = Instant.parse("2026-11-11T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final URI SITE = URI.create("https://fixture.atlassian.net");
    private static final UUID PROJECT = UUID.randomUUID();
    private static final InstanceSource SOURCE = new InstanceSource(UUID.randomUUID(), "fixture", TicketProvider.JIRA_CLOUD, SITE, 0);
    private static final FieldMapping CUSTOM = new FieldMapping(new FieldRef("customfield_10001", Extractor.STRING),
            new FieldRef("customfield_10002", Extractor.STRING), new FieldRef("customfield_10003", Extractor.OPTION_ID));
    private static final FieldMapping STANDARD = new FieldMapping(new FieldRef("project", Extractor.PROJECT_ID),
            new FieldRef("fixVersions", Extractor.VERSION_IDS), null);

    @Test void scopedOrdinaryDatasetRetainsModuleAttributionAndCanonicalNativeVersions() {
        var base = input(CUSTOM); var module = base.ordinaryProjects().getFirst().projectId();
        var scope = new io.givops.ticketing.model.TicketScopeModels.Scope(module,
                new io.givops.ticketing.model.TicketScopeModels.Field("customfield_10003", "Repository",
                io.givops.ticketing.model.TicketScopeModels.ValueKind.OPTION,
                io.givops.ticketing.model.TicketScopeModels.Cardinality.ONE,
                io.givops.ticketing.model.TicketScopeModels.Match.EQUALS,
                io.givops.adapters.CapabilityInspection.State.AVAILABLE, null), "55", "Back ", 1, 2);
        var scoped = new ReportInput(SOURCE, base.sourceProjects(), base.issueTypeIds(), base.mapping(), base.statuses(),
                List.of(new OrdinaryProject(module, PROJECT, "100", "TQ", scope)));
        var fixture = new Fixture(new Reply("{\"startAt\":0,\"maxResults\":100,\"total\":2,\"isLast\":false,\"values\":[" + version("502", "Front 1.0") + "]}"),
                new Reply("{\"startAt\":1,\"maxResults\":100,\"total\":2,\"isLast\":true,\"values\":[" + version("501", "Back 1.0") + "]}"),
                new Reply(page(issue("201", null, null, "{\"id\":\"55\",\"value\":\"Renamed\"}") + "," + issue("202", null, null, "{\"id\":\"56\"}"), null)),
                new Reply(history("201", "", null)));
        var adapter = fixture.adapter(limits(100, 5000, 2000));
        var result = adapter.ordinaryVersionsScoped(scoped, scope, adapter.captureAccess(SOURCE), new Gate());
        assertThat(result.scope()).isEqualTo(scope); assertThat(result.projectId()).isEqualTo(module);
        assertThat(result.dataset().versions()).singleElement().satisfies(version -> {
            assertThat(version.name()).isEqualTo("Back 1.0"); assertThat(version.canonicalName()).isEqualTo("1.0");
        });
        assertThat(result.dataset().issues()).singleElement().satisfies(issue -> assertThat(issue.versionIds()).containsExactly("501"));
        assertThat(fixture.requests.get(1).uri().getQuery()).contains("startAt=1");
        assertThat(fixture.body(2).get("jql").stringValue()).contains("cf[10003] = 55");
        assertThatThrownBy(() -> adapter.ordinaryVersions(scoped, adapter.captureAccess(SOURCE), new Gate())).isInstanceOf(TicketException.class);
    }
    @Test
    void drainsSearchAndBulkHistoryPagesWithoutDependingOnEmbeddedHistory() {
        var fixture = new Fixture(new Reply(page(issue("201", "\"product-a\"", "\"1.2\"", "{\"id\":\"55\",\"value\":\"Core\"}"), "search-token\"\\")),
                new Reply(page(issue("202", "\"product-b\"", "\"1.3\"", "null"), null)),
                new Reply(history("201", event("301", "10", "20"), "history-token\"\\")),
                new Reply(history("202", event("302", "20", "30"), null)));
        var adapter = fixture.adapter(limits(100, 5000, 2000));
        var result = adapter.reports(input(CUSTOM), adapter.captureAccess(SOURCE), new Gate());
        assertThat(result.reports()).hasSize(2);
        var report = result.reports().getFirst();
        assertThat(report.sourceTicketProjectId()).isEqualTo(PROJECT);
        assertThat(report.target().value()).isEqualTo("product-a");
        assertThat(report.versions().getFirst().name()).isEqualTo("1.2");
        assertThat(report.versions().getFirst().externalVersionId()).isNull();
        assertThat(report.family().value()).isEqualTo("55");
        assertThat(report.assignee()).isNull();
        assertThat(report.history().coverage()).isEqualTo(HistoryCoverage.COMPLETE);
        assertThat(report.history().events().getFirst().toStatusId()).isEqualTo("20");
        assertThat(fixture.body(0).get("jql").stringValue()).isEqualTo("project IN (100) AND issuetype IN (7) ORDER BY id ASC");
        assertThat(fixture.body(1).get("nextPageToken").stringValue()).isEqualTo("search-token\"\\");
        assertThat(fixture.body(2).get("issueIdsOrKeys")).hasSize(2);
        assertThat(fixture.body(3).get("nextPageToken").stringValue()).isEqualTo("history-token\"\\");
        assertThat(fixture.requests).allSatisfy(request -> {
            assertThat(request.uri().getHost()).isEqualTo("fixture.atlassian.net");
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.uri().getPath()).isIn("/rest/api/3/search/jql", "/rest/api/3/changelog/bulkfetch");
        });
    }

    @Test
    void explicitMissingAndUnavailableMappingsAndHistoryAreNeverSuccessfulEmptyEvidence() {
        var fixture = new Fixture(new Reply(page(issue("201", "null", null, "[1,2]"), null)),
                new Reply("{\"issueChangeLogs\":[]}"));
        var adapter = fixture.adapter(limits(100, 5000, 2000));
        var report = adapter.reports(input(CUSTOM), adapter.captureAccess(SOURCE), new Gate()).reports().getFirst();
        assertThat(report.targetState()).isEqualTo(FieldState.MISSING);
        assertThat(report.versionsState()).isEqualTo(FieldState.UNAVAILABLE);
        assertThat(report.familyState()).isEqualTo(FieldState.AMBIGUOUS);
        assertThat(report.history().coverage()).isEqualTo(HistoryCoverage.UNAVAILABLE);
        assertThat(report.history().events()).isEmpty();
        assertThat(report.diagnostics()).extracting(value -> value.code()).contains("FIELD_UNAVAILABLE", "FIELD_AMBIGUOUS");
    }

    @Test
    void probeStopsAtTwentyAndDoesNotFetchHistory() {
        String issues = java.util.stream.IntStream.rangeClosed(201, 220)
                .mapToObj(id -> issue(Integer.toString(id), "\"product\"", "\"1.0\"", "{\"id\":\"55\",\"value\":\"Core\"}"))
                .collect(java.util.stream.Collectors.joining(","));
        var fixture = new Fixture(new Reply(page(issues, "more-issues")));
        var adapter = fixture.adapter(limits(100, 5000, 2000));
        var probe = adapter.probe(input(CUSTOM), adapter.captureAccess(SOURCE), new Gate());
        assertThat(probe.valid()).isTrue();
        assertThat(probe.sampledCount()).isEqualTo(20);
        assertThat(probe.samples()).allSatisfy(row -> assertThat(row.history().coverage()).isEqualTo(HistoryCoverage.UNAVAILABLE));
        assertThat(fixture.requests).hasSize(1);
        assertThat(fixture.body(0).get("maxResults").intValue()).isEqualTo(20);
    }

    @Test
    void emptyTypeSelectionReadsAllTypesAndExplicitMissingMappingValuesRemainValid() {
        var fixture = new Fixture(new Reply(page(issue("201", "null", "\"\"", "null"), null)));
        var adapter = fixture.adapter(limits(100, 5000, 2000));
        var original = input(CUSTOM);
        var allTypes = new ReportInput(original.source(), original.sourceProjects(), Set.of(), original.mapping(),
                original.statuses(), original.ordinaryProjects());
        var probe = adapter.probe(allTypes, adapter.captureAccess(SOURCE), new Gate());
        assertThat(probe.valid()).isTrue();
        assertThat(probe.samples().getFirst().targetState()).isEqualTo(FieldState.MISSING);
        assertThat(probe.diagnostics()).extracting(diagnostic -> diagnostic.code()).contains("FIELD_MISSING");
        assertThat(fixture.body(0).get("jql").stringValue()).isEqualTo("project IN (100) ORDER BY id ASC");
        var full = new Fixture(new Reply(page(issue("201", null, null, null), null)),
                new Reply(history("201", "", null)));
        var fullAdapter = full.adapter(limits(100, 5000, 2000));
        var standard = input(STANDARD);
        assertThat(fullAdapter.reports(new ReportInput(SOURCE, standard.sourceProjects(), Set.of(), STANDARD,
                List.of(), List.of()), fullAdapter.captureAccess(SOURCE), new Gate()).reports()).hasSize(1);
    }

    @Test
    void probeRejectsOmittedAndWrongShapeMappings() {
        for (String value : new String[]{null, "{}", "[1,2]"}) {
            var fixture = new Fixture(new Reply(page(issue("201", value, "\"1.0\"", "null"), null)));
            var adapter = fixture.adapter(limits(100, 5000, 2000));
            assertThat(adapter.probe(input(CUSTOM), adapter.captureAccess(SOURCE), new Gate()).valid()).isFalse();
        }
    }

    @Test
    void metadataUsesAccessibleFieldsAndProjectStatusesWithoutJiraAdminApis() {
        var fixture = new Fixture(new Reply("""
                [{"id":"project","name":"Project","schema":{"type":"project"}},
                 {"id":"fixVersions","name":"Fix versions","schema":{"type":"array","items":"version"}},
                 {"id":"customfield_10001","name":"Target","schema":{"type":"string"}}]
                """), new Reply("""
                [{"id":"7","name":"Test report","statuses":[{"id":"10","name":"Requested"}]}]
                """));
        var adapter = fixture.adapter(limits(100, 5000, 2000));
        var original = input(STANDARD);
        var metadata = adapter.metadata(new ReportInput(SOURCE, original.sourceProjects(), Set.of(), STANDARD,
                List.of(), List.of()), adapter.captureAccess(SOURCE), new Gate());
        assertThat(metadata.fields().getFirst().supportedExtractors()).containsExactly(Extractor.PROJECT_ID, Extractor.PROJECT_KEY);
        assertThat(metadata.fields().get(1).supportedExtractors()).containsExactly(Extractor.VERSION_IDS);
        assertThat(metadata.projects().getFirst().statuses().getFirst().id()).isEqualTo("10");
        assertThat(fixture.requests).extracting(request -> request.uri().getPath())
                .containsExactly("/rest/api/3/field", "/rest/api/3/project/100/statuses");
    }

    @Test
    void ordinaryDatasetDrainsAllVersionsAndIssuePagesIndependentlyFromReportSearch() {
        var fixture = new Fixture(new Reply("""
                {"startAt":0,"maxResults":1,"total":2,"isLast":false,"values":[%s]}
                """.formatted(version("501", "1.0"))), new Reply("""
                {"startAt":1,"maxResults":1,"total":2,"isLast":true,"values":[%s]}
                """.formatted(version("502", "2.0"))),
                new Reply(page(issue("201", null, null, null), "more")),
                new Reply(page(issue("202", null, null, null), null)),
                new Reply("{\"issueChangeLogs\":[{\"issueId\":\"201\",\"changeHistories\":[]},{\"issueId\":\"202\",\"changeHistories\":[]}]}"));
        var adapter = fixture.adapter(limits(100, 5000, 2000));
        var result = adapter.ordinaryVersions(input(STANDARD), adapter.captureAccess(SOURCE), new Gate());
        assertThat(result.versions()).hasSize(2);
        assertThat(result.issues()).hasSize(2).allSatisfy(row -> {
            assertThat(row.versionIds()).containsExactly("501", "502");
            assertThat(row.history().coverage()).isEqualTo(HistoryCoverage.COMPLETE);
        });
        assertThat(fixture.body(2).get("jql").stringValue()).isEqualTo("project = 100 AND fixVersion IS NOT EMPTY ORDER BY id ASC");
    }

    @Test
    void ordinaryIssueMustMatchTheSpecificProjectSearchRatherThanTheConfiguredUnion() {
        String versions = "{\"startAt\":0,\"maxResults\":100,\"total\":1,\"isLast\":true,\"values\":[" + version("501", "1.0") + "]}";
        var fixture = new Fixture(new Reply(versions),
                new Reply(page(issue("201", null, null, null).replace("\"id\":\"100\"", "\"id\":\"101\""), null)));
        var adapter = fixture.adapter(limits(100, 5000, 2000));
        var original = input(STANDARD);
        var scoped = new ReportInput(SOURCE, original.sourceProjects(), Set.of(), STANDARD, List.of(), List.of(
                new OrdinaryProject(UUID.randomUUID(), PROJECT, "100", "TQ"),
                new OrdinaryProject(UUID.randomUUID(), UUID.randomUUID(), "101", "OTHER")));
        assertCode(() -> adapter.ordinaryVersions(scoped, adapter.captureAccess(SOURCE), new Gate()), Code.INVALID_RESPONSE);
        assertThat(fixture.requests).hasSize(2);
    }

    @Test
    void redactedHistoryIsIncompleteAndAuthorizationErrorsArePropagated() {
        var fixture = new Fixture(new Reply(page(issue("201", null, null, null), null)),
                new Reply(history("201", event("301", "10", null), null)));
        var adapter = fixture.adapter(limits(100, 5000, 2000));
        var report = adapter.reports(input(STANDARD), adapter.captureAccess(SOURCE), new Gate()).reports().getFirst();
        assertThat(report.history().coverage()).isEqualTo(HistoryCoverage.INCOMPLETE);
        assertThat(report.history().events()).hasSize(1);
        var denied = new Fixture(new Reply(page(issue("201", null, null, null), null)), new Reply(403, "{}"));
        var deniedAdapter = denied.adapter(limits(100, 5000, 2000));
        assertThatThrownBy(() -> deniedAdapter.reports(input(STANDARD), deniedAdapter.captureAccess(SOURCE), new Gate()))
                .isInstanceOfSatisfying(TicketException.class, error -> assertThat(error.code()).isEqualTo(Code.AUTHORIZATION_FAILED));
    }

    @Test
    void rejectsRepeatedHistoryTokenAndEnforcesPageAndEventBudgets() {
        String row = page(issue("201", null, null, null), null);
        var loop = new Fixture(new Reply(row), new Reply(history("201", event("301", "10", "20"), "same")),
                new Reply(history("201", event("302", "20", "30"), "same")));
        var adapter = loop.adapter(limits(100, 5000, 2000));
        assertCode(() -> adapter.reports(input(STANDARD), adapter.captureAccess(SOURCE), new Gate()), Code.INVALID_RESPONSE);
        var pages = new Fixture(new Reply(page(issue("201", null, null, null), "more")));
        var bounded = pages.adapter(limits(1, 5000, 2000));
        assertCode(() -> bounded.reports(input(STANDARD), bounded.captureAccess(SOURCE), new Gate()), Code.LIMIT_EXCEEDED);
        var events = new Fixture(new Reply(row), new Reply(history("201", event("301", "10", "20") + "," + event("302", "20", "30"), null)));
        var limited = events.adapter(limits(100, 5000, 1));
        assertCode(() -> limited.reports(input(STANDARD), limited.captureAccess(SOURCE), new Gate()), Code.LIMIT_EXCEEDED);
    }

    @Test
    void malformedPaginationConflictingHistoryAndIssueBudgetsFailTheWholeDataset() {
        String issue = issue("201", null, null, null);
        for (String malformed : new String[]{"{\"isLast\":false,\"issues\":[" + issue + "]}",
                "{\"isLast\":true,\"nextPageToken\":\"unexpected\",\"issues\":[" + issue + "]}"}) {
            var fixture = new Fixture(new Reply(malformed));
            var adapter = fixture.adapter(limits(100, 5000, 2000));
            assertCode(() -> adapter.reports(input(STANDARD), adapter.captureAccess(SOURCE), new Gate()), Code.INVALID_RESPONSE);
        }
        var duplicates = new Fixture(new Reply(page(issue, null)), new Reply(history("201",
                event("301", "10", "20") + "," + event("301", "10", "30"), null)));
        var adapter = duplicates.adapter(limits(100, 5000, 2000));
        assertCode(() -> adapter.reports(input(STANDARD), adapter.captureAccess(SOURCE), new Gate()), Code.INVALID_RESPONSE);
        var issueBudget = new Fixture(new Reply(page(issue + "," + issue("202", null, null, null), null)));
        var bounded = issueBudget.adapter(limits(100, 1, 2000));
        assertCode(() -> bounded.reports(input(STANDARD), bounded.captureAccess(SOURCE), new Gate()), Code.LIMIT_EXCEEDED);
    }

    @Test
    void missingHistoryPayloadIsUnavailableAndReportResponseLimitIsEnforced() {
        var fixture = new Fixture(new Reply(page(issue("201", null, null, null), null)),
                new Reply("{\"issueChangeLogs\":[{\"issueId\":\"201\"}]}"));
        var adapter = fixture.adapter(limits(100, 5000, 2000));
        assertThat(adapter.reports(input(STANDARD), adapter.captureAccess(SOURCE), new Gate()).reports().getFirst()
                .history().coverage()).isEqualTo(HistoryCoverage.UNAVAILABLE);
        var small = new Fixture(new Reply(page(issue("201", null, null, null), null)));
        var defaults = limits(100, 5000, 2000);
        var limit = new TestReportProperties(defaults.refreshTimeout(), defaults.requestTimeout(), defaults.maxHttpAttempts(),
                defaults.maxPages(), 32, defaults.maxTotalResponseBytes(), defaults.maxReportIssues(), defaults.maxOrdinaryIssues(),
                defaults.maxEventsPerIssue(), defaults.maxEvents(), defaults.freshness(), defaults.retainedAttempts());
        var bounded = small.adapter(limit);
        assertCode(() -> bounded.reports(input(STANDARD), bounded.captureAccess(SOURCE), new Gate()), Code.LIMIT_EXCEEDED);
    }

    @Test
    void rejectsOutOfScopeIssuesAndConflictingDuplicatesBeforeHistoryRequests() {
        String row = issue("201", null, null, null);
        var scope = new Fixture(new Reply(page(row.replace("\"id\":\"100\"", "\"id\":\"999\""), null)));
        var adapter = scope.adapter(limits(100, 5000, 2000));
        assertCode(() -> adapter.reports(input(STANDARD), adapter.captureAccess(SOURCE), new Gate()), Code.INVALID_RESPONSE);
        var duplicate = new Fixture(new Reply(page(row + "," + row.replace("Fixture report", "Conflicting report"), null)));
        var other = duplicate.adapter(limits(100, 5000, 2000));
        assertCode(() -> other.reports(input(STANDARD), other.captureAccess(SOURCE), new Gate()), Code.INVALID_RESPONSE);
    }

    private static void assertCode(Runnable work, Code code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(TicketException.class, error -> assertThat(error.code()).isEqualTo(code));
    }

    private static ReportInput input(FieldMapping mapping) {
        return new ReportInput(SOURCE, List.of(new SourceProject(PROJECT, "100", "TQ")), Set.of("7"), mapping,
                List.of(), List.of(new OrdinaryProject(UUID.randomUUID(), PROJECT, "100", "TQ")));
    }

    private static TestReportProperties limits(int pages, int issues, int events) {
        return new TestReportProperties(Duration.ofSeconds(300), Duration.ofSeconds(10), 500, pages, 2097152, 67108864,
                issues, 10000, events, 100000, Duration.ofMinutes(15), 50);
    }

    private static String issue(String id, String target, String version, String family) {
        return """
                {"id":"%s","key":"TQ-%s","fields":{"project":{"id":"100","key":"TQ","name":"Source"},
                "summary":"Fixture report","issuetype":{"id":"7","name":"Test report"},
                "status":{"id":"20","name":"Testing","statusCategory":{"key":"indeterminate"}},"assignee":null,
                "created":"2026-10-01T12:00:00.000+0000","updated":"2026-11-01T12:00:00Z",
                "fixVersions":[{"id":"501","name":"1.0"},{"id":"502","name":"2.0"}]%s%s%s}}
                """.formatted(id, id, target == null ? "" : ",\"customfield_10001\":" + target,
                version == null ? "" : ",\"customfield_10002\":" + version,
                family == null ? "" : ",\"customfield_10003\":" + family);
    }

    private static String version(String id, String name) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"released\":false,\"archived\":false}";
    }

    private static String page(String issues, String next) {
        return "{\"isLast\":" + (next == null) + ",\"issues\":[" + issues + "]"
                + (next == null ? "" : ",\"nextPageToken\":" + JSON.writeValueAsString(next)) + "}";
    }

    private static String history(String issue, String events, String next) {
        return "{\"issueChangeLogs\":[{\"issueId\":\"" + issue + "\",\"changeHistories\":[" + events + "]}]"
                + (next == null ? "" : ",\"nextPageToken\":" + JSON.writeValueAsString(next)) + "}";
    }

    private static String event(String id, String from, String to) {
        return "{\"id\":\"" + id + "\",\"created\":\"2026-11-01T12:00:00Z\",\"items\":[{\"fieldId\":\"status\",\"from\":"
                + JSON.writeValueAsString(from) + ",\"to\":" + JSON.writeValueAsString(to) + ",\"fromString\":\"Before\",\"toString\":\"After\"}]}";
    }

    private record Reply(int status, String body) { Reply(String body) { this(200, body); } }

    private static final class Fixture {
        final List<HttpRequest> requests = new ArrayList<>();
        final HttpClient client = mock(HttpClient.class);

        Fixture(Reply... replies) {
            var remaining = new ArrayDeque<>(List.of(replies));
            when(client.<byte[]>sendAsync(any(), any())).thenAnswer(invocation -> {
                requests.add(invocation.getArgument(0));
                HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                Reply reply = remaining.remove();
                var headers = HttpHeaders.of(Map.of(), (name, value) -> true);
                var info = mock(HttpResponse.ResponseInfo.class);
                when(info.headers()).thenReturn(headers);
                when(info.statusCode()).thenReturn(reply.status());
                var subscriber = handler.apply(info);
                subscriber.onSubscribe(mock(Flow.Subscription.class));
                if (!subscriber.getBody().toCompletableFuture().isDone()) {
                    subscriber.onNext(List.of(ByteBuffer.wrap(reply.body().getBytes(StandardCharsets.UTF_8))));
                    subscriber.onComplete();
                }
                return subscriber.getBody().thenApply(bytes -> {
                    @SuppressWarnings("unchecked") HttpResponse<byte[]> response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(reply.status());
                    when(response.headers()).thenReturn(headers);
                    when(response.body()).thenReturn(bytes);
                    return response;
                }).toCompletableFuture();
            });
        }

        JiraReportAdapter adapter(TestReportProperties limits) {
            var runtime = new JiraProperties(Map.of("fixture",
                    new JiraProperties.Instance(SITE.toString(), "reader@example.test", "synthetic-token", "revision-one", null)));
            return new JiraReportAdapter(JiraTestCredentials.access(runtime), new JiraTransport(client, limits.maxResponseBytes(), CLOCK), limits, JSON, CLOCK);
        }

        JsonNode body(int index) {
            var bytes = new ByteArrayOutputStream();
            var done = new CompletableFuture<byte[]>();
            requests.get(index).bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
                @Override public void onNext(ByteBuffer value) {
                    byte[] chunk = new byte[value.remaining()]; value.get(chunk); bytes.writeBytes(chunk);
                }
                @Override public void onError(Throwable error) { done.completeExceptionally(error); }
                @Override public void onComplete() { done.complete(bytes.toByteArray()); }
            });
            return JSON.readTree(done.join());
        }
    }

    private static final class Gate implements TicketRequestGate {
        @Override public Duration beforeRequest() { return Duration.ofSeconds(10); }
        @Override public void receivedBytes(long count) { }
        @Override public void acceptedObjects(int count) { }
        @Override public void check() { }
        @Override public Duration remaining() { return Duration.ofMinutes(5); }
    }
}
