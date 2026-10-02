param(
    [ValidateSet('Beta', 'Official')]
    [string]$Channel = 'Beta'
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$gradleHome = Join-Path $root '.gradle-home'
$androidHome = Join-Path $root '.android-home'
$env:GRADLE_USER_HOME = $gradleHome
$env:ANDROID_USER_HOME = $androidHome

$task = if ($Channel -eq 'Official') { 'copyFamilyChatOfficialReleaseApk' } else { 'copyFamilyChatBetaReleaseApk' }

# Gradle resolves the project from the current working directory, not from the
# location of gradlew.bat. Without this the script only works when it happens to
# be invoked from familychat_app, and fails elsewhere with "does not contain a
# Gradle build" - which is exactly what happened once the root build-android-*
# scripts started delegating here.
Push-Location $root
try {
    & (Join-Path $root 'gradlew.bat') $task --no-daemon '-Pkotlin.compiler.execution.strategy=in-process'
    if ($LASTEXITCODE -ne 0) { throw "Gradle task failed: $task" }
} finally {
    Pop-Location
}

$artifactDir = Join-Path $root $(if ($Channel -eq 'Official') { 'artifacts\release' } else { 'artifacts\beta' })
$apk = Get-ChildItem -LiteralPath $artifactDir -Filter 'familychat_*.apk' | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { throw "APK not found in $artifactDir" }

$variant = if ($Channel -eq 'Official') { 'officialRelease' } else { 'betaRelease' }
$mappingSource = Join-Path $root "app\build\outputs\mapping\$variant\mapping.txt"
if (Test-Path -LiteralPath $mappingSource) {
    Copy-Item -LiteralPath $mappingSource -Destination (Join-Path $artifactDir 'mapping.txt') -Force
}

$sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$apksigner = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Filter 'apksigner.bat' -Recurse |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $apksigner) { throw 'apksigner.bat was not found in Android SDK build-tools.' }

& $apksigner.FullName verify --verbose --print-certs $apk.FullName
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }

$hash = (Get-FileHash -LiteralPath $apk.FullName -Algorithm SHA256).Hash
$manifest = @(
    "file=$($apk.Name)"
    "channel=$($Channel.ToLowerInvariant())"
    "sha256=$hash"
    "built_at_utc=$([DateTime]::UtcNow.ToString('o'))"
) -join [Environment]::NewLine
Set-Content -LiteralPath (Join-Path $artifactDir 'build-manifest.txt') -Value $manifest -Encoding ascii

Write-Output "APK=$($apk.FullName)"
Write-Output "SHA-256=$hash"
Write-Output "MAPPING=$(Join-Path $artifactDir 'mapping.txt')"
