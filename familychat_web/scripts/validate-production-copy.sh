#!/usr/bin/env bash
set -euo pipefail

SOURCE_DB="${SOURCE_DB:?Set SOURCE_DB to a disposable database snapshot}"
CANDIDATE_DIR="${CANDIDATE_DIR:?Set CANDIDATE_DIR to the candidate source directory}"
NODE_BIN="${NODE_BIN:-/usr/bin/node}"
NODE_MODULES="${NODE_MODULES:?Set NODE_MODULES to the candidate dependency directory}"
PORT="${PORT:-3199}"

stamp="$(date +%Y%m%d-%H%M%S)-$$"
copy_db="/tmp/familychat-candidate-${stamp}.sqlite"
upload_dir="/tmp/familychat-candidate-uploads-${stamp}"
release_dir="/tmp/familychat-candidate-releases-${stamp}"

sqlite3 "$SOURCE_DB" ".backup '$copy_db'"
mkdir -p "$upload_dir" "$release_dir"

set +e
NODE_PATH="$NODE_MODULES" \
NODE_ENV=production \
JWT_SECRET=candidate-validation-secret-not-for-production-123456 \
DB_PATH="$copy_db" \
UPLOAD_DIR="$upload_dir" \
APP_RELEASE_DIR="$release_dir" \
PORT="$PORT" \
HOST=127.0.0.1 \
timeout 5 "$NODE_BIN" "$CANDIDATE_DIR/server.js"
candidate_exit=$?
set -e

if [[ "$candidate_exit" -ne 124 ]]; then
  echo "Candidate server exited unexpectedly: $candidate_exit" >&2
  exit "$candidate_exit"
fi

migration_count="$(sqlite3 "$copy_db" "SELECT COUNT(*) FROM schema_migrations;")"
foreign_key_violations="$(sqlite3 "$copy_db" "PRAGMA foreign_key_check;" | wc -l)"

echo "candidate_db=$copy_db"
echo "schema_migrations=$migration_count"
echo "foreign_key_violations=$foreign_key_violations"
echo "production copy validation passed"
