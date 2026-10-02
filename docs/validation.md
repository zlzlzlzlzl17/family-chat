# Validation Results

Validated on 2026-10-02 in the independent showcase copy.

| Check | Result |
| --- | --- |
| Backend `npm test` | Passed: syntax/management script, architecture, security, auth/device/account deletion, Blog/announcement smoke suites |
| All JavaScript syntax | Passed: 40 files; backend's own checker separately covers 32 server files |
| PowerShell script parsing | Passed: four existing build/deploy scripts, without executing deployment |
| Android `:app:assembleBetaDebug` | Passed, offline |
| Android `:app:testBetaDebugUnitTest` | Passed: 22 suites, 106 tests, zero failures/errors/skips |
| Markdown relative links | No broken links |
| Publishable file scan | No original deployment domain/IP, Windows absolute path, real private-key marker or common cloud/GitHub/Firebase credential pattern detected |
| Ignore coverage | Required credential/data/upload/cache/artifact paths checked; actual local config/build output absent from staged files |
| Original repository | Original tracked worktree/index unchanged; original HEAD remains `3db7ba7`; no history rewrite |
| MIT follow-up | LICENSE added; package and lockfile root metadata agree on MIT |
| Screenshot follow-up | Three supplied originals included byte-for-byte; no GPS EXIF tags detected |
| Deployment reference | Read-only SSH inspection of Nginx 1.18 and systemd configuration/status; no remote writes, upload, reload or restart |
| Remote read-only verification | Nginx/systemd configuration hashes and the service's main PID remained unchanged between inspection and final verification |
| Adapted Nginx template | Local brace/quote structure check passed; this is not nginx -t |

## Environment and commands

Backend validation used Node.js 20.20.0 and a copy of the already installed native dependencies, not a fresh network `npm ci`. The full package test command ran inside `familychat_web/`. The Windows sandbox required Node symlink-preservation options for path resolution; those were validation environment settings, not code changes. Test temporary files were isolated in the copy's ignored `tmp/` directory.

The auth/Blog child servers bind loopback, use disposable SQLite paths, set their own example management host, and explicitly disable FCM and Web Push credentials. No existing database or production server was used by these tests. The owner's later request separately authorized read-only SSH inspection of deployment configuration.

Android validation used the installed JDK 21.0.10 and SDK 36.1, with Gradle 9.3.1. Gradle caches were copied into the showcase's ignored directories; `GRADLE_USER_HOME` and `ANDROID_USER_HOME` pointed there. No release signing configuration or Firebase client configuration was copied.

```bash
./gradlew :app:assembleBetaDebug :app:testBetaDebugUnitTest --offline --no-daemon \
  -Pandroid.builder.sdkDownload=false -Pkotlin.compiler.execution.strategy=in-process
```

The debug APK is local, ignored build output. Generated BuildConfig uses the placeholder endpoint on an unconfigured fresh install. Existing deprecation warnings and unstripped native-library warnings did not fail the build.

## Not verified

- Fresh dependency installation without cached packages.
- Android instrumentation, emulator/real-device behavior, real FCM delivery or WebRTC call quality.
- Installing or syntax-testing the adapted Nginx/systemd templates, certificate validity, live TLS behavior or SSH deployment. The read-only reference inspection did not run nginx -t/-T, reload or restart.
- Release APK signing/upload or automatic update installation.
- Independent cryptographic audit, attack resistance, throughput or performance improvement.

Tests demonstrate the listed checks, not a complete security guarantee. Generic deployment templates still require operator validation.
