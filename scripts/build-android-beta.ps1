param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

$ErrorActionPreference = "Stop"

# Delegates to familychat_app/scripts/build-familychat.ps1 rather than invoking
# gradle directly.
#
# This script used to run the gradle task and stop there. That skipped the
# mapping.txt copy, the apksigner signature verification, and the
# build-manifest.txt write - which is why artifacts/beta/build-manifest.txt sat
# at v3.0.13 while a v3.0.16 APK shipped beside it. Keeping a single build path
# means the manifest cannot fall out of step with the artifact again.
#
# Beta builds are never uploaded automatically.

$appDir = Join-Path $ProjectRoot "familychat_app"
$builder = Join-Path $appDir "scripts\build-familychat.ps1"
if (-not (Test-Path -LiteralPath $builder)) {
    throw "Build script not found: $builder"
}

& $builder -Channel Beta
if ($LASTEXITCODE -ne 0) { throw "Beta build failed with exit code $LASTEXITCODE" }

$artifactDir = Join-Path $appDir "artifacts\beta"
$artifact = Get-ChildItem -LiteralPath $artifactDir -Filter "familychat_*.apk" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $artifact) { throw "Beta APK was not produced in $artifactDir" }

$manifestPath = Join-Path $artifactDir "build-manifest.txt"
if (-not (Test-Path -LiteralPath $manifestPath)) {
    throw "build-manifest.txt was not produced in $artifactDir"
}

# Guard against the exact failure this script previously allowed: an artifact
# and a manifest that disagree about which file was built.
$manifestFile = (Select-String -LiteralPath $manifestPath -Pattern '^file=(.+)$').Matches.Groups[1].Value
if ($manifestFile -ne $artifact.Name) {
    throw "build-manifest.txt refers to '$manifestFile' but the built APK is '$($artifact.Name)'"
}

Write-Host "Beta APK ready: $($artifact.FullName)"
Write-Host "Manifest verified against artifact: $manifestPath"
Write-Host "Beta builds are not uploaded automatically."
