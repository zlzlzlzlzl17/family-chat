# Local Configuration and Linux Deployment

These instructions describe a generic setup derived from the code and a read-only inspection of a running Nginx/systemd deployment on 2026-10-02. No production credentials, host or certificate configuration is supplied. The adapted templates themselves have not been installed or syntax-tested on that host.

## Local configuration

Copy the root `.env.example` to ignored `.env`, edit its local bootstrap password, and run from `familychat_web/`:

```bash
npm ci
node --env-file=../.env server.js
```

Node 20.12+ within major 20 is required for the documented `--env-file` command. The server itself reads `process.env`; it does not automatically load dotenv files.

The example's relative DB/upload paths are resolved against that working directory. Empty FCM settings skip external push sending; Blog is disabled. `MANAGE_HOST=localhost` allows the local management portal at `http://localhost:3000`. The chat browser client is disabled.

Use only disposable local accounts/data. Registration requires administrator approval, and new devices require trusted-device or administrator approval. Remove both bootstrap variables after creating the administrator.

## Configuration reference

| Group | Variables |
| --- | --- |
| Runtime | `NODE_ENV`, `HOST`, `PORT`, `TRUST_PROXY_HOPS` |
| Data | `DB_PATH`, `UPLOAD_DIR`, `APP_RELEASE_DIR`, `BLOG_UPLOAD_DIR` |
| Authentication | `JWT_SECRET` |
| Bootstrap | `SEED_DEMO_USERS`, `MANAGE_BOOTSTRAP_USERNAME`, `MANAGE_BOOTSTRAP_PASSWORD` |
| Management | `MANAGE_HOST` |
| Push | `FCM_PROJECT_ID`, `FCM_CLIENT_EMAIL`, `FCM_PRIVATE_KEY`; optional legacy `VAPID_*` |
| Calls | `CALL_STUN_URLS`, `CALL_TURN_URLS`, `CALL_TURN_USERNAME`, `CALL_TURN_CREDENTIAL` |
| Optional Blog | `BLOG_ENABLED`, `BLOG_AT_REST_KEY` |

Android default endpoint precedence is Gradle `-PfamilychatServerUrl`, then `FAMILYCHAT_SERVER_URL`, then ignored `familychat_app/familychat.properties`, then the `https://example.com` placeholder. An existing saved app URL still takes precedence at runtime.

## Service installation

1. Install Node.js 20 and dependencies from the checked-in lockfile (`npm ci --omit=dev`) on Linux. Native dependencies may need compilation tools.
2. Put source at `/path/to/app` as an example. Create a dedicated `familychat` service user/group.
3. Create `runtime/data`, `runtime/uploads`, `runtime/app_release`, and `runtime/blog_uploads`. Grant write access to these directories only to the service user.
4. Create the service environment file at `/path/to/app/.env`, readable only by the service's administrators; keep it out of Git and deployment archives.
5. Adapt [family-chat.service.example](../deploy/family-chat.service.example), especially the Node executable and file paths.
6. Start with systemd; inspect service status and logs, then confirm the authenticated API gate over loopback.

Essential environment settings for the single-Nginx topology are:

```dotenv
NODE_ENV=production
HOST=127.0.0.1
PORT=3000
TRUST_PROXY_HOPS=1
MANAGE_HOST=manage.example.com
SEED_DEMO_USERS=false
DB_PATH=/path/to/app/runtime/data/chat.sqlite
UPLOAD_DIR=/path/to/app/runtime/uploads
APP_RELEASE_DIR=/path/to/app/runtime/app_release
BLOG_UPLOAD_DIR=/path/to/app/runtime/blog_uploads
BLOG_ENABLED=false
```

Set a freshly generated unique `JWT_SECRET` of at least 32 characters separately; do not reuse the example's development secret. For example, `openssl rand -hex 32` generates a value to put in the private environment file. Temporarily set both bootstrap fields for a new DB, then remove them.

The service template can read code/configuration but only writes under `runtime/`. It does not run the server as root. Installation and certificate provisioning are operator steps, not scripts executed by this repository's tests.

## Nginx and TLS

Adapt [nginx.example.conf](../deploy/nginx.example.conf):

- `example.com` is the Android API host and `manage.example.com` is the management host.
- Supply certificates covering both names and substitute their real file locations.
- Enable the configuration inside Nginx's `http` context; run `nginx -t` before reload.
- Forward HTTP and WebSocket upgrades to `127.0.0.1:3000`.
- Keep the Node port inaccessible from the public network.
- The template overwrites forwarding headers and assumes exactly one trusted reverse proxy. Change `TRUST_PROXY_HOPS` for a different topology.
- Access logs deliberately omit query arguments because WebSocket authentication puts a token in the URL. Do not enable another full-URI access log for the same route.

The Node process itself creates an HTTP server. HTTPS/TLS is supplied by the proxy. The template does not alias data directories or expose database files. The app's own `/uploads` endpoint remains publicly readable; see the security boundary in [security.md](security.md).

## Android against a local backend

Android rejects cleartext HTTP and does not automatically trust self-signed certificates. Use a developer-controlled HTTPS tunnel to a disposable backend, or local Nginx with a certificate trusted by the device. If the proxy changes the request host, align `MANAGE_HOST` and manage routing with it. Device loopback is not the development machine. No trust-all TLS code is supplied.

Use a fresh debug installation after changing the compiled default URL; local saved preferences survive ordinary rebuilds.

## Read-only deployment reference

The owner authorized SSH inspection of configuration only. The inspection read Nginx configuration and systemd unit structure/status; it did not read environment-file values, databases, private certificate keys or logs, and did not upload, edit, reload or restart anything. Credential values in inline systemd environment directives were omitted from output.

Observed structure: Nginx 1.18 on Ubuntu; HTTP-to-HTTPS redirection; IPv4/IPv6 TLS listeners with HTTP/2; site-level TLS 1.2/1.3; proxying to loopback port 3000; WebSocket and forwarding headers; a running Node systemd service with restart policy, NoNewPrivileges and PrivateTmp.

The public templates intentionally differ from the inspected instance:

- Hosts, certificate locations and application paths are placeholders.
- A map sends an Upgrade connection header only when a request needs it.
- Access logs omit query arguments; no such dedicated format was observed in the inspected site configuration.
- One HSTS header replaces duplicate observed declarations; includeSubDomains is not assumed. Other observed browser headers and TLS session caching are retained.
- The body limit is 300 MiB rather than the observed 256 MiB to allow multipart overhead for the retained 256 MiB APK limit.
- The service uses a dedicated non-root account rather than the observed root account, with writes confined to runtime directories.
- The single-proxy edge overwrites X-Forwarded-For rather than preserving arbitrary incoming forwarding values.

These are template decisions, not changes applied to the reference server. Validate them on your own host, especially certificate paths, filesystem permissions and management host routing.

## Backup and maintenance

Back up SQLite using a consistent SQLite backup/snapshot approach, and include the necessary uploaded files and private encryption configuration in access-controlled operator backups. Do not copy just a live DB file while ignoring WAL. Treat backups as sensitive data, not repository assets.

Group TTL and periodic expiry cleanup are not secure-erasure guarantees. Changing `BLOG_AT_REST_KEY` without re-encryption loses access to existing Blog content.

Retained SSH deployment and key-rotation tools require explicit operator parameters. The showcase validation did not run them or contact an existing production host. See [release operations](operations/release-and-deploy.zh.md).
