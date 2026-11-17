package io.givops.ticketing.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

import io.givops.adapters.FailureCategory;
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

import io.givops.ticketing.model.TicketWriteModels.*;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;

class JiraTicketWriterTest {
    static final JsonMapper JSON=JsonMapper.builder().build();
    static final String PROJECT="{\"id\":\"100\",\"key\":\"EX\",\"name\":\"Example\"}";
    static final String VERSION="{\"id\":\"55\",\"projectId\":100,\"name\":\"1.2\",\"released\":false,\"archived\":false}";
    static final String TYPES="{\"startAt\":0,\"total\":1,\"issueTypes\":[{\"id\":\"7\",\"name\":\"Task\",\"subtask\":false}]}";
    static final String FIELDS="{\"startAt\":0,\"total\":4,\"fields\":[{\"fieldId\":\"project\",\"required\":true},{\"fieldId\":\"summary\",\"required\":true},{\"fieldId\":\"issuetype\",\"required\":true},{\"fieldId\":\"fixVersions\",\"required\":false}]}";
    static String issue(String state) {return "{\"id\":\"900\",\"key\":\"EX-1\",\"fields\":{\"project\":{\"id\":\"100\"},\"summary\":\"Work\",\"issuetype\":{\"name\":\"Task\"},\"status\":{\"name\":\""+state+"\",\"statusCategory\":{\"key\":\""+state+"\"}},\"fixVersions\":[{\"id\":\"55\"}]}}";}
    static RemoteProjectRef project(Fixture f) {return new RemoteProjectRef(UUID.randomUUID(),f.source.instanceId(),"100","EX","Example",1);}
    static CreateIssueCommand command(WorkflowState state) {return new CreateIssueCommand("Work",null,IssueKind.TASK,null,state,null,null,null,List.of(),false,null,List.of(new VersionSelector(VersionSelectorKind.EXTERNAL_ID,"55")));}
    @Test void createsProjectUsingExplicitTemplateAndActiveLeadThenReadsActualProject() {
        var f=new Fixture(new Reply(200,"{\"permissions\":{\"ADMINISTER\":{\"havePermission\":true}}}"),new Reply(200,"{\"accountId\":\"lead\",\"active\":true}"),new Reply(201,"{\"id\":100,\"key\":\"EX\"}"),new Reply(200,PROJECT));
        var command=new CreateProjectCommand("EX","Example","Description","lead","com.pyxis.greenhopper.jira:gh-simplified-basic");
        assertThat(f.writer().createProject(f.source,command,f.access,new Gate()).externalProjectId()).isEqualTo("100");
        assertThat(f.body(2).path("projectTypeKey").asText()).isEqualTo("software");assertThat(f.body(2).path("leadAccountId").asText()).isEqualTo("lead");
        assertThat(f.requests).extracting(HttpRequest::method).containsExactly("GET","GET","POST","GET");
    }
    @Test void usesAuthenticatedLeadWhenNoExplicitAccountIdWasSupplied() {
        var f=new Fixture(new Reply(200,"{\"permissions\":{\"ADMINISTER\":{\"havePermission\":true}}}"),new Reply(200,"{\"accountId\":\"authenticated\",\"active\":true}"),new Reply(201,"{\"id\":100,\"key\":\"EX\"}"),new Reply(200,PROJECT));
        f.writer().createProject(f.source,new CreateProjectCommand("EX","Example",null,null,"com.pyxis.greenhopper.jira:gh-simplified-kanban-classic"),f.access,new Gate());
        assertThat(f.requests.get(1).uri().getPath()).endsWith("/myself");
        assertThat(f.body(2).path("leadAccountId").asText()).isEqualTo("authenticated");
    }
    @Test void requiresTemplateLeadAndGlobalAdminBeforeMutation() {
        var f=new Fixture();
        assertThatThrownBy(() -> f.writer().createProject(f.source,new CreateProjectCommand("EX","Example",null,null),f.access,new Gate())).isInstanceOf(TicketException.class);
        assertThat(f.requests).isEmpty();
        var denied=new Fixture(new Reply(200,"{\"permissions\":{\"ADMINISTER\":{\"havePermission\":false}}}"));
        assertThatThrownBy(() -> denied.writer().createProject(denied.source,new CreateProjectCommand("EX","Example",null,"lead","com.pyxis.greenhopper.jira:gh-simplified-basic"),denied.access,new Gate())).isInstanceOfSatisfying(TicketException.class,e -> assertThat(e.code()).isEqualTo(Code.AUTHORIZATION_FAILED));
        assertThat(denied.requests).hasSize(1);
    }
    @Test void createsVersionWithNativeFlagsAndAuthoritativeReadback() {
        var f=new Fixture(new Reply(200,PROJECT),new Reply(201,VERSION),new Reply(200,VERSION));
        assertThat(f.writer().createVersion(f.source,project(f),new CreateVersionCommand("1.2",false,false,null),f.access,new Gate()).externalVersionId()).isEqualTo("55");
        assertThat(f.requests).extracting(HttpRequest::method).containsExactly("GET","POST","GET");
    }
    @Test void createsDoneUsingObservedTypeVersionAndOneDirectTransition() {
        var f=doneFixture(204);
        var actual=f.writer().createIssue(f.source,project(f),command(WorkflowState.DONE),f.access,new Gate());
        assertThat(actual.statusCategory()).isEqualTo("done");assertThat(actual.releaseVersionIds()).containsExactly("55");
        assertThat(f.body(4).path("fields").path("issuetype").path("id").asText()).isEqualTo("7");
        assertThat(f.body(4).path("fields").path("fixVersions").get(0).path("id").asText()).isEqualTo("55");
        assertThat(f.requests).extracting(HttpRequest::method).containsExactly("GET","GET","GET","GET","POST","GET","GET","POST","GET");
    }
    @Test void portableTypeMetadataForcesEnglishWithoutChangingNativeObservations() {
        var f=doneFixture(204);
        f.writer().createIssue(f.source,project(f),command(WorkflowState.DONE),f.access,new Gate());
        var metadata=f.requests.get(1);
        assertThat(metadata.uri().getPath()).endsWith("/issuetypes");
        assertThat(metadata.headers().firstValue("Accept-Language")).contains("en-US");
        assertThat(metadata.headers().firstValue("X-Force-Accept-Language")).contains("true");
        assertThat(f.requests.get(5).headers().firstValue("Accept-Language")).isEmpty();
        assertThat(f.requests.getLast().headers().firstValue("Accept-Language")).isEmpty();
    }
    @Test void explicitLocalizedCustomTypeKeepsNativeMetadataAndMatchingIdentity() {
        var f=new Fixture(new Reply(200,PROJECT),new Reply(200,TYPES.replace("Task","Tâche")),new Reply(200,FIELDS),new Reply(201,"{\"id\":\"900\",\"key\":\"EX-1\"}"),new Reply(200,issue("new")));
        var command=new CreateIssueCommand("Work",null,null,"Tâche",WorkflowState.OPEN,null,null,null,List.of(),false,null);
        f.writer().createIssue(f.source,project(f),command,f.access,new Gate());
        assertThat(f.requests.get(1).headers().firstValue("Accept-Language")).isEmpty();
        assertThat(f.requests.get(1).headers().firstValue("X-Force-Accept-Language")).isEmpty();
        assertThat(f.body(3).path("fields").path("issuetype").path("id").asText()).isEqualTo("7");
    }
    @Test void ambiguousPortableTypesFailBeforeMutationEvenWithForcedEnglish() {
        var types=TYPES.replace("\"total\":1","\"total\":2").replace("}]}","},{\"id\":\"8\",\"name\":\"Task\",\"subtask\":false}]}");
        var f=new Fixture(new Reply(200,PROJECT),new Reply(200,types));
        assertThatThrownBy(() -> f.writer().createIssue(f.source,project(f),command(WorkflowState.OPEN),f.access,new Gate())).isInstanceOfSatisfying(TicketException.class,e -> assertThat(e.code()).isEqualTo(Code.UNSUPPORTED_PROVIDER));
        assertThat(f.requests).allSatisfy(r -> assertThat(r.method()).isEqualTo("GET"));
    }
    @Test void uncertainTransitionReadsActualDoneWithoutReplay() {
        var f=doneFixture(503);
        assertThat(f.writer().createIssue(f.source,project(f),command(WorkflowState.DONE),f.access,new Gate()).statusCategory()).isEqualTo("done");
        assertThat(f.requests.stream().filter(r -> r.method().equals("POST"))).hasSize(2);
    }
    static Fixture doneFixture(int transitionStatus) {
        return new Fixture(new Reply(200,PROJECT),new Reply(200,TYPES),new Reply(200,"{\"startAt\":0,\"total\":1,\"values\":["+VERSION+"]}"),new Reply(200,FIELDS),new Reply(201,"{\"id\":\"900\",\"key\":\"EX-1\"}"),new Reply(200,issue("new")),new Reply(200,"{\"transitions\":[{\"id\":\"31\",\"to\":{\"statusCategory\":{\"key\":\"done\"}},\"fields\":{}}]}"),new Reply(transitionStatus,"private provider error"),new Reply(200,issue("done")));
    }
    @Test void unsupportedStateAndMissingRequiredFieldsFailBeforeCreate() {
        var f=new Fixture();
        assertThatThrownBy(() -> f.writer().createIssue(f.source,project(f),command(WorkflowState.IN_PROGRESS),f.access,new Gate())).isInstanceOf(TicketException.class);assertThat(f.requests).isEmpty();
        var missing=new Fixture(new Reply(200,PROJECT),new Reply(200,TYPES),new Reply(200,"{\"startAt\":0,\"total\":1,\"values\":["+VERSION+"]}"),new Reply(200,FIELDS.replace("\"fixVersions\",\"required\":false","\"customfield_10000\",\"required\":true")));
        assertThatThrownBy(() -> missing.writer().createIssue(missing.source,project(missing),command(WorkflowState.OPEN),missing.access,new Gate())).isInstanceOf(TicketException.class);
        assertThat(missing.requests).allSatisfy(r -> assertThat(r.method()).isEqualTo("GET"));
    }
    @Test void providerDenialIsSanitizedAndWriteNotReplayed() {
        for(int status:List.of(401,403,429,503)) {
            var f=new Fixture(new Reply(200,PROJECT),new Reply(status,"private provider error"));
            assertThatThrownBy(() -> f.writer().createVersion(f.source,project(f),new CreateVersionCommand("1.2",false,false,null),f.access,new Gate())).isInstanceOf(TicketException.class).hasMessageNotContaining("private provider error");assertThat(f.requests).hasSize(2);
        }
    }
    private record Reply(int status, String body) { }
    private static final class Fixture {
        final HttpClient client = mock(HttpClient.class);
        final List<HttpRequest> requests = new ArrayList<>();
        final JiraAccess credentials;
        final JiraAccess.Captured access;
        final InstanceSource source;
        Fixture(Reply... replies) {
            var remaining = new ArrayDeque<>(List.of(replies));
            source = new InstanceSource(UUID.randomUUID(), "fixture", TicketProvider.JIRA_CLOUD, URI.create("https://fixture.atlassian.net"), 0);
            var settings = new JiraProperties(Map.of("fixture",
                    new JiraProperties.Instance(source.baseUrl().toString(), "fixture@example.test", "synthetic-token", "one", null)));
            credentials = JiraTestCredentials.access(settings);
            access = credentials.capture(source);
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
        JiraTicketWriter writer() {
            var limits = new TicketProperties(true, Duration.ofMinutes(2), Duration.ofSeconds(10), 100, 20, 1024, 16384,
                    500, 500, 1000, 5000, 8388608, 20, 500, 10000, Duration.ofMinutes(15), 50, 500);
            return new JiraTicketWriter(credentials, new JiraTransport(client, 16384, Clock.systemUTC()),
                    new JiraWriteTransport(client, 16384), limits, JSON, Clock.systemUTC());
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
