#!/usr/bin/env bash
set -euo pipefail
verification_log=$(mktemp)
trap 'rm -f "$verification_log"' EXIT
run_verification() {
  repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
  cd "$repo_root"
  if [[ $# -gt 0 && ( $# != 2 || $1 != --sdk-source ) ]]; then
    echo 'Usage: scripts/verify-local.sh [--sdk-source /path/to/committed/adapter-sdk]' >&2
    exit 2
  fi
  scripts/verify.sh "$@"
  python3 -m unittest discover -s scripts -p 'test_*.py' -v
  # Release receipts intentionally require committed inputs.
  if [[ -z $(git status --porcelain) ]]; then
    python3 scripts/package-release.py
  else
    echo 'Working-tree verification passed; rerun on the clean final commit to verify release packaging.'
  fi
  git diff --check
}
run_verification "$@" 2>&1 | tee "$verification_log"
if grep -Eq 'Tests (are )?skipped|Tests run:.*Skipped: [1-9][0-9]*|OK \(skipped=[1-9]' "$verification_log"; then
  echo 'Local verification requires every test to run; skipped tests were reported.' >&2
  exit 1
fi
