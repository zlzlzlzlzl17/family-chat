param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$Remote = $env:FAMILYCHAT_SSH_REMOTE,
    [string]$RemoteDir = $env:FAMILYCHAT_REMOTE_DIR,
    [string]$RemoteNode = "/usr/bin/node",
    [string]$RemoteNpm = "/usr/bin/npm",
    [string]$IdentityFile = $env:FAMILYCHAT_SSH_KEY,
    [string[]]$SshOptions = @("-o", "BatchMode=yes", "-o", "ConnectTimeout=30", "-o", "ServerAliveInterval=10")
)

$ErrorActionPreference = "Stop"

if (-not $Remote -or -not $RemoteDir -or -not $IdentityFile) {
    throw "Set Remote, RemoteDir and IdentityFile (or FAMILYCHAT_SSH_REMOTE, FAMILYCHAT_REMOTE_DIR and FAMILYCHAT_SSH_KEY)."
}
if (-not (Test-Path -LiteralPath $IdentityFile -PathType Leaf)) { throw "SSH key file not found." }

$SshOptions = @("-i", $IdentityFile) + $SshOptions

# ---------------------------------------------------------------------------
# What actually makes up the deployable server.
#
# The previous version of this script packaged only server.js, the package
# files, manage_public and public. Because the remote step overlays rather than
# syncs, that did not erase the module directories - it overwrote server.js and
# left lib/, routes/, services/ etc at their old revision, producing silent
# version skew that is far harder to diagnose than an outright failure.
#
# Sync strategy differs per path group and is deliberate:
#   CodeDirs   - pure code, fully owned by the repo, mirrored with --delete so
#                files removed from the repo also disappear from production.
#   AssetDirs  - overlaid WITHOUT --delete. Production holds operator files the
#                repo does not (e.g. manage_public/index.html.bak-*), and this
#                script must not silently delete them.
#   ScriptDirs - overlaid. Carries check-syntax.js, which the remote stage uses
#                to verify the deployed tree is complete.
#
# Nothing here ever touches data/, uploads/, app_release/, backups/ or
# .env.family-chat.
# ---------------------------------------------------------------------------
$CodeDirs   = @("lib", "database", "repositories", "routes", "services", "websocket")
$AssetDirs  = @("manage_public", "public")
$ScriptDirs = @("scripts")
$RootFiles  = @("server.js", "package.json", "package-lock.json")

$webDir = Join-Path $ProjectRoot "familychat_web"

# --- Pre-flight: refuse to build an incomplete package -----------------------
$missing = @()
foreach ($dir in ($CodeDirs + $AssetDirs + $ScriptDirs)) {
    if (-not (Test-Path (Join-Path $webDir $dir) -PathType Container)) { $missing += "$dir/" }
}
foreach ($file in $RootFiles) {
    if (-not (Test-Path (Join-Path $webDir $file) -PathType Leaf)) { $missing += $file }
}
if ($missing.Count -gt 0) {
    throw "refusing to deploy, missing required paths in ${webDir}: $($missing -join ', ')"
}

# --- Local gate --------------------------------------------------------------
# Full test suite, not just a syntax check. `npm run check` alone used to parse
# server.js only, so a broken route module passed the gate and failed at
# systemctl restart.
$nodeWrapper = Join-Path $ProjectRoot ".tools\with-node.cmd"
if (Test-Path $nodeWrapper) {
    & $nodeWrapper npm --prefix $webDir test
} else {
    npm --prefix $webDir test
}
if ($LASTEXITCODE -ne 0) { throw "local web test suite failed with exit code $LASTEXITCODE" }

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$stage = Join-Path ([System.IO.Path]::GetTempPath()) "familychat_web_deploy_$stamp"
$archive = Join-Path ([System.IO.Path]::GetTempPath()) "familychat_web_deploy_$stamp.tar.gz"
$remoteArchive = "/tmp/familychat_web_deploy_$stamp.tar.gz"
$remoteScriptName = "familychat_web_deploy_$stamp.sh"
$remoteScriptPath = "/tmp/$remoteScriptName"
$localRemoteScript = Join-Path ([System.IO.Path]::GetTempPath()) $remoteScriptName

if (Test-Path $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
if (Test-Path $archive) { Remove-Item -LiteralPath $archive -Force }
New-Item -ItemType Directory -Path $stage | Out-Null

foreach ($item in ($RootFiles + $CodeDirs + $AssetDirs + $ScriptDirs)) {
    $source = Join-Path $webDir $item
    Copy-Item -Path $source -Destination $stage -Recurse -Force
}

# Verify the staged tree before shipping it.
foreach ($dir in $CodeDirs) {
    if (-not (Test-Path (Join-Path $stage $dir) -PathType Container)) {
        throw "staging failed, $dir/ did not reach the package"
    }
}

tar.exe -czf $archive -C $stage .
if ($LASTEXITCODE -ne 0) { throw "web archive creation failed with exit code $LASTEXITCODE" }

$codeDirList = $CodeDirs -join " "
$assetDirList = ($AssetDirs + $ScriptDirs) -join " "
$rootFileList = $RootFiles -join " "

$remoteScript = @"
set -euo pipefail
REMOTE_DIR='$RemoteDir'
REMOTE_NODE='$RemoteNode'
REMOTE_NPM='$RemoteNpm'
STAMP='$stamp'
ARCHIVE='$remoteArchive'
CODE_DIRS="$codeDirList"
ASSET_DIRS="$assetDirList"
ROOT_FILES="$rootFileList"
STAGE="/tmp/familychat_web_deploy_`$STAMP"
BACKUP_DIR="`$REMOTE_DIR/backups/deploy"
BACKUP_FILE="`$BACKUP_DIR/family-chat-web-`$STAMP.tar.gz"
NODE_BIN="`$(dirname "`$REMOTE_NODE")"
export PATH="`$NODE_BIN:`$PATH"

mkdir -p "`$STAGE" "`$BACKUP_DIR"
rm -rf "`$STAGE"/*
tar -xzf "`$ARCHIVE" -C "`$STAGE"

cd "`$REMOTE_DIR"

# Back up every path this deploy can modify. The old backup covered only the
# five packaged items, so restoring from it left an inconsistent tree.
BACKUP_PATHS=""
for p in `$ROOT_FILES `$CODE_DIRS `$ASSET_DIRS; do
  if [ -e "`$p" ]; then BACKUP_PATHS="`$BACKUP_PATHS `$p"; fi
done
if [ -n "`$BACKUP_PATHS" ]; then
  tar -czf "`$BACKUP_FILE" `$BACKUP_PATHS
  echo "backup written: `$BACKUP_FILE"
fi

restore_backup() {
  if [ -f "`$BACKUP_FILE" ]; then
    echo "!! deploy failed, restoring previous revision from `$BACKUP_FILE"
    tar -xzf "`$BACKUP_FILE" -C "`$REMOTE_DIR"
    systemctl restart family-chat || true
    echo "!! rollback complete"
  else
    echo "!! deploy failed and no backup was available"
  fi
}

rm -f server.js.save public/icon-192.png.bak public/icon-512.png.bak

# --checksum compares file contents instead of rsync's default size+mtime quick
# check. Without it a changed file that happens to keep the same size and mtime
# is silently skipped, producing a deploy that reports success while shipping
# nothing. The payload here is a few hundred KB, so hashing costs nothing.

# Code directories are mirrored: --delete removes files dropped from the repo.
for d in `$CODE_DIRS; do
  mkdir -p "`$REMOTE_DIR/`$d"
  rsync -a --checksum --delete "`$STAGE/`$d/" "`$REMOTE_DIR/`$d/"
done

# Asset and script directories are overlaid, never pruned.
for d in `$ASSET_DIRS; do
  mkdir -p "`$REMOTE_DIR/`$d"
  rsync -a --checksum "`$STAGE/`$d/" "`$REMOTE_DIR/`$d/"
done

for f in `$ROOT_FILES; do
  cp -a "`$STAGE/`$f" "`$REMOTE_DIR/`$f"
done

# Verify the deployed tree parses and is structurally complete. This checks
# every module, not just server.js, and fails loudly if a directory is missing.
if ! "`$REMOTE_NODE" scripts/check-syntax.js; then
  restore_backup
  exit 1
fi

"`$REMOTE_NPM" install --omit=dev

chmod 755 "`$REMOTE_DIR" || true
chmod 755 "`$REMOTE_DIR/manage_public" "`$REMOTE_DIR/public" "`$REMOTE_DIR/data" "`$REMOTE_DIR/uploads" "`$REMOTE_DIR/app_release" 2>/dev/null || true
find "`$REMOTE_DIR/manage_public" "`$REMOTE_DIR/public" -type d -exec chmod 755 {} + 2>/dev/null || true
find "`$REMOTE_DIR/manage_public" "`$REMOTE_DIR/public" -type f -exec chmod 644 {} + 2>/dev/null || true
for d in `$CODE_DIRS; do
  find "`$REMOTE_DIR/`$d" -type d -exec chmod 755 {} + 2>/dev/null || true
  find "`$REMOTE_DIR/`$d" -type f -exec chmod 644 {} + 2>/dev/null || true
done
chmod 644 "`$REMOTE_DIR/server.js" "`$REMOTE_DIR/package.json" "`$REMOTE_DIR/package-lock.json" 2>/dev/null || true
chmod 600 "`$REMOTE_DIR/.env.family-chat" 2>/dev/null || true

systemctl restart family-chat

HTTP_CODE=""
for i in `$(seq 1 20); do
  HTTP_CODE="`$(curl -sS -o /dev/null -w '%{http_code}' -H 'X-FamilyChat-Client: android-app' http://127.0.0.1:3000/api/me || true)"
  if [ "`$HTTP_CODE" = "200" ] || [ "`$HTTP_CODE" = "401" ] || [ "`$HTTP_CODE" = "403" ]; then
    break
  fi
  sleep 1
done
if [ "`$HTTP_CODE" != "200" ] && [ "`$HTTP_CODE" != "401" ] && [ "`$HTTP_CODE" != "403" ]; then
  systemctl status family-chat --no-pager -l || true
  journalctl -u family-chat -n 80 --no-pager || true
  restore_backup
  exit 1
fi

if ! systemctl is-active --quiet family-chat; then
  journalctl -u family-chat -n 80 --no-pager || true
  restore_backup
  exit 1
fi

rm -rf "`$STAGE" "`$ARCHIVE"
echo "Web deployed and family-chat restarted (health `$HTTP_CODE)."
"@

[System.IO.File]::WriteAllText($localRemoteScript, $remoteScript, [System.Text.UTF8Encoding]::new($false))

try {
    scp @SshOptions $archive "${Remote}:$remoteArchive"
    if ($LASTEXITCODE -ne 0) { throw "scp web archive upload failed with exit code $LASTEXITCODE" }
    scp @SshOptions $localRemoteScript "${Remote}:$remoteScriptPath"
    if ($LASTEXITCODE -ne 0) { throw "scp remote deploy script upload failed with exit code $LASTEXITCODE" }
    ssh @SshOptions $Remote "sudo bash '$remoteScriptPath'; rm -f '$remoteScriptPath'"
    if ($LASTEXITCODE -ne 0) { throw "remote web deploy failed with exit code $LASTEXITCODE" }
} finally {
    Remove-Item -LiteralPath $stage -Recurse -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $archive -Force -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $localRemoteScript -Force -ErrorAction SilentlyContinue
}
