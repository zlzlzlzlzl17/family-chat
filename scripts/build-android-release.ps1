param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$Remote = $env:FAMILYCHAT_SSH_REMOTE,
    [string]$RemoteDir = $env:FAMILYCHAT_REMOTE_DIR,
    [string]$IdentityFile = $env:FAMILYCHAT_SSH_KEY,
    [string[]]$SshOptions = @("-o", "BatchMode=yes", "-o", "ConnectTimeout=30", "-o", "ServerAliveInterval=10"),
    [switch]$SkipUpload
)

$ErrorActionPreference = "Stop"

$SshOptions = @("-i", $IdentityFile) + $SshOptions

# Delegates the build to familychat_app/scripts/build-familychat.ps1 so the
# official channel goes through the same path as beta: mapping.txt is copied,
# the signature is verified with apksigner, and build-manifest.txt is written.

$appDir = Join-Path $ProjectRoot "familychat_app"
$builder = Join-Path $appDir "scripts\build-familychat.ps1"
if (-not (Test-Path -LiteralPath $builder)) {
    throw "Build script not found: $builder"
}

& $builder -Channel Official
if ($LASTEXITCODE -ne 0) { throw "Official build failed with exit code $LASTEXITCODE" }

$artifactDir = Join-Path $appDir "artifacts\release"
$artifact = Get-ChildItem -LiteralPath $artifactDir -Filter "familychat_*.apk" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $artifact) { throw "Official APK was not produced in $artifactDir" }

$manifestPath = Join-Path $artifactDir "build-manifest.txt"
if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "build-manifest.txt was not produced in $artifactDir"
}
$manifestFile = (Select-String -LiteralPath $manifestPath -Pattern '^file=(.+)$').Matches.Groups[1].Value
if ($manifestFile -ne $artifact.Name) {
    throw "build-manifest.txt refers to '$manifestFile' but the built APK is '$($artifact.Name)'"
}

$sha256 = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant()

Write-Host "Official APK ready: $($artifact.FullName)"
Write-Host "SHA-256: $sha256"

if ($SkipUpload) {
    Write-Host "SkipUpload was set, not uploading official APK."
    exit 0
}

if (-not $Remote -or -not $RemoteDir -or -not $IdentityFile) {
    throw "For upload, set Remote, RemoteDir and IdentityFile (or the FAMILYCHAT_SSH_* / FAMILYCHAT_REMOTE_DIR variables). Use -SkipUpload for a local build."
}
if (-not (Test-Path -LiteralPath $IdentityFile -PathType Leaf)) { throw "SSH key file not found." }

if ($artifact.Name -notmatch 'familychat_(v\d+\.\d+\.\d+)\.apk$') {
    throw "Official APK name does not match familychat_vx.x.x.apk: $($artifact.Name)"
}

$version = $Matches[1]
$remoteTmp = "/tmp/$($artifact.Name)"
$remoteScriptName = "familychat_upload_release_$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds()).sh"
$remoteScriptPath = "/tmp/$remoteScriptName"
$localRemoteScript = Join-Path ([System.IO.Path]::GetTempPath()) $remoteScriptName

# The upload previously left app_release_channels.sha256 empty. release-service
# backfills it lazily on first read, so this was self-healing rather than
# broken - but writing it here removes the window entirely, and comparing the
# locally computed hash against the uploaded file catches transfer corruption
# before the release goes live.
$remoteScript = @"
set -euo pipefail
REMOTE_DIR='$RemoteDir'
VERSION='$version'
FILE_NAME='$($artifact.Name)'
TMP_APK='$remoteTmp'
EXPECTED_SHA256='$sha256'

cd "`$REMOTE_DIR"
mkdir -p app_release

ACTUAL_SHA256="`$(sha256sum "`$TMP_APK" | awk '{print `$1}')"
if [ "`$ACTUAL_SHA256" != "`$EXPECTED_SHA256" ]; then
  echo "checksum mismatch after upload"
  echo "  expected: `$EXPECTED_SHA256"
  echo "  actual:   `$ACTUAL_SHA256"
  rm -f "`$TMP_APK"
  exit 1
fi

PREVIOUS_FILE="`$(sqlite3 data/chat.sqlite "SELECT file_name FROM app_release_channels WHERE channel='release';" || true)"
mv "`$TMP_APK" "app_release/`$FILE_NAME"
chmod 644 "app_release/`$FILE_NAME"
FILE_SIZE="`$(stat -c '%s' "app_release/`$FILE_NAME")"
UPLOADED_AT="`$(date +%s%3N)"
sqlite3 data/chat.sqlite "BEGIN IMMEDIATE;
INSERT INTO app_release_channels (channel, version, file_name, original_name, file_size, sha256, uploaded_at)
VALUES ('release', '`$VERSION', '`$FILE_NAME', '`$FILE_NAME', `$FILE_SIZE, '`$ACTUAL_SHA256', `$UPLOADED_AT)
ON CONFLICT(channel) DO UPDATE SET
  version=excluded.version,
  file_name=excluded.file_name,
  original_name=excluded.original_name,
  file_size=excluded.file_size,
  sha256=excluded.sha256,
  uploaded_at=excluded.uploaded_at;
INSERT INTO app_release_state (id, version, file_name, original_name, file_size, sha256, uploaded_at)
VALUES (1, '`$VERSION', '`$FILE_NAME', '`$FILE_NAME', `$FILE_SIZE, '`$ACTUAL_SHA256', `$UPLOADED_AT)
ON CONFLICT(id) DO UPDATE SET
  version=excluded.version,
  file_name=excluded.file_name,
  original_name=excluded.original_name,
  file_size=excluded.file_size,
  sha256=excluded.sha256,
  uploaded_at=excluded.uploaded_at;
COMMIT;"

STORED_SHA256="`$(sqlite3 data/chat.sqlite "SELECT sha256 FROM app_release_channels WHERE channel='release';")"
if [ "`$STORED_SHA256" != "`$ACTUAL_SHA256" ]; then
  echo "database sha256 was not stored correctly"
  exit 1
fi

if [ -n "`$PREVIOUS_FILE" ] && [ "`$PREVIOUS_FILE" != "`$FILE_NAME" ]; then
  rm -f "app_release/`$PREVIOUS_FILE"
fi
echo "Official APK uploaded: `$FILE_NAME (`$VERSION)"
echo "  sha256: `$ACTUAL_SHA256"
"@

[System.IO.File]::WriteAllText($localRemoteScript, $remoteScript, [System.Text.UTF8Encoding]::new($false))

try {
    scp @SshOptions $artifact.FullName "${Remote}:$remoteTmp"
    if ($LASTEXITCODE -ne 0) { throw "scp APK upload failed with exit code $LASTEXITCODE" }
    scp @SshOptions $localRemoteScript "${Remote}:$remoteScriptPath"
    if ($LASTEXITCODE -ne 0) { throw "scp remote script upload failed with exit code $LASTEXITCODE" }
    ssh @SshOptions $Remote "sudo bash '$remoteScriptPath'; rm -f '$remoteScriptPath'"
    if ($LASTEXITCODE -ne 0) { throw "remote official APK update failed with exit code $LASTEXITCODE" }
} finally {
    Remove-Item -LiteralPath $localRemoteScript -Force -ErrorAction SilentlyContinue
}
