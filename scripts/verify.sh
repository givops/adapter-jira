#!/usr/bin/env bash
set -euo pipefail
project_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
sdk_source=
if [[ $# -gt 0 ]]; then
  if [[ $# -ne 2 || $1 != --sdk-source ]]; then
    echo 'Usage: scripts/verify.sh [--sdk-source /path/to/committed/adapter-sdk]' >&2
    exit 2
  fi
  sdk_source=$2
fi
build_root=$(mktemp -d "${TMPDIR:-/tmp}/givops-jira-sdk.XXXXXXXX")
trap 'rm -rf -- "$build_root"' EXIT
if [[ -n $sdk_source ]]; then
  sdk_ref=$(git -C "$sdk_source" rev-parse --verify HEAD)
  if [[ -n $(git -C "$sdk_source" status --porcelain) ]]; then
    echo 'The SDK source override must be a clean committed checkout.' >&2
    exit 2
  fi
  git clone --quiet --no-hardlinks --no-checkout -- "$sdk_source" "$build_root/sdk"
else
  sdk_ref=$(cat "$project_root/sdk.ref")
  git clone --quiet --filter=blob:none --no-checkout https://github.com/givops/adapter-sdk.git "$build_root/sdk"
fi
if [[ ! $sdk_ref =~ ^[0-9a-f]{40}$ ]]; then
  echo 'The SDK revision must be a full immutable Git commit.' >&2
  exit 2
fi
git -C "$build_root/sdk" checkout --quiet --detach "$sdk_ref"
[[ $(git -C "$build_root/sdk" rev-parse HEAD) == "$sdk_ref" ]]
mvn --batch-mode --no-transfer-progress -f "$build_root/sdk/pom.xml" clean install
mvn --batch-mode --no-transfer-progress -f "$project_root/pom.xml" clean verify
printf '%s\n' "$sdk_ref" > "$project_root/target/verified-sdk.ref"
git -C "$project_root" rev-parse HEAD > "$project_root/target/verified-source.ref"
sha256sum "$project_root"/target/adapter-jira-*.jar | cut -d ' ' -f 1 > "$project_root/target/verified-jar.sha256"
printf 'Verified Jira against SDK commit %s\n' "$sdk_ref"
