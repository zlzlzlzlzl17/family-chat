#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:3199}"
CLIENT_HEADER="X-FamilyChat-Client: android-app"

expect_status() {
  local expected="$1"
  local path="$2"
  local actual
  actual="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --header "$CLIENT_HEADER" "$BASE_URL$path")"
  if [[ "$actual" != "$expected" ]]; then
    echo "Expected HTTP $expected for $path, got $actual" >&2
    exit 1
  fi
}

expect_status 401 /api/me
expect_status 401 /api/app_release

echo "staging HTTP smoke test passed"
