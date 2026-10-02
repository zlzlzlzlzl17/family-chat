#!/usr/bin/env bash
set -euo pipefail

drop_in_dir="/etc/systemd/system/family-chat.service.d"
drop_in_file="$drop_in_dir/security.conf"
environment_file="${ENV_FILE:?Set ENV_FILE to your service environment file}"
backup_dir="$(dirname "$environment_file")/backups/config-$(date +%Y%m%d-%H%M%S)"
secret="$(openssl rand -hex 32)"
temporary_file="$(mktemp)"
temporary_env="$(mktemp)"
trap 'rm -f "$temporary_file" "$temporary_env"' EXIT

umask 077
printf '[Service]\nEnvironment="JWT_SECRET=%s"\n' "$secret" > "$temporary_file"
install -d -m 0755 "$drop_in_dir"
install -m 0600 "$temporary_file" "$drop_in_file"

install -d -m 0700 "$backup_dir"
cp -a "$environment_file" "$backup_dir/.env.family-chat"
awk -v secret="$secret" '
  BEGIN { replaced = 0 }
  /^JWT_SECRET=/ {
    if (!replaced) print "JWT_SECRET=" secret
    replaced = 1
    next
  }
  { print }
  END { if (!replaced) print "JWT_SECRET=" secret }
' "$environment_file" > "$temporary_env"
install -m 0600 "$temporary_env" "$environment_file"
systemctl daemon-reload

echo "jwt_secret_rotated=true"
echo "jwt_secret_length=${#secret}"
echo "drop_in=$drop_in_file"
echo "environment_file=$environment_file"
echo "backup_dir=$backup_dir"
