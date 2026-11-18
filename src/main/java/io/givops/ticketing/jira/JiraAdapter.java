package io.givops.ticketing.jira;

import io.givops.ticketing.model.TicketBrowserModels.*;
import io.givops.ticketing.port.TicketNavigationAdapter;
import io.givops.ticketing.port.TicketBrowseAdapter;
import io.givops.ticketing.port.TicketContentAdapter;
import static io.givops.ticketing.jira.JiraConnection.invalidConfiguration;
import static io.givops.ticketing.jira.JiraPayload.invalidResponse;
import static io.givops.ticketing.jira.JiraTransport.limitExceeded;

import io.givops.ticketing.config.TicketProperties;
import io.givops.ticketing.model.TicketException;
import io.givops.ticketing.model.TicketException.Code;
import io.givops.ticketing.model.TicketModels.InstanceSource;
import io.givops.ticketing.model.TicketModels.IssueIdentity;
import io.givops.ticketing.model.TicketModels.IssueObservation;
import io.givops.ticketing.model.TicketModels.ProjectQuery;
import io.givops.ticketing.model.TicketModels.ProjectResult;
import io.givops.ticketing.model.TicketModels.ProviderInput;
import io.givops.ticketing.model.TicketModels.ProviderSnapshot;
import io.givops.ticketing.model.TicketModels.ReleaseMappingState;
import io.givops.ticketing.model.TicketModels.ReleaseMembership;
import io.givops.ticketing.model.TicketModels.TicketProvider;
import io.givops.ticketing.model.TicketModels.VersionObservation;
import io.givops.ticketing.port.TicketProjectLookupAdapter;
import io.givops.ticketing.port.TicketRequestGate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.json.JsonMapper;

import io.givops.ticketing.model.TicketScopeModels.*;
import io.givops.ticketing.port.TicketScopedProviderAdapter;
import io.givops.ticketing.port.TicketScopedBrowseAdapter;
import io.givops.ticketing.port.TicketRepositoryScopeAdapter;
import io.givops.adapters.CapabilityInspection;

public final class JiraAdapter implements TicketScopedProviderAdapter, TicketScopedBrowseAdapter, TicketRepositoryScopeAdapter, TicketProjectLookupAdapter, TicketNavigationAdapter, TicketBrowseAdapter, TicketContentAdapter {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_PAGE_TOKEN_LENGTH = 8192;
    private static final List<String> ISSUE_FIELDS = List.of("project", "summary", "issuetype", "status",
            "priority", "resolution", "created", "updated", "resolutiondate", "fixVersions", "assignee");

    private final JiraAccess credentials;
    private final TicketProperties limits;
    private final JiraTransport transport;
    private final Clock clock;
    private final ObjectReader json;
    private final ObjectWriter writer;
    private final JiraBrowser browser;
    private final JiraScopeMetadata scopeMetadata;

    JiraAdapter(JiraAccess credentials, TicketProperties limits, JiraTransport transport,
                JsonMapper mapper, Clock clock) {
        this.credentials = credentials;
        this.limits = limits;
        this.transport = transport;
        this.clock = clock;
        this.json = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION);
        this.writer = mapper.writer();
        this.browser = new JiraBrowser(credentials, limits, transport, mapper, clock);
        this.scopeMetadata = new JiraScopeMetadata(credentials, limits, transport, mapper, clock);
    }

    @Override
    public TicketProvider provider() {
        return TicketProvider.JIRA_CLOUD;
    }

    @Override
    public String authenticationContext(InstanceSource source) {
        return captureAccess(source).context();
    }

    @Override
    public Access captureAccess(InstanceSource source) {
        return credentials.capture(source);
    }

    @Override
    public boolean current(InstanceSource source, Access captured, boolean locked) {
        if (captured instanceof JiraAccess.Captured jira) {
            credentials.requireSource(source, jira);
            credentials.requireCurrent(jira, locked);
            return true;
        }
        return false;
    }

    @Override
    public ProjectIdentity lookupProject(InstanceSource source, String projectKey, Access access, TicketRequestGate gate) {
        if (!limits.enabled()) {
            throw new TicketException(Code.DISABLED, JiraMessages.format("ticketing.jiraAdapter.ticketing.access.is.disabled"));
        }
        if (projectKey == null || !JiraPayload.PROJECT_KEY.matcher(projectKey).matches()
                || !(access instanceof JiraAccess.Captured captured)) {
            throw invalidConfiguration();
        }
        gate.check();
        var connection = credentials.connection(source, captured);
        credentials.requireCurrent(captured, false);
        var data = new Read(connection, gate).request("/project/" + projectKey, null);
        var id = JiraPayload.id(data, "id");
        var key = JiraPayload.text(data, "key", true);
        var name = JiraPayload.text(data, "name", true);
        if (!key.equals(projectKey) || name.length() > 200 || name.chars().anyMatch(Character::isISOControl)) {
            throw invalidResponse();
        }
        gate.acceptedObjects(1);
        gate.check();
        credentials.requireCurrent(captured, false);
        return new ProjectIdentity(id, key, name);
    }

    @Override
    public ProviderSnapshot read(ProviderInput input, TicketRequestGate gate) {
        return read(input, gate, null);
    }

    @Override
    public ProviderSnapshot read(ProviderInput input, TicketRequestGate gate, Access captured) {
        if (!limits.enabled()) {
            throw new TicketException(Code.DISABLED, JiraMessages.format("ticketing.jiraAdapter.ticketing.access.is.disabled"));
        }
        gate.check();
        validateQueries(input.projects());
        var connection = credentials.connection(input.source(), captured);
        var read = new Read(connection, gate);
        var projects = new ArrayList<ProjectResult>();
        for (ProjectQuery query : input.projects()) {
            projects.add(read.project(query));
        }
        gate.check();
        return new ProviderSnapshot(projects);
    }

    /** Reads complete fix-version membership for the selected IDs in one Jira project. */
    @Override
    public VersionMemberships readVersionMemberships(InstanceSource source, ProjectQuery query, Access captured,
                                                     TicketRequestGate gate,
                                                     Function<List<VersionObservation>, Set<String>> select,
                                                     int maximumVersions, int maximumIssues) {
        if (!limits.enabled()) {
            throw new TicketException(Code.DISABLED, JiraMessages.format("ticketing.jiraAdapter.ticketing.access.is.disabled"));
        }
        validateQueries(List.of(query));
        var connection = credentials.connection(source, captured);
        var read = new Read(connection, gate);
        String projectId = query.externalProjectId();
        var versions = read.versions("/project/" + projectId, projectId);
        Set<String> selected = Set.copyOf(select.apply(versions));
        if (selected.size() > maximumVersions) throw limitExceeded();
        if (!versions.stream().map(VersionObservation::externalVersionId).collect(Collectors.toSet())
                .containsAll(selected)) {
            throw invalidConfiguration();
        }
        var members = new LinkedHashMap<String, List<IssueObservation>>();
        var unique = new Issues();
        var memberships = new HashMap<IssueIdentity, Set<String>>();
        int remaining = Math.min(maximumIssues, limits.maxReleaseIssues());
        for (String id : selected.stream().sorted().toList()) {
            var issues = read.search(projectId, "fixVersion = " + id, id, remaining);
            remaining -= issues.size();
            for (var issue : issues) {
                unique.add(issue, true);
                memberships.computeIfAbsent(issue.identity(), ignored -> new HashSet<>()).add(id);
            }
            members.put(id, issues);
        }
        for (var issues : members.values()) {
            for (var issue : issues) {
                Set<String> expected = issue.releaseVersionIds().stream().filter(selected::contains)
                        .collect(Collectors.toSet());
                if (!expected.equals(memberships.get(issue.identity()))) throw invalidResponse();
            }
        }
        gate.check();
        return new VersionMemberships(members);
    }

    @Override public ScopedSnapshot readScoped(ProviderInput input, Scope scope, TicketRequestGate gate, Access access) {
        JiraScope.require(scope);
        if (!limits.enabled()) throw new TicketException(Code.DISABLED, JiraMessages.format("ticketing.jiraAdapter.ticketing.access.is.disabled"));
        if (input.projects().size() != 1) throw invalidConfiguration();
        validateQueries(input.projects()); gate.check();
        var read = new Read(credentials.connection(input.source(), access), gate, scope);
        var result = new ProviderSnapshot(List.of(read.project(input.projects().getFirst())));
        gate.check();
        if (!current(input.source(), access, false)) throw invalidConfiguration();
        return new ScopedSnapshot(scope, result);
    }
    @Override public ScopedMemberships readVersionMembershipsScoped(InstanceSource source, ProjectQuery query, Scope scope,
            Access access, TicketRequestGate gate, Function<List<VersionObservation>, Set<String>> select,
            int maximumVersions, int maximumIssues) {
        JiraScope.require(scope); validateQueries(List.of(query)); gate.check();
        if (!limits.enabled()) throw new TicketException(Code.DISABLED, JiraMessages.format("ticketing.jiraAdapter.ticketing.access.is.disabled"));
        var read = new Read(credentials.connection(source, access), gate, scope);
        var versions = read.versions("/project/" + query.externalProjectId(), query.externalProjectId());
        var selected = Set.copyOf(select.apply(versions));
        if (selected.size() > maximumVersions) throw limitExceeded();
        if (!versions.stream().map(VersionObservation::externalVersionId).collect(Collectors.toSet()).containsAll(selected)) throw invalidConfiguration();
        var members = new LinkedHashMap<String, List<IssueObservation>>();
        var unique = new Issues();
        var memberships = new HashMap<IssueIdentity, Set<String>>();
        int remaining = Math.min(maximumIssues, limits.maxReleaseIssues());
        for (var id : selected.stream().sorted().toList()) {
            var issues = read.search(query.externalProjectId(), "fixVersion = " + id, id, remaining);
            remaining -= issues.size();
            for (var issue : issues) {
                unique.add(issue, true);
                memberships.computeIfAbsent(issue.identity(), ignored -> new HashSet<>()).add(id);
            }
            members.put(id, issues);
        }
        for (var issues : members.values()) for (var issue : issues) {
            var expected = issue.releaseVersionIds().stream().filter(selected::contains).collect(Collectors.toSet());
            if (!expected.equals(memberships.get(issue.identity()))) throw invalidResponse();
        }
        gate.check();
        if (!current(source, access, false)) throw invalidConfiguration();
        return new ScopedMemberships(scope, new VersionMemberships(members));
    }
    @Override public Fields fields(InstanceSource source, String project, Access access, TicketRequestGate gate) {
        return scopeMetadata.fields(source, project, access, gate);
    }
    @Override public Values values(InstanceSource source, String project, String field, Access access, TicketRequestGate gate) {
        return scopeMetadata.values(source, project, field, access, gate);
    }
    @Override public CapabilityInspection validateScope(InstanceSource source, String project, Scope scope, Access access, TicketRequestGate gate) {
        return scopeMetadata.validate(source, project, scope, access, gate);
    }

    private void validateQueries(List<ProjectQuery> projects) {
        if (projects.size() > limits.maxBindings()) {
            throw limitExceeded();
        }
        long keyCount = 0;
        var projectIds = new HashSet<String>();
        var bindingIds = new HashSet<UUID>();
        for (ProjectQuery query : projects) {
            if (query.ticketProjectId() == null || query.externalProjectId() == null || query.projectKey() == null
                    || !JiraPayload.ID.matcher(query.externalProjectId()).matches()
                    || !JiraPayload.PROJECT_KEY.matcher(query.projectKey()).matches()
                    || !projectIds.add(query.externalProjectId()) || !bindingIds.add(query.ticketProjectId())) {
                throw invalidConfiguration();
            }
            keyCount += query.issueKeys().size();
            if (keyCount > limits.maxIssueKeys()) {
                throw limitExceeded();
            }
            String prefix = query.projectKey() + "-";
            for (String key : query.issueKeys()) {
                if (!key.startsWith(prefix) || !JiraPayload.ISSUE_KEY.matcher(key).matches()) {
                    throw invalidConfiguration();
                }
            }
        }
    }

    private final class Read {
        private final JiraConnection connection;
        private final TicketRequestGate gate;
        private final String authorization;
        private final Scope scope;
        private Set<String> eligibleVersions = Set.of();
        private int remainingReleaseIssues = limits.maxReleaseIssues();
        private int remainingAssignedIssues = limits.maxAssignedIssues();

        private Read(JiraConnection connection, TicketRequestGate gate) {
            this(connection, gate, null);
        }

        private Read(JiraConnection connection, TicketRequestGate gate, Scope scope) {
            this.scope = scope;
            this.connection = connection;
            this.gate = gate;
            this.authorization = connection.authorization();
        }

        private ProjectResult project(ProjectQuery query) {
            String projectId = query.externalProjectId();
            String path = "/project/" + projectId;
            var project = JiraPayload.project(request(path, null), projectId, connection, clock.instant());
            gate.acceptedObjects(1);
            List<VersionObservation> versions = versions(path, projectId);

            var unresolved = new LinkedHashSet<>(query.issueKeys());
            var issues = new Issues();
            List<String> keys = query.issueKeys().stream().sorted().toList();
            for (int start = 0; start < keys.size(); start += PAGE_SIZE) {
                List<String> batch = keys.subList(start, Math.min(start + PAGE_SIZE, keys.size()));
                String quoted = batch.stream().map(key -> "\"" + key + "\"").collect(Collectors.joining(", "));
                var expected = Set.copyOf(batch);
                var matches = search(projectId, "key in (" + quoted + ")", null, limits.maxObjects());
                for (IssueObservation issue : matches) {
                    // A returned current key does not prove which requested alias produced the hit.
                    if (expected.contains(issue.issueKey())) {
                        issues.add(issue, false);
                        unresolved.remove(issue.issueKey());
                    }
                }
            }
            ReleaseMembership release = release(query, versions, issues);
            var assigned = search(projectId, "assignee IS NOT EMPTY AND statusCategory != Done", null, remainingAssignedIssues);
            remainingAssignedIssues -= assigned.size();
            for (var issue : assigned) {
                if (issue.assigneeAccountId() == null || "done".equalsIgnoreCase(issue.statusCategory())) {
                    throw invalidResponse();
                }
                issues.add(issue, true);
            }
            var values = issues.values();
            values.forEach(issue -> unresolved.remove(issue.issueKey()));
            return new ProjectResult(query.ticketProjectId(), project, versions, values, unresolved, release,
                    assigned.stream().map(IssueObservation::identity).toList());
        }

        private List<VersionObservation> versions(String projectPath, String projectId) {
            var versions = new ArrayList<VersionObservation>();
            var ids = new HashSet<String>();
            Integer expectedTotal = null;
            int startAt = 0;
            for (int page = 0; page < limits.maxPages(); page++) {
                JsonNode root = request(projectPath + "/version?startAt=" + startAt + "&maxResults=" + PAGE_SIZE, null);
                JsonNode rows = JiraPayload.array(root, "values", PAGE_SIZE);
                Integer reportedStart = JiraPayload.offset(root, "startAt");
                Integer maxResults = JiraPayload.offset(root, "maxResults");
                Integer total = JiraPayload.offset(root, "total");
                Boolean last = JiraPayload.flag(root, "isLast");
                int nextOffset = startAt + rows.size();
                if (!Objects.equals(reportedStart, startAt)
                        || maxResults != null && (maxResults < rows.size() || maxResults > PAGE_SIZE)
                        || total != null && (nextOffset > total || expectedTotal != null && !total.equals(expectedTotal))) {
                    throw invalidResponse();
                }
                if (total != null) {
                    expectedTotal = total;
                }
                if (expectedTotal != null) {
                    boolean atEnd = nextOffset == expectedTotal;
                    if (nextOffset > expectedTotal || last != null && last != atEnd) {
                        throw invalidResponse();
                    }
                    last = atEnd;
                }
                if (last == null || !last && rows.isEmpty()) {
                    throw invalidResponse();
                }
                gate.acceptedObjects(rows.size());
                Instant observedAt = clock.instant();
                for (JsonNode row : rows) {
                    VersionObservation version = JiraPayload.version(row, projectId, observedAt);
                    if (!ids.add(version.externalVersionId())) {
                        throw invalidResponse();
                    }
                    version = JiraScope.version(version, scope);
                    if (version != null) versions.add(version);
                }
                startAt = nextOffset;
                if (last) {
                    eligibleVersions = versions.stream().map(VersionObservation::externalVersionId).collect(Collectors.toSet());
                    return List.copyOf(versions);
                }
            }
            throw limitExceeded();
        }

        private ReleaseMembership release(ProjectQuery query, List<VersionObservation> versions, Issues issues) {
            String name = query.releaseVersionName();
            if (name == null) {
                return new ReleaseMembership(ReleaseMappingState.NOT_REQUESTED, null, List.of(), null);
            }
            var matches = versions.stream().filter(version -> version.canonicalName().equals(name)).limit(2).toList();
            if (matches.size() != 1) {
                var state = matches.isEmpty() ? ReleaseMappingState.NOT_FOUND : ReleaseMappingState.AMBIGUOUS;
                return new ReleaseMembership(state, null, List.of(), clock.instant());
            }
            String versionId = matches.getFirst().externalVersionId();
            var members = search(query.externalProjectId(), "fixVersion = " + versionId, versionId, remainingReleaseIssues);
            remainingReleaseIssues -= members.size();
            var identities = new ArrayList<IssueIdentity>();
            for (IssueObservation member : members) {
                issues.add(member, true);
                identities.add(member.identity());
            }
            return new ReleaseMembership(ReleaseMappingState.MATCHED, versionId, identities, clock.instant());
        }

        private List<IssueObservation> search(String projectId, String predicate, String versionId, int maximumIssues) {
            String jql = "project = " + projectId + " AND " + (scope == null ? predicate : JiraScope.predicate(scope) + " AND (" + predicate + ")") + " ORDER BY key ASC";
            var results = new Issues();
            var tokens = new HashSet<String>();
            String token = null;
            int count = 0;
            for (int page = 0; page < limits.maxPages(); page++) {
                int pageSize = Math.min(PAGE_SIZE, maximumIssues - count + 1);
                var body = new LinkedHashMap<String, Object>();
                body.put("jql", jql);
                body.put("fields", JiraScope.fields(ISSUE_FIELDS, scope));
                body.put("maxResults", pageSize);
                if (token != null) {
                    body.put("nextPageToken", token);
                }
                JsonNode root = request("/search/jql", searchBody(body));
                JsonNode rows = JiraPayload.array(root, "issues", pageSize);
                count += rows.size();
                if (count > maximumIssues) {
                    throw limitExceeded();
                }
                gate.acceptedObjects(rows.size());
                Instant observedAt = clock.instant();
                for (JsonNode row : rows) {
                    if (!JiraScope.matches(row, scope)) continue;
                    IssueObservation issue = JiraPayload.issue(row, projectId, connection, observedAt);
                    if (scope != null) issue = JiraScope.issue(issue, eligibleVersions);
                    if (versionId != null && !issue.releaseVersionIds().contains(versionId)) {
                        throw invalidResponse();
                    }
                    results.add(issue, false);
                }
                Boolean last = JiraPayload.flag(root, "isLast");
                token = JiraPayload.text(root, "nextPageToken", false);
                if (Boolean.TRUE.equals(last) && token == null) {
                    return results.values();
                }
                if (Boolean.TRUE.equals(last) || token == null || token.isBlank()
                        || token.length() > MAX_PAGE_TOKEN_LENGTH || rows.isEmpty() || !tokens.add(token)) {
                    throw invalidResponse();
                }
            }
            throw limitExceeded();
        }

        private JsonNode request(String path, byte[] searchBody) {
            byte[] response = transport.read(connection.endpoint(path), authorization, searchBody, gate);
            try {
                JsonNode root = json.readTree(response);
                JiraPayload.requireObject(root);
                return root;
            } catch (JacksonException exception) {
                throw invalidResponse();
            }
        }
    }

    private byte[] searchBody(Map<String, Object> body) {
        try {
            return writer.writeValueAsBytes(body);
        } catch (JacksonException exception) {
            throw invalidConfiguration();
        }
    }

    private static final class Issues {
        private final Map<IssueIdentity, IssueObservation> byId = new LinkedHashMap<>();
        private final Map<String, IssueIdentity> byKey = new HashMap<>();

        private void add(IssueObservation issue, boolean allowExisting) {
            IssueIdentity identity = byKey.putIfAbsent(issue.issueKey(), issue.identity());
            IssueObservation previous = byId.put(issue.identity(), issue);
            if (identity != null && !identity.equals(issue.identity())
                    || previous != null && (!allowExisting || !previous.issueKey().equals(issue.issueKey()))) {
                throw invalidResponse();
            }
        }

        private List<IssueObservation> values() {
            return List.copyOf(byId.values());
        }
    }
    /** Provider-space navigation is available independently of rich content; global IDs cannot infer issue numbers. */
    @Override public Navigation navigate(BrowserRequest request, TicketRef ticket, Access access, TicketRequestGate gate) {
        gate.check();
        validateQueries(List.of(new ProjectQuery(request.ticketProjectId(), request.externalProjectId(), request.projectKey(), Set.of(), null)));
        if (!current(request.source(), access, false)) throw new TicketException(Code.SUPERSEDED, JiraMessages.format("jira.credentials.changed"));
        return new Navigation(((JiraAccess.Captured) access).connection().browse(request.projectKey()), null);
    }
    @Override public BrowseDescriptor describe(BrowserRequest request, Access access, TicketRequestGate gate) {
        return browser.describe(request, access, gate);
    }
    @Override public TicketPage list(BrowserRequest request, BrowseQuery query, Access access, TicketRequestGate gate) {
        return browser.list(request, query, access, gate);
    }
    @Override public TicketDetail detail(BrowserRequest request, TicketRef ticket, Access access, TicketRequestGate gate) {
        return browser.detail(request, ticket, access, gate);
    }
    @Override public TicketContent content(BrowserRequest request, TicketRef ticket, Access access, TicketRequestGate gate) {
        return browser.content(request, ticket, access, gate);
    }
    @Override public BrowseDescriptor describeScoped(BrowserRequest request, Scope scope, Access access, TicketRequestGate gate) {
        JiraScope.require(scope);
        return browser.describe(request, scope, access, gate);
    }
    @Override public TicketPage listScoped(BrowserRequest request, Scope scope, BrowseQuery query, Access access, TicketRequestGate gate) {
        JiraScope.require(scope);
        return browser.list(request, scope, query, access, gate);
    }
    @Override public TicketDetail detailScoped(BrowserRequest request, Scope scope, TicketRef ticket, Access access, TicketRequestGate gate) {
        JiraScope.require(scope);
        return browser.detail(request, scope, ticket, access, gate);
    }
    @Override public TicketContent contentScoped(BrowserRequest request, Scope scope, TicketRef ticket, Access access, TicketRequestGate gate) {
        JiraScope.require(scope);
        return browser.content(request, scope, ticket, access, gate);
    }
    @Override public Navigation navigateScoped(BrowserRequest request, Scope scope, TicketRef ticket, Access access, TicketRequestGate gate) {
        JiraScope.require(scope);
        if (ticket != null) return browser.detail(request, scope, ticket, access, gate).navigation();
        browser.describe(request, scope, access, gate); return navigate(request, ticket, access, gate);
    }
}
