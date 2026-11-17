# GivOps Jira Cloud adapter

An independently built Jira Cloud library for the public [GivOps adapter SDK](https://github.com/givops/adapter-sdk), licensed under [Apache 2.0](LICENSE).

Implemented operations include project metadata, versions, issue reads, assigned issues, complete version membership, configurable test-report metadata and datasets, issue history, release version lookup/creation, and report duplicate detection/creation with text-only ADF. The adapter does not register the generic ticket-write port.

Provider HTTP calls retain bounded requests and responses, origin validation, cancellation, pagination validation and sanitized failures. Mutating operations are never automatically replayed after an uncertain result.

## Build and test

Install Java 25, Maven 3.9 or later, Git and Bash. From a clean checkout:

```sh
./scripts/verify.sh
```

The script checks out the exact public SDK commit in `sdk.ref` into a temporary directory, installs its artifacts locally, and runs this library's full clean verification. It needs public GitHub and Maven repository access; it needs no private GivOps source, database, credentials or live Jira account. Tests use local HTTP/HTTPS fixtures and the JDK keytool to create temporary synthetic TLS keys.

For SDK development, use a clean committed public SDK checkout:

```sh
./scripts/verify.sh --sdk-source /path/to/adapter-sdk
```

The override is cloned before building, so its working tree and outputs remain untouched. Once the SDK is installed, `mvn verify` runs the adapter suite directly. Python 3 is needed only to create the downloadable bundle:

```sh
python3 scripts/package-release.py
```

Packaging requires a clean committed adapter checkout and records both source and pinned SDK commit identities. Use the pinned default verification before packaging a release. CI follows this order and attaches a ZIP to published GitHub releases. Artifacts are not currently published to Maven Central.

## Install or replace

This is an ordinary library JAR, not an executable Spring Boot application. A compatible GivOps host supplies Java 25, Spring Boot 4.0.9, Jackson 3 and SDK API **0.3**. Before 1.0, a different SDK minor version is incompatible. Spring Boot discovers the adapter through `AutoConfiguration.imports`, outside the application's component scan; the host supplies credentials, signing, limits, clock and JSON services.

Download and extract a release ZIP, or use `target/adapter-jira-0.3.0-SNAPSHOT.jar` after building. Put exactly one Jira adapter JAR in the host's `/opt/givops/adapters` directory. A read-only directory bind mount replaces the complete bundled adapter set, so include every provider you intend to retain. Replace the prior Jira JAR, then restart the same core image. Do not keep two versions together. The host rejects duplicate provider/artifact registrations and incompatible SDK metadata before use. No core rebuild or live reload is required.

## Credentials and API compatibility

The host owns credential storage and signing secrets. The adapter accepts `email` and `apiToken`, plus optional `cloudId` for scoped tokens, through a destination-pinned credential lease. It revalidates freshness through the host before publication and release mutations. Runtime fallback configuration retains the `givops.ticketing.jira.instances` properties; managed credentials use the same public validation contract. Tokens and lease details are never part of diagnostic `toString` values.

The API profile is `jira-cloud-rest-v3`. No `serverInfo` request is required. Installed capabilities describe implemented operations; they do not assert a user's permissions or operation availability. API failures retain the distinctions between authorization, missing data, rate limits and uncertain writes. Jira Cloud product version strings are not feature gates; see [Atlassian's version guidance](https://support.atlassian.com/jira/kb/how-to-check-your-jira-cloud-version/).

English display messages are owned by this artifact at `io/givops/adapters/jira/i18n/en.yml` and formatted through the SDK's MF1 engine. Provider queries, URLs, identifiers and protocol payload values remain code constants.

### SDK API 0.3 compatibility

This source requires the exact public SDK API 0.3 and artifact version
0.3.0-SNAPSHOT. Observation acquisition is metadata-only: issue descriptions and
comments are not mapped. Existing creation/write descriptions retain their existing behavior. The adapter registers `ticketing.browse` and `ticketing.content`;
assets remain unavailable. The `ticketing.navigation` capability supplies provider-owned project
space links without a content fetch. A global ticket ID does not imply a local
issue number, so an unknown ticket link remains absent.

The SDK source pin selects public commit
[`ad5dc81e0378d31a8cb4e80a531dee559adea648`](https://github.com/givops/adapter-sdk/commit/ad5dc81e0378d31a8cb4e80a531dee559adea648),
which supplies API 0.3. The repository's default build uses that public SDK source;
no unpublished SDK checkout is required. Browser support is available on the
`task/provider-browser` source branch. The artifact remains a development snapshot.


### Ticket browser

The browser uses the existing `jira-cloud-rest-v3` profile and destination-pinned
email/token credential lease, including the optional scoped-token `cloudId`.
Jira Server and Data Center profiles are outside this adapter's current support
boundary; the browser does not add authentication modes or context-path support.

Listing uses the Cloud [enhanced JQL search API](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issue-search/)
with explicit metadata fields. Detail and content use the
[issue API](https://developer.atlassian.com/cloud/jira/platform/rest/v3/api-group-issues/)
by stable numeric issue ID, with response project and issue identity validation.
List/detail requests never request descriptions or comments. The separate content
request fetches only project identity and description; its result is transient and
must never enter observation persistence.

Supported filters are native status names, issue type names (`categories`) and
labels. Families combine with AND; values within each family combine with OR.
Text search uses an escaped phrase against Jira's summary search, plus an exact
key match when the input is an issue key. Jira's own indexing, tokenization and
stemming rules apply; this is not a literal substring search and raw JQL is never
accepted. Sorts are key ascending and updated descending with key ascending as a
tie breaker. Only ACTIVE lifecycle is advertised: it denotes live issues and
includes completed issues. Jira's `new` and `indeterminate` status categories map
to OPEN and IN_PROGRESS; other categories retain UNKNOWN because native workflow
completion does not reliably distinguish success from cancellation.

Cloud pagination tokens are wrapped with query and source/access scope fingerprints
and validated before another request. Encoded continuations are limited to 4096
characters. There is no exact total or immutable snapshot: the scoped `jira-live`
revision is mutable, and issues may change or move between pages. Content reads
use the same revision scope and revalidate current credentials throughout the read.

Descriptions are projected from [Atlassian Document Format](https://developer.atlassian.com/cloud/jira/platform/apis/document/structure/)
to bounded plain text, preserving known text containers, line breaks, mention text
and code text. Legacy string descriptions and absent descriptions are supported.
Marks, link targets, embedded media, cards and extension payloads are omitted;
no remote assets are fetched and no assets or link targets are advertised. Literal
HTML remains plain text. ADF traversal has explicit depth, node and UTF-8 output
bounds in addition to the existing HTTP response and operation budgets.

The browser HTTP tests use synthetic local TLS fixtures and require no live Jira
credentials. They cover query construction, scoped cursors, identity checks,
credential invalidation, cancellation and content conversion/resource limits.
