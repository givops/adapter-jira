# Contributing

Use Java 25, Maven 3.9+, Git and Bash, then run `./scripts/verify.sh`. This repository and the pinned public adapter SDK contain everything needed to build and test. Python 3 is required only for release ZIP packaging.

Keep changes focused and add tests for changed behavior. Provider HTTP tests must use synthetic local fixtures; never commit account data, tokens, signing secrets or production responses. Preserve transport bounds, destination validation, cancellation, credential freshness, sanitized failures and the rule that uncertain mutations are not automatically replayed.

Put user-facing English messages in the artifact's YAML catalog and use `JiraMessages`; keep API keys, JQL and other protocol values untranslated. Do not depend on private host implementations. Propose public contract changes in the SDK before using them here, and update the full commit in `sdk.ref` only after the SDK revision is reviewed and available publicly.

Run full verification on the final source. Include the behavior change, relevant test results and any compatibility implications in pull requests. Run `./scripts/verify-local.sh` before merging to verify the pinned SDK and adapter, test packaging, and package the JAR with license and commit/checksum metadata on a clean commit. Release publication is maintained by repository owners; local verification does not publish anything.

Contributions are licensed under the repository's Apache 2.0 license.
