package io.givops.ticketing.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.IssueObservation;
import io.givops.ticketing.model.TicketModels.IssueObservation;
import io.givops.ticketing.model.TicketModels.IssueIdentity;
import io.givops.ticketing.model.TicketModels.ProjectQuery;
import io.givops.ticketing.model.TicketModels.ProviderInput;
import io.givops.ticketing.model.TicketModels.ReleaseMappingState;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JiraAdapterTest {
    private static final URI SITE = URI.create("https://example.atlassian.net");
    private static final String API = "/rest/api/3";
    private static final String EMAIL = "reader@example.test";
    private static final String TOKEN = "synthetic-test-token";
    private static final String CONTEXT_SECRET = "synthetic-context-secret-for-tests";
    private static final String CLOUD_ID = "c40798bc-7189-4183-b62b-09b574c25431";
    private static final String PROJECT_ID = "10101";
    private static final UUID BINDING_ID = UUID.fromString("a76eaf5d-645f-4b50-a8aa-70b46af7aa23");
    private static final InstanceSource SOURCE = new InstanceSource(
            UUID.fromString("1972b7b5-03cf-4058-a4a2-17a2a6c3897b"), "jira", TicketProvider.JIRA_CLOUD, SITE, 0);
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final TicketProperties LIMITS = limits(true, 20, 500);
    private static final String PROJECT = """
            {"id":"10101","key":"RENAMED","name":"Demo","self":"https://elsewhere.example/secret"}
            """;
    private static final String EMPTY_VERSIONS = """
            {"startAt":0,"maxResults":100,"total":0,"isLast":true,"values":[]}
            """;
    private static final String EMPTY_ISSUES = """
            {"isLast":true,"issues":[]}
            """;

    @Test void looksUpProjectIdentityUsingTheRoutingKey() {
        var fixture = new Fixture(new Reply("""
                {"id":"10038","key":"MUL","name":"Synthetic project","uuid":"different-identifier"}
                """));
        var adapter = fixture.adapter(settings(null), LIMITS);
        var gate = new Gate();
        var result = adapter.lookupProject(SOURCE, "MUL", adapter.captureAccess(SOURCE), gate);
        assertThat(result.externalProjectId()).isEqualTo("10038");
        assertThat(result.projectKey()).isEqualTo("MUL");
        assertThat(result.displayName()).isEqualTo("Synthetic project");
        assertThat(gate.objects).isEqualTo(1);
        assertThat(fixture.requests).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.uri()).isEqualTo(SOURCE.baseUrl().resolve("/rest/api/3/project/MUL"));
        });
    }

    @Test void projectLookupRejectsInvalidOrMismatchedKeys() {
        var fixture = new Fixture(new Reply(PROJECT));
        var adapter = fixture.adapter(settings(null), LIMITS);
        var access = adapter.captureAccess(SOURCE);
        assertThatExceptionOfType(TicketException.class)
                .isThrownBy(() -> adapter.lookupProject(SOURCE, "invalid/key", access, new Gate()));
        assertThat(fixture.requests).isEmpty();
        assertThatExceptionOfType(TicketException.class)
                .isThrownBy(() -> adapter.lookupProject(SOURCE, "MUL", access, new Gate()))
                .extracting(TicketException::code).isEqualTo(Code.INVALID_RESPONSE);
    }

    private static io.givops.ticketing.model.TicketScopeModels.Scope scope() {
        return new io.givops.ticketing.model.TicketScopeModels.Scope(BINDING_ID,
                new io.givops.ticketing.model.TicketScopeModels.Field("components", "Components",
                io.givops.ticketing.model.TicketScopeModels.ValueKind.COMPONENT,
                io.givops.ticketing.model.TicketScopeModels.Cardinality.MANY,
                io.givops.ticketing.model.TicketScopeModels.Match.CONTAINS,
                io.givops.adapters.CapabilityInspection.State.AVAILABLE, null), "41", "Back ", 2, 3);
    }
    private static String scopedIssue(String id, String key, String component) {
        return issue(id, key, PROJECT_ID, "20002").replace("\"summary\":", "\"components\":[{\"id\":\"" + component + "\"}],\"summary\":");
    }
    @Test void scopeFiltersKeysAssignmentsAndReleaseAndKeepsRawVersionPaging() {
        var fixture = new Fixture(new Reply(PROJECT),
                new Reply("{\"startAt\":0,\"maxResults\":100,\"total\":2,\"isLast\":false,\"values\":[" + version("20001", "Front 1.0.0") + "]}"),
                new Reply("{\"startAt\":1,\"maxResults\":100,\"total\":2,\"isLast\":true,\"values\":[" + version("20002", "Back 1.0.0") + "]}"),
                new Reply(issuePage(scopedIssue("30001", "APP-1", "41") + "," + scopedIssue("30002", "APP-2", "42"), null)),
                new Reply(issuePage(scopedIssue("30001", "APP-1", "41"), null)), new Reply(EMPTY_ISSUES));
        var adapter = fixture.adapter(settings(null), LIMITS);
        var result = adapter.readScoped(input(SOURCE, Set.of("APP-1", "APP-2"), "1.0.0"), scope(), new Gate(), adapter.captureAccess(SOURCE));
        io.givops.adapters.testkit.AdapterTestkit.verifyScopedSnapshot(scope(), result);
        var project = result.snapshot().projects().getFirst();
        assertThat(project.versions()).singleElement().satisfies(version -> {
            assertThat(version.name()).isEqualTo("Back 1.0.0"); assertThat(version.canonicalName()).isEqualTo("1.0.0");
        });
        assertThat(project.issues()).extracting(IssueObservation::issueKey).containsExactly("APP-1");
        assertThat(project.unresolvedKeys()).containsExactly("APP-2");
        assertThat(project.releaseMembership().state()).isEqualTo(ReleaseMappingState.MATCHED);
        assertThat(fixture.requests.get(2).uri().getQuery()).contains("startAt=1");
        assertThat(fixture.body(3).get("jql").stringValue()).contains("project = 10101 AND component = 41 AND (key in");
        assertThat(fixture.body(3).get("fields").toString()).contains("components");
    }
    @Test void scopedAssignmentsKeepIssuesWithoutFixVersionsAndMultipleMatchingValues() {
        String both = assignedIssue(1).replace("\"fixVersions\":[]", "\"components\":[{\"id\":\"41\"},{\"id\":\"42\"}],\"fixVersions\":[]");
        String foreign = assignedIssue(2).replace("\"fixVersions\":[]", "\"components\":[{\"id\":\"42\"}],\"fixVersions\":[]");
        var fixture = new Fixture(new Reply(PROJECT), new Reply(EMPTY_VERSIONS), new Reply(issuePage(both + "," + foreign, null)));
        var adapter = fixture.adapter(settings(null), LIMITS);
        var result = adapter.readScoped(input(SOURCE, Set.of(), null), scope(), new Gate(), adapter.captureAccess(SOURCE));
        var project = result.snapshot().projects().getFirst();
        assertThat(project.assignedIssues()).containsExactly(new IssueIdentity(PROJECT_ID, "30001"));
        assertThat(project.issues()).singleElement().satisfies(issue -> assertThat(issue.releaseVersionIds()).isEmpty());
    }
    @Test void scopedVersionSelectionReceivesCanonicalNamesAndFiltersNativeMemberships() {
        var fixture = new Fixture(new Reply(versions(version("20001", "Front 1.0.0") + "," + version("20002", "Back 1.0.0"))),
                new Reply(issuePage(scopedIssue("30001", "APP-1", "41"), null)));
        var adapter = fixture.adapter(settings(null), LIMITS);
        var result = adapter.readVersionMembershipsScoped(SOURCE, input(SOURCE, Set.of(), null).projects().getFirst(), scope(),
                adapter.captureAccess(SOURCE), new Gate(), versions -> {
                    assertThat(versions).extracting(v -> v.canonicalName()).containsExactly("1.0.0"); return Set.of("20002");
                }, 10, 100);
        assertThat(result.scope()).isEqualTo(scope());
        assertThat(result.memberships().members().get("20002")).hasSize(1);
    }
    private static String metadataPage(String rows) {
        return "{\"startAt\":0,\"maxResults\":100,\"total\":1,\"isLast\":true,\"values\":[" + rows + "]}";
    }
    private static final String SCOPE_FIELDS = """
        [{"id":"components","name":"Components","schema":{"type":"array","items":"component","system":"components"}},
         {"id":"customfield_101","name":"Single","schema":{"type":"option","custom":"com.atlassian.jira.plugin.system.customfieldtypes:select"}},
         {"id":"customfield_102","name":"Multi","schema":{"type":"array","items":"option","custom":"com.atlassian.jira.plugin.system.customfieldtypes:multiselect"}},
         {"id":"customfield_103","name":"Text","schema":{"type":"string","custom":"com.atlassian.jira.plugin.system.customfieldtypes:textfield"}}]
        """;
    @Test void malformedMetadataCannotActivateAFieldOrBecomeSuccessfulEmptyEnumeration() {
        for (String response : List.of("[{\"id\":\"components\",\"id\":\"customfield_101\"}]", SCOPE_FIELDS + " {}")) {
            var fixture = new Fixture(new Reply(response)); var adapter = fixture.adapter(settings(null), LIMITS);
            var fields = adapter.fields(SOURCE, PROJECT_ID, adapter.captureAccess(SOURCE), new Gate());
            assertThat(fields.state()).isEqualTo(io.givops.adapters.CapabilityInspection.State.UNKNOWN);
            assertThat(fields.fields()).isEmpty();
        }
    }
    @Test void metadataAdvertisesOnlyVerifiedNativeSelectProfiles() {
        var fixture = new Fixture(new Reply(SCOPE_FIELDS)); var adapter = fixture.adapter(settings(null), LIMITS);
        var result = adapter.fields(SOURCE, PROJECT_ID, adapter.captureAccess(SOURCE), new Gate());
        assertThat(result.state()).isEqualTo(io.givops.adapters.CapabilityInspection.State.AVAILABLE);
        assertThat(result.fields()).filteredOn(field -> field.state() == io.givops.adapters.CapabilityInspection.State.AVAILABLE)
                .extracting(field -> field.id()).containsExactly("components", "customfield_101", "customfield_102");
        assertThat(result.fields().getLast().state()).isEqualTo(io.givops.adapters.CapabilityInspection.State.UNSUPPORTED);
    }
    @Test void metadataValidatesOptionsWithinTheBoundProjectContextAndRetainsDisabledIds() {
        var fixture = new Fixture(new Reply(SCOPE_FIELDS),
                new Reply(metadataPage("{\"contextId\":501,\"projectId\":10101}")),
                new Reply(metadataPage("{\"id\":\"501\",\"isAnyIssueType\":true,\"isGlobalContext\":false}")),
                new Reply("{\"startAt\":0,\"maxResults\":100,\"total\":2,\"isLast\":true,\"values\":[{\"id\":\"41\",\"value\":\"Renamed Back\",\"disabled\":false},{\"id\":\"42\",\"value\":\"Back\",\"disabled\":true}]}"));
        var adapter = fixture.adapter(settings(null), LIMITS);
        var values = adapter.values(SOURCE, PROJECT_ID, "customfield_102", adapter.captureAccess(SOURCE), new Gate());
        assertThat(values.state()).isEqualTo(io.givops.adapters.CapabilityInspection.State.AVAILABLE);
        assertThat(values.values()).extracting(value -> value.id()).containsExactly("41", "42");
        assertThat(values.values().getLast().state()).isEqualTo(io.givops.adapters.CapabilityInspection.State.DISABLED);
        assertThat(fixture.requests.getLast().uri().getPath()).contains("/context/501/option");
    }
    @Test void metadataDenialAndAmbiguousContextsCannotBecomeEmptySuccessfulOptions() {
        var denied = new Fixture(new Reply(403, Map.of(), "{}")); var adapter = denied.adapter(settings(null), LIMITS);
        assertThat(adapter.values(SOURCE, PROJECT_ID, "components", adapter.captureAccess(SOURCE), new Gate()).state())
                .isEqualTo(io.givops.adapters.CapabilityInspection.State.FORBIDDEN);
        var fixture = new Fixture(new Reply(SCOPE_FIELDS), new Reply("{\"startAt\":0,\"maxResults\":100,\"total\":2,\"isLast\":true,\"values\":[{\"contextId\":\"501\",\"projectId\":\"10101\"},{\"contextId\":\"502\",\"isGlobalContext\":true}]}"));
        var ambiguous = fixture.adapter(settings(null), LIMITS);
        assertThat(ambiguous.values(SOURCE, PROJECT_ID, "customfield_101", ambiguous.captureAccess(SOURCE), new Gate()).diagnostic())
                .isEqualTo("AMBIGUOUS_PROJECT_CONTEXT");
        assertThat(fixture.requests).hasSize(2);
    }
    @Test void componentOwnersAcceptNumericIdentifiersAndRejectForeignOwners() {
        for (String owner : List.of("10101", "\"10101\"", "10202")) {
            var fixture = new Fixture(new Reply(metadataPage("{\"id\":41,\"name\":\"Back\",\"projectId\":" + owner + "}")));
            var adapter = fixture.adapter(settings(null), LIMITS);
            var values = adapter.values(SOURCE, PROJECT_ID, "components", adapter.captureAccess(SOURCE), new Gate());
            assertThat(values.state()).isEqualTo(owner.equals("10202") ? io.givops.adapters.CapabilityInspection.State.UNKNOWN
                    : io.givops.adapters.CapabilityInspection.State.AVAILABLE);
            if (!owner.equals("10202")) assertThat(values.values()).extracting(value -> value.id()).containsExactly("41");
        }
    }
    @Test void componentActivationChecksStableIdsAndSchemaRatherThanLabels() {
        var fixture = new Fixture(new Reply(SCOPE_FIELDS), new Reply(metadataPage("{\"id\":\"41\",\"name\":\"Renamed Back\",\"projectId\":\"10101\"}")),
                new Reply(SCOPE_FIELDS), new Reply(metadataPage("{\"id\":\"42\",\"name\":\"Components\",\"projectId\":\"10101\"}")));
        var adapter = fixture.adapter(settings(null), LIMITS); var access = adapter.captureAccess(SOURCE);
        assertThat(adapter.validateScope(SOURCE, PROJECT_ID, scope(), access, new Gate()).state())
                .isEqualTo(io.givops.adapters.CapabilityInspection.State.AVAILABLE);
        assertThat(adapter.validateScope(SOURCE, PROJECT_ID, scope(), access, new Gate()).reason()).isEqualTo("VALUE_NOT_FOUND");
    }
    @Test void navigatesToBoundProviderSpaceWithoutCollectingContent() {
        var fixture = new Fixture(); var adapter = fixture.adapter(settings(null), LIMITS);
        var request = new io.givops.ticketing.model.TicketBrowserModels.BrowserRequest(SOURCE, BINDING_ID, PROJECT_ID, "APP", null, NOW);
        assertThat(adapter.navigate(request, null, adapter.captureAccess(SOURCE), new Gate()).spaceUrl()).isEqualTo(SITE.resolve("/browse/APP"));
        assertThat(fixture.requests).isEmpty();
    }
    @Test
    void releaseSearchResolvesARequestedKeyOmittedByTheEarlierKeySearch() {
        var fixture = new Fixture(
                new Reply(PROJECT),
                new Reply("""
                        {"startAt":0,"maxResults":100,"total":1,"isLast":true,"values":[%s]}
                        """.formatted(version("20002", "v1.2.3"))),
                new Reply(EMPTY_ISSUES),
                new Reply(issuePage(issue("30001", "APP-1", PROJECT_ID, "20002"), null)), new Reply(EMPTY_ISSUES));

        var result = fixture.adapter(settings(null), LIMITS)
                .read(input(SOURCE, Set.of("APP-1"), "v1.2.3"), new Gate()).projects().getFirst();

        assertThat(result.unresolvedKeys()).isEmpty();
        assertThat(result.issues()).singleElement().satisfies(issue -> assertThat(issue.issueKey()).isEqualTo("APP-1"));
        assertThat(result.releaseMembership().issues()).containsExactly(new IssueIdentity(PROJECT_ID, "30001"));
    }

    @Test
    void readsBoundProjectAndVersionsAndAcquiresReleaseMembershipIndependentlyOfKeys() {
        String firstVersion = version("20001", "v1.2.2");
        String releaseVersion = version("20002", "v1.2.3");
        String issueOne = issue("30001", "APP-1", PROJECT_ID, "20002");
        String issueThree = issue("30003", "APP-3", PROJECT_ID, "20001");
        String releaseOnly = issue("30011", "RENAMED-11", PROJECT_ID, "20002");
        var fixture = new Fixture(
                new Reply(PROJECT),
                new Reply("""
                        {"startAt":0,"maxResults":1,"total":2,"isLast":false,
                         "nextPage":"https://elsewhere.example/do-not-follow","values":[%s]}
                        """.formatted(firstVersion)),
                new Reply("""
                        {"startAt":1,"maxResults":1,"total":2,"isLast":true,"values":[%s]}
                        """.formatted(releaseVersion)),
                new Reply(issuePage(issueOne, "key-page-2")),
                new Reply(issuePage(issueThree, null)),
                new Reply(issuePage(issueOne, "release-page-2")),
                new Reply(issuePage(releaseOnly, null)), new Reply(EMPTY_ISSUES));
        var gate = new Gate();

        var result = fixture.adapter(settings(null), LIMITS)
                .read(input(SOURCE, Set.of("APP-3", "APP-1", "APP-2"), "v1.2.3"), gate).projects().getFirst();

        assertThat(result.ticketProjectId()).isEqualTo(BINDING_ID);
        assertThat(result.project().projectKey()).isEqualTo("RENAMED");
        assertThat(result.project().webUrl()).isEqualTo(SITE.resolve("/browse/RENAMED"));
        assertThat(result.versions()).hasSize(2).last().satisfies(version -> {
            assertThat(version.externalVersionId()).isEqualTo("20002");
            assertThat(version.name()).isEqualTo("v1.2.3");
            assertThat(version.released()).isTrue();
            assertThat(version.archived()).isFalse();
            assertThat(version.releaseDate()).isEqualTo(LocalDate.of(2026, 10, 10));
        });
        assertThat(result.unresolvedKeys()).containsExactly("APP-2");
        assertThat(result.issues()).hasSize(3).first().satisfies(issue -> {
            assertThat(issue.identity()).isEqualTo(new IssueIdentity(PROJECT_ID, "30001"));
            assertThat(issue.webUrl()).isEqualTo(SITE.resolve("/browse/APP-1"));
            assertThat(issue.summary()).isEqualTo("Synthetic issue");
            assertThat(issue.description()).isNull();
            assertThat(issue.type()).isEqualTo("Task");
            assertThat(issue.status()).isEqualTo("In Progress");
            assertThat(issue.statusCategory()).isEqualTo("indeterminate");
            assertThat(issue.priority()).isEqualTo("Medium");
            assertThat(issue.resolution()).isNull();
            assertThat(issue.createdAt()).isEqualTo(Instant.parse("2026-10-01T12:30:00Z"));
            assertThat(issue.updatedAt()).isEqualTo(Instant.parse("2026-10-09T10:00:00Z"));
            assertThat(issue.resolvedAt()).isNull();
            assertThat(issue.releaseVersionIds()).containsExactly("20002");
            assertThat(issue.observedAt()).isEqualTo(NOW);
        });
        assertThat(result.releaseMembership().state()).isEqualTo(ReleaseMappingState.MATCHED);
        assertThat(result.releaseMembership().externalVersionId()).isEqualTo("20002");
        assertThat(result.releaseMembership().issues()).containsExactly(
                new IssueIdentity(PROJECT_ID, "30001"), new IssueIdentity(PROJECT_ID, "30011"));
        assertThat(gate.attempts).isEqualTo(8);
        assertThat(gate.objects).isEqualTo(7);
        assertThat(gate.bytes).isPositive();
        assertThat(fixture.requests).extracting(request -> request.uri().toString()).containsExactly(
                SITE + API + "/project/10101",
                SITE + API + "/project/10101/version?startAt=0&maxResults=100",
                SITE + API + "/project/10101/version?startAt=1&maxResults=100",
                SITE + API + "/search/jql", SITE + API + "/search/jql",
                SITE + API + "/search/jql", SITE + API + "/search/jql", SITE + API + "/search/jql");
        assertThat(fixture.requests).extracting(HttpRequest::method)
                .containsExactly("GET", "GET", "GET", "POST", "POST", "POST", "POST", "POST");
        String expectedAuth = "Basic " + Base64.getEncoder().encodeToString(
                (EMAIL + ":" + TOKEN).getBytes(StandardCharsets.UTF_8));
        assertThat(fixture.requests).allSatisfy(request ->
                assertThat(request.headers().firstValue("Authorization")).contains(expectedAuth));
        assertThat(fixture.body(3).get("jql").stringValue())
                .isEqualTo("project = 10101 AND key in (\"APP-1\", \"APP-2\", \"APP-3\") ORDER BY key ASC");
        assertThat(fixture.body(3).get("fields")).extracting(JsonNode::stringValue).containsExactly(
                "project", "summary", "issuetype", "status", "priority", "resolution",
                "created", "updated", "resolutiondate", "fixVersions", "assignee");
        assertThat(fixture.body(3).has("expand")).isFalse();
        assertThat(fixture.body(4).get("nextPageToken").stringValue()).isEqualTo("key-page-2");
        assertThat(fixture.body(5).get("jql").stringValue())
                .isEqualTo("project = 10101 AND fixVersion = 20002 ORDER BY key ASC");
        assertThat(fixture.body(5).has("nextPageToken")).isFalse();
        assertThat(fixture.body(6).get("nextPageToken").stringValue()).isEqualTo("release-page-2");
    }

    @Test
    void capsExactKeyBatchesAtOneHundredAndLeavesUnattributableAliasesUnresolved() {
        var keys = IntStream.rangeClosed(1, 101).mapToObj(index -> "APP-" + index).collect(Collectors.toSet());
        var fixture = new Fixture(new Reply(PROJECT), new Reply(EMPTY_VERSIONS),
                new Reply(issuePage(issue("30001", "RENAMED-1", PROJECT_ID, "20002"), null)), new Reply(EMPTY_ISSUES),
                new Reply(EMPTY_ISSUES));

        var result = fixture.adapter(settings(null), LIMITS).read(input(SOURCE, keys, null), new Gate())
                .projects().getFirst();

        assertThat(result.issues()).isEmpty();
        assertThat(result.unresolvedKeys()).containsExactlyInAnyOrderElementsOf(keys);
        assertThat(result.releaseMembership().state()).isEqualTo(ReleaseMappingState.NOT_REQUESTED);
        var quotedKeys = Pattern.compile("\"(APP-[0-9]+)\"");
        var firstBatch = quotedKeys.matcher(fixture.body(2).get("jql").stringValue()).results()
                .map(match -> match.group(1)).toList();
        var secondBatch = quotedKeys.matcher(fixture.body(3).get("jql").stringValue()).results()
                .map(match -> match.group(1)).toList();
        assertThat(firstBatch).hasSize(100).doesNotContainAnyElementsOf(secondBatch);
        assertThat(secondBatch).hasSize(1);
        assertThat(keys).containsAll(firstBatch).containsAll(secondBatch);
    }

    @Test
    void distinguishesMissingAndAmbiguousVersionNamesFromACompleteEmptyRelease() {
        var missing = new Fixture(new Reply(PROJECT), new Reply(versions(version("20001", "V1.2.3"))), new Reply(EMPTY_ISSUES));
        var ambiguous = new Fixture(new Reply(PROJECT), new Reply(versions(
                version("20001", "v1.2.3") + "," + version("20002", "v1.2.3"))), new Reply(EMPTY_ISSUES));
        var empty = new Fixture(new Reply(PROJECT), new Reply(versions(version("20002", "v1.2.3"))),
                new Reply(EMPTY_ISSUES), new Reply(EMPTY_ISSUES));
        var input = input(SOURCE, Set.of(), "v1.2.3");

        var missingMembership = missing.adapter(settings(null), LIMITS).read(input, new Gate())
                .projects().getFirst().releaseMembership();
        var ambiguousMembership = ambiguous.adapter(settings(null), LIMITS).read(input, new Gate())
                .projects().getFirst().releaseMembership();
        var emptyMembership = empty.adapter(settings(null), LIMITS).read(input, new Gate())
                .projects().getFirst().releaseMembership();

        assertThat(missingMembership.state()).isEqualTo(ReleaseMappingState.NOT_FOUND);
        assertThat(ambiguousMembership.state()).isEqualTo(ReleaseMappingState.AMBIGUOUS);
        assertThat(emptyMembership.state()).isEqualTo(ReleaseMappingState.MATCHED);
        assertThat(emptyMembership.externalVersionId()).isEqualTo("20002");
        assertThat(emptyMembership.issues()).isEmpty();
        assertThat(missing.requests).hasSize(3);
        assertThat(ambiguous.requests).hasSize(3);
        assertThat(empty.requests).hasSize(4);
    }

    @Test
    void readsEveryPageOfAssignedIssuesWithoutScmReferencesOrReleaseMembership() {
        String firstPage = IntStream.rangeClosed(1, 100).mapToObj(JiraAdapterTest::assignedIssue)
                .collect(Collectors.joining(","));
        var fixture = new Fixture(new Reply(PROJECT), new Reply(EMPTY_VERSIONS),
                new Reply(issuePage(firstPage, "assigned-page-2")), new Reply(issuePage(assignedIssue(101), null)));
        var result = fixture.adapter(settings(null), LIMITS).read(input(SOURCE, Set.of(), null), new Gate())
                .projects().getFirst();

        assertThat(result.issues()).hasSize(101).allSatisfy(issue ->
                assertThat(issue.assigneeAccountId()).isEqualTo("fixture-account"));
        assertThat(result.assignedIssues()).hasSize(101).last().isEqualTo(new IssueIdentity(PROJECT_ID, "30101"));
        assertThat(result.releaseMembership().state()).isEqualTo(ReleaseMappingState.NOT_REQUESTED);
        assertThat(fixture.body(2).get("jql").stringValue())
                .isEqualTo("project = 10101 AND assignee IS NOT EMPTY AND statusCategory != Done ORDER BY key ASC");
        assertThat(fixture.body(3).get("nextPageToken").stringValue()).isEqualTo("assigned-page-2");
    }

    @Test
    void refusesAnIncompleteAssignedSearchAtTheResultLimit() {
        var fixture = new Fixture(new Reply(PROJECT), new Reply(EMPTY_VERSIONS),
                new Reply(issuePage(assignedIssue(1), "assigned-page-2")), new Reply(issuePage(assignedIssue(2), null)));
        assertFailure(Code.LIMIT_EXCEEDED, () -> fixture.adapter(settings(null), limits(true, 20, 500, 1))
                .read(input(SOURCE, Set.of(), null), new Gate()));
        assertThat(fixture.body(3).get("maxResults").asInt()).isEqualTo(1);
    }

    @Test
    void usesFixedGatewayInScopedModeWithoutFallingBackOnAuthenticationFailure() {
        var fixture = new Fixture(new Reply(401, Map.of(), TOKEN));
        var adapter = fixture.adapter(settings(CLOUD_ID), LIMITS);

        assertThatExceptionOfType(TicketException.class)
                .isThrownBy(() -> adapter.read(input(SOURCE, Set.of(), null), new Gate()))
                .satisfies(failure -> {
                    assertThat(failure.code()).isEqualTo(Code.AUTHENTICATION_FAILED);
                    assertThat(failure).hasNoCause().hasMessageNotContaining(TOKEN);
                });

        assertThat(fixture.requests).singleElement().satisfies(request ->
                assertThat(request.uri().toString()).isEqualTo(
                        "https://api.atlassian.com/ex/jira/" + CLOUD_ID + API + "/project/10101"));
    }

    @Test
    void makesStableOpaqueAccessContextsSensitiveToRuntimeCredentialScope() {
        var fixture = new Fixture();
        var standard = fixture.adapter(settings(null), LIMITS);
        var equivalentSource = source(URI.create("https://EXAMPLE.ATLASSIAN.NET:443/"));
        var rotated = new JiraProperties(Map.of("jira", new JiraProperties.Instance(SITE.toString(), EMAIL, TOKEN, "revision-2", null)));
        var differentToken = new JiraProperties(Map.of("jira", new JiraProperties.Instance(SITE.toString(), EMAIL, "rotated", null, null)));

        String context = standard.authenticationContext(SOURCE);

        assertThat(context).matches("[0-9a-f]{64}");
        assertThat(fixture.adapter(settings(null), LIMITS).authenticationContext(equivalentSource)).isEqualTo(context);
        assertThat(fixture.adapter(settings(CLOUD_ID), LIMITS).authenticationContext(SOURCE)).isNotEqualTo(context);
        assertThat(fixture.adapter(rotated, LIMITS).authenticationContext(SOURCE)).isNotEqualTo(context);
        assertThat(fixture.adapter(differentToken, LIMITS).authenticationContext(SOURCE)).isNotEqualTo(context);
        assertThat(fixture.requests).isEmpty();
        assertThat(settings(null).toString()).doesNotContain(EMAIL, TOKEN, CONTEXT_SECRET);
        assertThat(settings(null).instances().get("jira").toString()).doesNotContain(EMAIL, TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.atlassian.net", "https://example.atlassian.net:8443",
            "https://other.atlassian.net", "https://example.atlassian.net/jira",
            "https://example.atlassian.net?query=secret", "https://example.atlassian.net/#secret",
            "https://reader:secret@example.atlassian.net"})
    void refusesUnsafeOrMismatchedCatalogOriginsBeforeRequestAdmission(String catalogOrigin) {
        var fixture = new Fixture();
        var gate = new Gate();
        var adapter = fixture.adapter(settings(CLOUD_ID), LIMITS);
        assertFailure(Code.INVALID_CONFIGURATION, () ->
                adapter.read(input(source(URI.create(catalogOrigin)), Set.of(), null), gate));
        assertThat(gate.attempts).isZero();
        assertThat(fixture.requests).isEmpty();
    }

    @Test
    void disabledReadAndMalformedIdentifiersPerformNoHttpRequests() {
        var fixture = new Fixture();
        var gate = new Gate();
        var disabled = fixture.adapter(settings(null), limits(false, 20, 500));
        assertFailure(Code.DISABLED, () -> disabled.read(input(SOURCE, Set.of(), null), gate));
        var enabled = fixture.adapter(settings(null), LIMITS);
        assertFailure(Code.INVALID_CONFIGURATION, () ->
                enabled.read(input(SOURCE, Set.of("APP-1\") OR project = 123"), null), gate));
        assertFailure(Code.INVALID_CONFIGURATION, () ->
                fixture.adapter(settings("1-1-1-1-1"), LIMITS).read(input(SOURCE, Set.of(), null), gate));
        assertThat(gate.attempts).isZero();
        assertThat(fixture.requests).isEmpty();
    }

    @Test
    void rejectsAnIssueFromTheWrongStableProjectAndInvalidReleaseMembership() {
        var wrongProject = new Fixture(new Reply(PROJECT), new Reply(EMPTY_VERSIONS),
                new Reply(issuePage(issue("30001", "APP-1", "99999", "20002"), null)));
        assertFailure(Code.INVALID_RESPONSE, () -> wrongProject.adapter(settings(null), LIMITS)
                .read(input(SOURCE, Set.of("APP-1"), null), new Gate()));
        var wrongVersion = new Fixture(new Reply(PROJECT), new Reply(versions(version("20002", "v1.2.3"))),
                new Reply(issuePage(issue("30001", "APP-1", PROJECT_ID, "20001"), null)));
        assertFailure(Code.INVALID_RESPONSE, () -> wrongVersion.adapter(settings(null), LIMITS)
                .read(input(SOURCE, Set.of(), "v1.2.3"), new Gate()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"isLast\":false,\"issues\":[]}", "{\"isLast\":true,\"issues\":[]} {}",
            "{\"isLast\":true,\"isLast\":true,\"issues\":[]}", "{\"isLast\":true,\"issues\":{}}",
            "{\"isLast\":false,\"nextPageToken\":\"token\",\"issues\":[]}"})
    void rejectsInvalidOrNonProgressingSearchPages(String body) {
        var fixture = new Fixture(new Reply(PROJECT), new Reply(EMPTY_VERSIONS), new Reply(body));
        assertFailure(Code.INVALID_RESPONSE, () -> fixture.adapter(settings(null), LIMITS)
                .read(input(SOURCE, Set.of("APP-1"), null), new Gate()));
    }

    @Test
    void rejectsRepeatedSearchTokensAndDuplicateVersionIds() {
        var repeatedToken = new Fixture(new Reply(PROJECT), new Reply(EMPTY_VERSIONS),
                new Reply(issuePage(issue("30001", "APP-1", PROJECT_ID, "20002"), "same-token")),
                new Reply(issuePage(issue("30002", "APP-2", PROJECT_ID, "20002"), "same-token")));
        assertFailure(Code.INVALID_RESPONSE, () -> repeatedToken.adapter(settings(null), LIMITS)
                .read(input(SOURCE, Set.of("APP-1", "APP-2"), null), new Gate()));
        var duplicateVersion = new Fixture(new Reply(PROJECT), new Reply(versions(
                version("20001", "one") + "," + version("20001", "two"))));
        assertFailure(Code.INVALID_RESPONSE, () -> duplicateVersion.adapter(settings(null), LIMITS)
                .read(input(SOURCE, Set.of(), null), new Gate()));
    }

    @Test
    void failsPageOrReleaseResultExhaustionWithoutReturningATruncatedSnapshot() {
        var pages = new Fixture(new Reply(PROJECT), new Reply(EMPTY_VERSIONS),
                new Reply(issuePage(issue("30001", "APP-1", PROJECT_ID, "20002"), "another-page")));
        assertFailure(Code.LIMIT_EXCEEDED, () -> pages.adapter(settings(null), limits(true, 1, 500))
                .read(input(SOURCE, Set.of("APP-1", "APP-2"), null), new Gate()));
        assertThat(pages.requests).hasSize(3);
        var release = new Fixture(new Reply(PROJECT), new Reply(versions(version("20002", "v1.2.3"))),
                new Reply(issuePage(issue("30001", "APP-1", PROJECT_ID, "20002") + ","
                        + issue("30002", "APP-2", PROJECT_ID, "20002"), null)));
        assertFailure(Code.LIMIT_EXCEEDED, () -> release.adapter(settings(null), limits(true, 20, 1))
                .read(input(SOURCE, Set.of(), "v1.2.3"), new Gate()));
        assertThat(release.requests).hasSize(3);
    }

    @Test
    void completesEverySelectedVersionByIdIncludingSharedAndEmptyMemberships() {
        String shared = issue("30001", "APP-1", PROJECT_ID, "20001")
                .replace("[{\"id\":\"20001\"}]", "[{\"id\":\"20001\"},{\"id\":\"20002\"}]");
        var fixture = new Fixture(
                new Reply("""
                        {"startAt":0,"total":3,"isLast":false,"values":[%s,%s]}
                        """.formatted(version("20001", "1.2.3"), version("20002", "1.2.4"))),
                new Reply("""
                        {"startAt":2,"total":3,"isLast":true,"values":[%s]}
                        """.formatted(version("20003", "1.2.5"))),
                new Reply(issuePage(shared, "next-issues")),
                new Reply(issuePage(issue("30002", "APP-2", PROJECT_ID, "20001"), null)),
                new Reply(issuePage(shared, null)),
                new Reply(EMPTY_ISSUES));
        var adapter = fixture.adapter(settings(null), LIMITS);
        var query = new ProjectQuery(BINDING_ID, PROJECT_ID, "APP", Set.of(), null);
        var observed = adapter.readVersionMemberships(SOURCE, query, adapter.captureAccess(SOURCE), new Gate(),
                catalog -> {
                    assertThat(catalog).hasSize(3);
                    return Set.of("20001", "20002", "20003");
                }, 3, 5);

        assertThat(fixture.requests).hasSize(6);
        assertThat(fixture.body(2).get("jql").stringValue()).contains("fixVersion = 20001");
        assertThat(fixture.body(3).get("nextPageToken").stringValue()).isEqualTo("next-issues");
        assertThat(fixture.body(4).get("jql").stringValue()).contains("fixVersion = 20002");
        assertThat(fixture.body(5).get("jql").stringValue()).contains("fixVersion = 20003");
        assertThat(observed.members().get("20001")).extracting(IssueObservation::issueKey)
                .containsExactly("APP-1", "APP-2");
        assertThat(observed.members().get("20002")).extracting(IssueObservation::issueKey).containsExactly("APP-1");
        assertThat(observed.members().get("20003")).isEmpty();
    }

    @Test
    void refusesContradictoryMembershipAcrossSelectedVersions() {
        String shared = issue("30001", "APP-1", PROJECT_ID, "20001")
                .replace("[{\"id\":\"20001\"}]", "[{\"id\":\"20001\"},{\"id\":\"20002\"}]");
        var fixture = new Fixture(
                new Reply(versions(version("20001", "1.2.3") + "," + version("20002", "1.2.4"))),
                new Reply(issuePage(shared, null)), new Reply(EMPTY_ISSUES));
        var adapter = fixture.adapter(settings(null), LIMITS);
        var query = new ProjectQuery(BINDING_ID, PROJECT_ID, "APP", Set.of(), null);
        assertFailure(Code.INVALID_RESPONSE, () -> adapter.readVersionMemberships(SOURCE, query,
                adapter.captureAccess(SOURCE), new Gate(), catalog -> Set.of("20001", "20002"), 2, 10));
    }

    private static JiraProperties settings(String cloudId) {
        return new JiraProperties(Map.of("jira", new JiraProperties.Instance(SITE.toString(), EMAIL, TOKEN, null, cloudId)));
    }

    private static InstanceSource source(URI origin) {
        return new InstanceSource(SOURCE.instanceId(), SOURCE.instanceKey(), SOURCE.provider(), origin, 0);
    }

    private static ProviderInput input(InstanceSource source, Set<String> keys, String release) {
        return new ProviderInput(source, List.of(new ProjectQuery(BINDING_ID, PROJECT_ID, "APP", keys, release)), NOW);
    }

    private static TicketProperties limits(boolean enabled, int pages, int releaseIssues) {
        return limits(enabled, pages, releaseIssues, 500);
    }

    private static TicketProperties limits(boolean enabled, int pages, int releaseIssues, int assignedIssues) {
        return new TicketProperties(enabled, Duration.ofMinutes(2), Duration.ofSeconds(10), 100, pages,
                2 * 1024 * 1024, 16 * 1024 * 1024, 500, releaseIssues, 1000,
                5000, 8 * 1024 * 1024, 20, 500, 10000, Duration.ofMinutes(15), 50, assignedIssues);
    }

    private static void assertFailure(Code code, Runnable call) {
        assertThatExceptionOfType(TicketException.class).isThrownBy(call::run)
                .satisfies(failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static String version(String id, String name) {
        return """
                {"id":"%s","projectId":10101,"name":"%s","released":true,"archived":false,"releaseDate":"2026-10-10"}
                """.formatted(id, name);
    }

    private static String versions(String versionRows) {
        return """
                {"startAt":0,"isLast":true,"values":[%s]}
                """.formatted(versionRows);
    }

    private static String issue(String id, String key, String projectId, String versionId) {
        return """
                {"id":"%s","key":"%s","fields":{"project":{"id":"%s"},"summary":"Synthetic issue","description":"TICKET-BODY-MUST-NOT-SURVIVE",
                  "issuetype":{"name":"Task"},"status":{"name":"In Progress","statusCategory":{"key":"indeterminate"}},
                  "priority":{"name":"Medium"},"resolution":null,"created":"2026-10-01T14:30:00.000+0200",
                  "updated":"2026-10-09T10:00:00Z","fixVersions":[{"id":"%s"}]}}
                """.formatted(id, key, projectId, versionId);
    }

    private static String issuePage(String rows, String nextToken) {
        return nextToken == null
                ? "{\"isLast\":true,\"issues\":[" + rows + "]}"
                : "{\"isLast\":false,\"nextPageToken\":\"" + nextToken + "\",\"issues\":[" + rows + "]}";
    }

    private static String assignedIssue(int index) {
        return """
                {"id":"%d","key":"RENAMED-%d","fields":{"project":{"id":"10101"},
                  "status":{"statusCategory":{"key":"indeterminate"}},"fixVersions":[],
                  "assignee":{"accountId":"fixture-account"}}}
                """.formatted(30000 + index, index);
    }

    private record Reply(int status, Map<String, List<String>> headers, String body) {
        private Reply(String body) {
            this(200, Map.of(), body);
        }
    }

    private static final class Fixture {
        private final List<HttpRequest> requests = new ArrayList<>();
        private final HttpClient client = mock(HttpClient.class);

        private Fixture(Reply... replies) {
            var remaining = new ArrayDeque<>(List.of(replies));
            when(client.<byte[]>sendAsync(any(), any())).thenAnswer(invocation -> {
                requests.add(invocation.getArgument(0));
                HttpResponse.BodyHandler<byte[]> handler = invocation.getArgument(1);
                Reply reply = remaining.remove();
                var headers = HttpHeaders.of(reply.headers(), (name, value) -> true);
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
                    @SuppressWarnings("unchecked")
                    HttpResponse<byte[]> response = mock(HttpResponse.class);
                    when(response.statusCode()).thenReturn(reply.status());
                    when(response.headers()).thenReturn(headers);
                    when(response.body()).thenReturn(bytes);
                    return response;
                }).toCompletableFuture();
            });
        }

        private JiraAdapter adapter(JiraProperties settings, TicketProperties limits) {
            return new JiraAdapter(JiraTestCredentials.access(settings), limits, new JiraTransport(client, limits.maxResponseBytes(), CLOCK),
                    MAPPER, CLOCK);
        }

        private JsonNode body(int requestIndex) {
            var bytes = new ByteArrayOutputStream();
            var body = new CompletableFuture<byte[]>();
            requests.get(requestIndex).bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    subscription.request(Long.MAX_VALUE);
                }

                @Override
                public void onNext(ByteBuffer buffer) {
                    byte[] chunk = new byte[buffer.remaining()];
                    buffer.get(chunk);
                    bytes.writeBytes(chunk);
                }

                @Override
                public void onError(Throwable error) {
                    body.completeExceptionally(error);
                }

                @Override
                public void onComplete() {
                    body.complete(bytes.toByteArray());
                }
            });
            return MAPPER.readTree(body.join());
        }
    }

    private static final class Gate implements TicketRequestGate {
        private int attempts;
        private int objects;
        private long bytes;

        @Override
        public Duration beforeRequest() {
            attempts++;
            return Duration.ofSeconds(10);
        }

        @Override
        public Duration remaining() {
            return Duration.ofSeconds(120);
        }

        @Override
        public void receivedBytes(long count) {
            bytes += count;
        }

        @Override
        public void acceptedObjects(int count) {
            objects += count;
        }

        @Override
        public void check() {}
    }
}
