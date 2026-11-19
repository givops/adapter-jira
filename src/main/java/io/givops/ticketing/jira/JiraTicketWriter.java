package io.givops.ticketing.jira;

import io.givops.adapters.CapabilityInspection;
import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.*;
import io.givops.ticketing.model.TicketWriteModels.*;
import io.givops.ticketing.port.TicketCapabilityInspector;
import io.givops.ticketing.port.TicketProviderAdapter.Access;
import io.givops.ticketing.port.TicketRequestGate;
import io.givops.ticketing.port.TicketWriteAdapter;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

/** Explicit Jira Cloud provisioning; no mutation is automatically replayed. */
public final class JiraTicketWriter implements TicketWriteAdapter, TicketCapabilityInspector, AutoCloseable {
    private static final Set<String> SOFTWARE_TEMPLATES=Set.of("gh-simplified-agility-kanban","gh-simplified-agility-scrum","gh-simplified-basic","gh-simplified-kanban-classic","gh-simplified-scrum-classic");
    private static final Set<String> BUSINESS_TEMPLATES=Set.of("content-management","document-approval","lead-tracking","process-control","procurement","project-management","recruitment","task-tracking");
    private static final String ISSUE_FIELDS="project,summary,issuetype,status,priority,resolution,created,updated,resolutiondate,fixVersions,assignee";
    private final JiraAccess credentials;
    private final JiraTransport reads;
    private final JiraWriteTransport writes;
    private final TicketProperties limits;
    private final JsonMapper mapper;
    private final ObjectReader reader;
    private final Clock clock;
    JiraTicketWriter(JiraAccess credentials,JiraTransport reads,TicketProperties limits,JsonMapper mapper,Clock clock) {
        this(credentials,reads,new JiraWriteTransport(JiraTransport.clientBuilder(limits.requestTimeout()).build(),limits.maxResponseBytes(),reads.connectionClients()),limits,mapper,clock);
    }
    JiraTicketWriter(JiraAccess credentials,JiraTransport reads,JiraWriteTransport writes,TicketProperties limits,JsonMapper mapper,Clock clock) {
        this.credentials=credentials;this.reads=reads;this.writes=writes;this.limits=limits;this.mapper=mapper;this.clock=clock;
        this.reader=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
    }
    @Override public Access captureAccess(InstanceSource source) { return credentials.capture(source); }
    @Override public void requireCurrent(InstanceSource source,Access access,boolean locked) {
        if (!(access instanceof JiraAccess.Captured jira)) throw invalid();
        credentials.requireSource(source,jira);credentials.requireCurrent(jira,locked);
    }
    @Override public TicketProvider provider() { return TicketProvider.JIRA_CLOUD; }
    @Override public ProjectObservation createProject(InstanceSource source,CreateProjectCommand command,Access access,TicketRequestGate gate) {
        if(command==null || command.projectKey()==null || !JiraPayload.PROJECT_KEY.matcher(command.projectKey()).matches() || blank(command.displayName()) || blank(command.projectTemplateKey())) throw invalid();
        String type=projectType(command.projectTemplateKey());
        var session=session(source,access,gate);
        var permissions=session.get("/mypermissions?permissions=ADMINISTER");
        if(!permissions.path("permissions").path("ADMINISTER").path("havePermission").asBoolean()) throw failure(Code.AUTHORIZATION_FAILED,"jira.provisioning.denied");
        var lead=blank(command.leaderExternalId()) ? session.get("/myself") : session.get("/user?accountId="+encode(command.leaderExternalId()));
        String leadId=JiraPayload.text(lead,"accountId",true);
        if(!blank(command.leaderExternalId()) && !command.leaderExternalId().equals(leadId) || !lead.path("active").asBoolean()) throw invalid();
        var body=new LinkedHashMap<String,Object>(); body.put("key",command.projectKey());body.put("name",command.displayName());body.put("leadAccountId",leadId);body.put("projectTypeKey",type);body.put("projectTemplateKey",command.projectTemplateKey());
        if(command.description()!=null) body.put("description",command.description());
        var created=session.post("/project",body,201);
        String id=JiraReportPayload.identifier(created,"id");
        var actual=JiraPayload.project(session.get("/project/"+id),id,session.connection,clock.instant());
        if(!command.projectKey().equals(actual.projectKey())) throw JiraPayload.invalidResponse();
        return actual;
    }
    @Override public VersionObservation createVersion(InstanceSource source,RemoteProjectRef project,CreateVersionCommand command,Access access,TicketRequestGate gate) {
        requireProject(source,project); if(command==null || blank(command.name())) throw invalid();
        var session=session(source,access,gate);session.project(project);
        var body=new LinkedHashMap<String,Object>();body.put("projectId",Long.parseLong(project.externalProjectId()));body.put("name",command.name());body.put("released",command.released());body.put("archived",command.archived());
        if(command.releaseDate()!=null) body.put("releaseDate",command.releaseDate().toString());
        var created=session.post("/version",body,201);
        String id=JiraReportPayload.identifier(created,"id");
        var actual=JiraPayload.version(session.get("/version/"+id),project.externalProjectId(),clock.instant());
        if(!command.name().equals(actual.name()) || actual.released()!=command.released() || actual.archived()!=command.archived()) throw JiraPayload.invalidResponse();
        return actual;
    }
    @Override public IssueObservation createIssue(InstanceSource source,RemoteProjectRef project,CreateIssueCommand command,Access access,TicketRequestGate gate) {
        requireProject(source,project);
        if(command==null || blank(command.summary()) || command.summary().length()>255 || !command.fixVersions().isEmpty() && !command.versionSelectors().isEmpty()) throw invalid();
        if(command.workflowState()!=null && command.workflowState()!=WorkflowState.OPEN && command.workflowState()!=WorkflowState.DONE || command.providerStatus()!=null || command.priority()!=null || command.providerPriority()!=null) throw unsupported();
        var session=session(source,access,gate);session.project(project);
        String typeName=command.providerType()!=null ? command.providerType() : command.type()==null ? "Task" : switch(command.type()) {case TASK->"Task";case BUG->"Bug";case FEATURE->"Story";case EPIC->"Epic";};
        // Portable kinds use Jira's English built-in names; explicit native/custom names retain the user's locale.
        var types=session.pages("/issue/createmeta/"+project.externalProjectId()+"/issuetypes","issueTypes",command.providerType()==null);
        var matching=types.stream().filter(t -> typeName.equalsIgnoreCase(JiraPayload.text(t,"name",true)) && !t.path("subtask").asBoolean()).toList();
        if(matching.size()!=1) throw unsupported();
        String typeId=JiraReportPayload.identifier(matching.getFirst(),"id");
        var fields=new LinkedHashMap<String,Object>();fields.put("project",Map.of("id",project.externalProjectId()));fields.put("summary",command.summary());fields.put("issuetype",Map.of("id",typeId));
        if(command.description()!=null) fields.put("description",JiraReleaseWriter.adf(command.description()));
        String assignee=command.assigneeExternalId();
        if(command.assignToSelf()) assignee=JiraPayload.text(session.get("/myself"),"accountId",true);
        if(assignee!=null) fields.put("assignee",Map.of("accountId",assignee));
        var selectors=new ArrayList<VersionSelector>(command.versionSelectors());
        for(String name:command.fixVersions()) selectors.add(new VersionSelector(VersionSelectorKind.NAME,name));
        if(!selectors.isEmpty()) {
            var versions=session.pages("/project/"+project.externalProjectId()+"/version","values");var values=new ArrayList<Object>();
            for(var selector:selectors) {
                var matches=versions.stream().filter(v -> switch(selector.kind()) {case NAME -> selector.value().equals(JiraPayload.text(v,"name",true));case EXTERNAL_ID -> selector.value().equals(JiraReportPayload.identifier(v,"id"));}).toList();
                if(matches.size()!=1) throw invalid();
                values.add(Map.of("id",JiraReportPayload.identifier(matches.getFirst(),"id")));
            }
            fields.put("fixVersions",values);
        }
        var metadata=session.pages("/issue/createmeta/"+project.externalProjectId()+"/issuetypes/"+typeId,"fields");
        var allowed=new java.util.HashSet<String>();
        for(var field:metadata) {
            String id=JiraPayload.text(field,"fieldId",true);allowed.add(id);
            if(field.path("required").asBoolean() && !field.path("hasDefaultValue").asBoolean() && !fields.containsKey(id)) throw unsupported();
        }
        if(!allowed.containsAll(fields.keySet())) throw unsupported();
        var created=session.post("/issue",Map.of("fields",fields),201);String id=JiraReportPayload.identifier(created,"id");
        IssueObservation actual=session.issue(id,project);
        if(command.workflowState()==WorkflowState.DONE && !"done".equals(actual.statusCategory())) {
            var transitions=JiraPayload.array(session.get("/issue/"+id+"/transitions?expand=transitions.fields"),"transitions",limits.maxObjects());
            var possible=new ArrayList<JsonNode>();
            for(var transition:transitions) {
                if(!"done".equals(transition.path("to").path("statusCategory").path("key").asText())) continue;
                boolean required=false;
                for(var field:transition.path("fields")) if(field.path("required").asBoolean() && !field.path("hasDefaultValue").asBoolean()) required=true;
                if(!required) possible.add(transition);
            }
            if(possible.size()!=1) throw unsupported();
            TicketException closeFailure=null;
            try { session.post("/issue/"+id+"/transitions",Map.of("transition",Map.of("id",JiraReportPayload.identifier(possible.getFirst(),"id"))),204); }
            catch(TicketException failure) { closeFailure=failure; }
            // Read once after the transition, even when delivery was uncertain. Never replay the POST.
            actual=session.issue(id,project);
            if(!"done".equals(actual.statusCategory())) { if(closeFailure!=null) throw closeFailure;throw JiraPayload.invalidResponse(); }
        }
        if(command.workflowState()==WorkflowState.OPEN && !"new".equals(actual.statusCategory())) throw unsupported();
        return actual;
    }
    @Override public CapabilityInspection inspect(InstanceSource source,String projectId,String capability,Access access,TicketRequestGate gate) {
        var session=session(source,access,gate);var state=CapabilityInspection.State.UNKNOWN;
        if(!Set.of("ticketing.project-create","ticketing.version-create","ticketing.issue-create").contains(capability)) state=CapabilityInspection.State.UNSUPPORTED;
        else if("ticketing.project-create".equals(capability)) {
            var permissions=session.get("/mypermissions?permissions=ADMINISTER");
            if(!permissions.path("permissions").path("ADMINISTER").path("havePermission").asBoolean()) state=CapabilityInspection.State.FORBIDDEN;
        }
        return new CapabilityInspection(provider().name(),capability,state,"Template, issue fields, permissions and workflow are validated by the explicit command",null,"jira-cloud-rest-v3",session.captured.context(),clock.instant());
    }
    private Session session(InstanceSource source,Access access,TicketRequestGate gate) {
        if(!limits.enabled()) throw failure(Code.DISABLED,"jira.provisioning.disabled");
        var captured=access==null?credentials.capture(source):access;
        if(!(captured instanceof JiraAccess.Captured jira)) throw invalid();
        credentials.requireSource(source,jira);credentials.requireCurrent(jira,false);return new Session(jira,gate);
    }
    private final class Session {
        final JiraAccess.Captured captured;final JiraConnection connection;final TicketRequestGate gate;
        Session(JiraAccess.Captured captured,TicketRequestGate host) {
            this.captured=captured;this.connection=captured.connection();
            this.gate=new TicketRequestGate() {
                public Duration beforeRequest(){credentials.requireCurrent(captured,false);return host.beforeRequest();}
                public void receivedBytes(long bytes){host.receivedBytes(bytes);}
                public void acceptedObjects(int count){host.acceptedObjects(count);}
                public void check(){credentials.requireCurrent(captured,false);host.check();}
                public io.givops.adapters.ConnectionTarget connectionTarget(){return host.connectionTarget();}
                public Duration remaining(){return host.remaining();}
            };
        }
        JsonNode get(String path) { var root=parse(reads.read(connection.endpoint(path),connection.authorization(),null,gate));gate.acceptedObjects(1);gate.check();return root; }
        JsonNode post(String path,Object body,int expected) {
            byte[] bytes=mapper.writeValueAsBytes(body);if(bytes.length>65_536) throw JiraTransport.limitExceeded();
            JiraWriteTransport.Response response;
            try {response=writes.post(connection.endpoint(path),connection.authorization(),bytes,gate);}
            catch(JiraWriteTransport.Failure failure) {throw new TicketException(Code.PROVIDER_ERROR,
                    JiraMessages.format("jira.provisioning.uncertain") + " " + failure.getMessage());}
            gate.check();
            if(response.status()!=expected) throw new TicketException(switch(response.status()) {case 401 -> Code.AUTHENTICATION_FAILED;case 403,404 -> Code.AUTHORIZATION_FAILED;case 429 -> Code.RATE_LIMITED;case 408 -> Code.TIMEOUT;case 400,409,422 -> Code.INVALID_CONFIGURATION;default -> Code.PROVIDER_ERROR;},response.evidence());
            if(expected==204) return mapper.createObjectNode();
            var result=parse(response.body());gate.acceptedObjects(1);return result;
        }
        void project(RemoteProjectRef ref) {var actual=JiraPayload.project(get("/project/"+ref.externalProjectId()),ref.externalProjectId(),connection,clock.instant());if(!ref.projectKey().equals(actual.projectKey())) throw JiraPayload.invalidResponse();}
        IssueObservation issue(String id,RemoteProjectRef project) {var actual=JiraPayload.issue(get("/issue/"+id+"?fields="+ISSUE_FIELDS),project.externalProjectId(),connection,clock.instant());if(!id.equals(actual.identity().externalIssueId()) || !actual.issueKey().startsWith(project.projectKey()+"-")) throw JiraPayload.invalidResponse();return actual;}
        List<JsonNode> pages(String path,String field) {return pages(path,field,false);}
        List<JsonNode> pages(String path,String field,boolean englishMetadata) {
            var rows=new ArrayList<JsonNode>();var ids=new java.util.HashSet<String>();Integer expectedTotal=null;
            for(int page=0;page<limits.maxPages();page++) {
                int offset=rows.size();String pagePath=path+"?startAt="+offset+"&maxResults=100";
                JsonNode root;
                if(englishMetadata) {
                    root=parse(reads.readEnglishMetadata(connection.endpoint(pagePath),connection.authorization(),gate));
                    gate.acceptedObjects(1);gate.check();
                } else root=get(pagePath);
                var values=JiraPayload.array(root,field,100);
                Integer start=JiraPayload.offset(root,"startAt"),total=JiraPayload.offset(root,"total");
                if(start==null || start!=offset || total==null || expectedTotal!=null && !total.equals(expectedTotal) || offset+values.size()>total || values.isEmpty() && offset<total) throw JiraPayload.invalidResponse();
                expectedTotal=total;
                for(var value:values) {String id=field.equals("fields")?JiraPayload.text(value,"fieldId",true):JiraReportPayload.identifier(value,"id");if(!ids.add(id)) throw JiraPayload.invalidResponse();rows.add(value);}
                gate.acceptedObjects(values.size());if(rows.size()>limits.maxObjects()) throw JiraTransport.limitExceeded();
                if(rows.size()==total) return List.copyOf(rows);
            }
            throw JiraTransport.limitExceeded();
        }
    }
    private JsonNode parse(byte[] bytes) {try {var node=reader.readTree(bytes);JiraPayload.requireObject(node);return node;}catch(tools.jackson.core.JacksonException failure){throw JiraPayload.invalidResponse();}}
    private static void requireProject(InstanceSource source,RemoteProjectRef project) {if(project==null || !source.instanceId().equals(project.instanceId()) || project.externalProjectId()==null || !JiraPayload.ID.matcher(project.externalProjectId()).matches() || project.projectKey()==null || !JiraPayload.PROJECT_KEY.matcher(project.projectKey()).matches()) throw invalid();}
    private static String projectType(String template) {
        String software="com.pyxis.greenhopper.jira:",business="com.atlassian.jira-core-project-templates:jira-core-simplified-";
        if(template.startsWith(software) && SOFTWARE_TEMPLATES.contains(template.substring(software.length()))) return "software";
        if(template.startsWith(business) && BUSINESS_TEMPLATES.contains(template.substring(business.length()))) return "business";
        throw unsupported();
    }
    private static boolean blank(String value) {return value==null || value.isBlank() || value.codePoints().anyMatch(Character::isISOControl);}
    private static String encode(String value) {return java.net.URLEncoder.encode(value,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");}
    private static TicketException invalid(){return failure(Code.INVALID_CONFIGURATION,"jira.provisioning.invalid");}
    private static TicketException unsupported(){return failure(Code.UNSUPPORTED_PROVIDER,"jira.provisioning.unsupported");}
    private static TicketException failure(Code code,String key){return new TicketException(code,JiraMessages.format(key));}
    @Override public void close(){writes.close();}
}
