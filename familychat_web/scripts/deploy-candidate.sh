#!/usr/bin/env bash
set -euo pipefail

SOURCE_DIR="${1:?Supply the candidate source directory}"
TARGET_DIR="${TARGET_DIR:?Set TARGET_DIR to your deployment directory}"
SERVICE_NAME="${SERVICE_NAME:-family-chat}"
NODE_BIN="${NODE_BIN:-/usr/bin/node}"
NPM_BIN="${NPM_BIN:-/usr/bin/npm}"
stamp="$(date +%Y%m%d-%H%M%S)"
backup_dir="$TARGET_DIR/backups/$stamp"

mkdir -p "$backup_dir" "$TARGET_DIR/lib" "$TARGET_DIR/database" "$TARGET_DIR/routes" "$TARGET_DIR/services" \
  "$TARGET_DIR/repositories" "$TARGET_DIR/websocket" "$TARGET_DIR/scripts" "$TARGET_DIR/manage_public"
for item in server.js package.json package-lock.json lib database routes services repositories websocket scripts manage_public blog_uploads node_modules; do
  [[ -e "$TARGET_DIR/$item" ]] && cp -a "$TARGET_DIR/$item" "$backup_dir/$item"
done

rollback() {
  echo "Deployment failed; restoring code from $backup_dir" >&2
  cp -a "$backup_dir/." "$TARGET_DIR/"
  systemctl restart "$SERVICE_NAME"
}

trap rollback ERR

if [[ -f "$TARGET_DIR/data/chat.sqlite" ]]; then
  sqlite3 "$TARGET_DIR/data/chat.sqlite" ".backup '$backup_dir/chat.sqlite'"
fi

install -m 0644 "$SOURCE_DIR/server.js" "$TARGET_DIR/server.js"
cp -a "$SOURCE_DIR/lib/." "$TARGET_DIR/lib/"
cp -a "$SOURCE_DIR/database/." "$TARGET_DIR/database/"
cp -a "$SOURCE_DIR/routes/." "$TARGET_DIR/routes/"
cp -a "$SOURCE_DIR/services/." "$TARGET_DIR/services/"
cp -a "$SOURCE_DIR/repositories/." "$TARGET_DIR/repositories/"
cp -a "$SOURCE_DIR/websocket/." "$TARGET_DIR/websocket/"
cp -a "$SOURCE_DIR/manage_public/." "$TARGET_DIR/manage_public/"
install -m 0644 "$SOURCE_DIR/package.json" "$TARGET_DIR/package.json"
install -m 0644 "$SOURCE_DIR/package-lock.json" "$TARGET_DIR/package-lock.json"
install -m 0644 "$SOURCE_DIR/DEPLOYMENT_SECURITY_ZH.md" "$TARGET_DIR/DEPLOYMENT_SECURITY_ZH.md"
install -m 0755 "$SOURCE_DIR/scripts/validate-production-copy.sh" "$TARGET_DIR/scripts/validate-production-copy.sh"
install -m 0644 "$SOURCE_DIR/scripts/auth-smoke-test.js" "$TARGET_DIR/scripts/auth-smoke-test.js"
install -m 0644 "$SOURCE_DIR/scripts/security-smoke-test.js" "$TARGET_DIR/scripts/security-smoke-test.js"
install -m 0644 "$SOURCE_DIR/scripts/architecture-smoke-test.js" "$TARGET_DIR/scripts/architecture-smoke-test.js"
install -m 0644 "$SOURCE_DIR/scripts/blog-smoke-test.js" "$TARGET_DIR/scripts/blog-smoke-test.js"
install -m 0644 "$SOURCE_DIR/scripts/check-syntax.js" "$TARGET_DIR/scripts/check-syntax.js"
install -m 0644 "$SOURCE_DIR/scripts/check-manage-script.js" "$TARGET_DIR/scripts/check-manage-script.js"

"$NODE_BIN" --check "$TARGET_DIR/server.js"
find "$TARGET_DIR/lib" "$TARGET_DIR/database" "$TARGET_DIR/routes" "$TARGET_DIR/services" "$TARGET_DIR/repositories" "$TARGET_DIR/websocket" \
  -type f -name '*.js' -print0 | xargs -0 -n1 "$NODE_BIN" --check

systemctl stop "$SERVICE_NAME"
(cd "$TARGET_DIR" && "$NPM_BIN" ci --omit=dev --no-audit --no-fund)

systemctl restart "$SERVICE_NAME"
sleep 3
systemctl is-active --quiet "$SERVICE_NAME"

http_status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --header 'Host: example.com' \
  --header 'X-FamilyChat-Client: android-app' \
  http://127.0.0.1:3000/api/me)"
[[ "$http_status" == "401" ]]

blog_status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
  --header 'Host: example.com' \
  --header 'X-FamilyChat-Client: android-app' \
  http://127.0.0.1:3000/api/blog/status)"
[[ "$blog_status" == "401" ]]

trap - ERR
echo "backup_dir=$backup_dir"
echo "health_http_status=$http_status"
echo "blog_auth_gate_http_status=$blog_status"
echo "deployment passed"
