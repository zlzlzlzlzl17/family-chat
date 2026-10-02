# Showcase Changes and Publication Notes

## Isolation

This is an independent snapshot of the original local repository's tracked source files at commit `3db7ba7`. The original checkout, credentials, databases and 33 reachable commits were not modified. Its `.git` directory was not copied. Publication uses `codex/public-showcase` and replaces the existing GitHub repository's current file tree with this cleaned snapshot as a normal successor commit; the existing GitHub history is preserved without force push. The original local history is not included.

No runtime data, private key, Firebase configuration, signing file, dependencies or build cache is included in the publishable Git file set. Local validation dependencies/build output exist only in ignored directories. Publish this snapshot's source set, not the enclosing original workspace. A later owner request authorized read-only SSH inspection of the reference deployment; no remote files or service state were modified.

## New files

- Root `README.md`, `.env.example`.
- Owner-selected MIT `LICENSE`; backend package and lockfile root metadata now use `MIT`.
- `docs/architecture.md`, `message_flow.md`, `security.md`, `deployment.md`, `firebase.md`, `feature_evidence.md`, `validation.md`, and this inventory.
- `deploy/nginx.example.conf`, `deploy/family-chat.service.example`.
- `familychat_app/familychat.properties.example`, `local.properties.example`.
- `screenshots/README.md`.
- Three owner-supplied screenshot files, preserved without image editing.

## Changed files

| Files | Change |
| --- | --- |
| `.gitignore`, `.gitattributes` | Secret/data/tool/build exclusions; portable Linux template line endings |
| `familychat_web/package.json`, `package-lock.json` | Owner-selected MIT root package metadata; dependency licenses unchanged |
| `.tools/with-node.cmd` | Optional bundled Node 20 with PATH fallback, rather than requiring a specific local download |
| `familychat_app/app/build.gradle.kts`, `ChatCore.kt` | Generated configurable default endpoint from local config, environment or Gradle property; placeholder fallback |
| `familychat_app/app/src/test/java/com/example/chat/AuthFeedbackTest.kt` | Production domain replaced by an example |
| `familychat_web/server.js` | Example management host fallback |
| `familychat_web/scripts/auth-smoke-test.js`, `blog-smoke-test.js` | Example management host and isolated child environment, loopback binding, temporary data and disabled external push |
| `scripts/deploy-web.ps1`, `build-android-release.ps1` | Explicit SSH remote/key/directory configuration and no production defaults |
| `familychat_web/scripts/deploy-candidate.sh`, `validate-production-copy.sh`, `rotate-jwt-secret.sh` | Explicit deployment/snapshot/environment locations rather than production paths |
| `docs/README.md`, `docs/operations/release-and-deploy.zh.md`, `familychat_app/RELEASE_SIGNING_GUIDE_ZH.md` | Public documentation and relative paths |
| `docs/operations/manage-totp.zh.md`, `docs/history/auth-device-migration-v2.1.md` | Example management domain |

`ChatCore.kt` in the table is under `familychat_app/app/src/main/java/com/example/chat/`.

The two old `familychat_app/artifacts/{beta,release}/build-manifest.txt` records were excluded from this snapshot. The original records are intact. Build scripts can generate new ignored artifacts/manifests.

Messaging functionality and cryptographic implementation files were not changed. Blog, management, updates, legacy web and benchmark source remain present.

## Original history locations

Production deployment identifiers remain in the untouched original history. Examples in commit `e3c71b4` (values intentionally omitted):

- `scripts/deploy-web.ps1:3`
- `scripts/build-android-release.ps1:3`
- `familychat_app/app/src/main/java/com/example/chat/ChatCore.kt:50`
- `familychat_web/server.js:50`
- `familychat_web/scripts/auth-smoke-test.js:394` and `:402`
- `familychat_web/scripts/deploy-candidate.sh:57`
- Historical docs including `MANAGE_PORTAL_GUIDE_ZH.md:8`, `MANAGE_TOTP_GUIDE_ZH.md:3`, `APP_UPDATE_DEPLOY_GUIDE_ZH.md:67`.

The earlier 33-commit credential-pattern scan found private-key markers in `DEPLOY_GUIDE_ZH.md` at lines 327, 426 and 804 of the initial snapshot; these were document placeholders, not identified real private keys. No real credential leak was confirmed by that scan. This is a bounded pattern scan, not a guarantee covering every possible secret format or unreachable Git object.

Do not push the original history assuming this cleaned snapshot also sanitized it. No history rewrite was performed.

## Owner decisions still needed

- MIT was selected by the owner and added; dependency/component licenses remain separate.
- Three real screenshots supplied by the owner are included. Additional retry, transfer and notification captures remain optional.
- Create your own Firebase project, HTTPS endpoint and private deployment/signing configuration.
- Validate Linux templates with Nginx/systemd on your own host.
- Template structure now incorporates read-only observations of a running deployment; the adapted files were not applied to that server.
- Publication targets `zlzlzlzlzl17/family-chat`, renamed from the older template repository, and uses the owner's GitHub noreply commit address rather than a personal email address.
- Keep the documented security gaps visible; this snapshot is a showcase, not an audited secure messenger.

## Suggested GitHub metadata

Description: `Self-hosted Android chat with a Kotlin client, Node.js backend, REST/WebSocket messaging, SQLite persistence, Room outbox, and FCM push.`

Topics: `android`, `kotlin`, `jetpack-compose`, `nodejs`, `express`, `websocket`, `sqlite`, `room`, `workmanager`, `firebase-cloud-messaging`, `self-hosted`, `nginx`.
