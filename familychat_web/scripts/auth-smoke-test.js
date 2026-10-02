const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");
const Database = require("better-sqlite3");

const serverPath = path.resolve(process.argv[2] || path.join(__dirname, "..", "server.js"));
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "familychat-auth-"));
const dbPath = path.join(tempDir, "chat.sqlite");
const port = 3198;
const baseUrl = `http://127.0.0.1:${port}`;
const headers = {
  "Content-Type": "application/json",
  "X-FamilyChat-Client": "android-app",
};

function waitForServer(child) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("server_start_timeout")), 10_000);
    child.stdout.on("data", (chunk) => {
      const output = String(chunk);
      if (output.includes("Family chat HTTP") || output.includes('"event":"listening"')) {
        clearTimeout(timer);
        resolve();
      }
    });
    child.stderr.on("data", (chunk) => {
      process.stderr.write(chunk);
    });
    child.on("exit", (code) => {
      clearTimeout(timer);
      reject(new Error(`server_exited_${code}`));
    });
  });
}

async function request(pathname, options = {}) {
  const response = await fetch(`${baseUrl}${pathname}`, {
    ...options,
    headers: { ...headers, ...(options.headers || {}) },
  });
  const text = await response.text();
  const body = text ? JSON.parse(text) : {};
  return { response, body };
}

async function main() {
  const child = spawn(process.execPath, [serverPath], {
    env: {
      ...process.env,
      NODE_ENV: "development",
      HOST: "127.0.0.1",
      TRUST_PROXY_HOPS: "1",
      MANAGE_HOST: "manage.example.com",
      PORT: String(port),
      DB_PATH: dbPath,
      UPLOAD_DIR: path.join(tempDir, "uploads"),
      BLOG_UPLOAD_DIR: path.join(tempDir, "blog_uploads"),
      BLOG_ENABLED: "false",
      BLOG_AT_REST_KEY: "",
      FCM_PROJECT_ID: "",
      FCM_CLIENT_EMAIL: "",
      FCM_PRIVATE_KEY: "",
      VAPID_PUBLIC_KEY: "",
      VAPID_PRIVATE_KEY: "",
      APP_RELEASE_DIR: path.join(tempDir, "releases"),
      JWT_SECRET: "auth-smoke-test-secret",
      SEED_DEMO_USERS: "true",
      MANAGE_BOOTSTRAP_USERNAME: "smoke-admin",
      MANAGE_BOOTSTRAP_PASSWORD: "smoke-admin-password",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let db = null;

  try {
    await waitForServer(child);
    db = new Database(dbPath);
    const user = db.prepare("SELECT id, user_code FROM users WHERE username='alice'").get();
    const familyConversation = db.prepare("SELECT id FROM conversations WHERE kind='group' ORDER BY id LIMIT 1").get();
    assert.ok(familyConversation?.id, "seeded group conversation is required");
    for (let attempt = 1; attempt <= 5; attempt += 1) {
      const failedLogin = await request("/api/login", {
        method: "POST",
        body: JSON.stringify({
          user_code: "99999999",
          password: "definitely-wrong",
          device_id: "smoke-rate-limit-device",
        }),
      });
      assert.equal(failedLogin.response.status, attempt < 5 ? 401 : 429);
    }

    const trustedDeviceId = "smoke-trusted-device";
    const now = Date.now();
    db.prepare(
      `
        INSERT INTO devices (
          user_id, device_id, platform, device_name, manufacturer, model, status,
          approved_by_device_id, approved_by_admin, created_at, approved_at,
          revoked_at, last_seen_at, last_ip
        )
        VALUES (?, ?, 'android', 'Trusted test device', 'test', 'trusted',
                'trusted', '', 'smoke', ?, ?, 0, ?, '127.0.0.1')
      `
    ).run(user.id, trustedDeviceId, now, now, now);

    const trustedLogin = await request("/api/login", {
      method: "POST",
      body: JSON.stringify({
        user_code: user.user_code,
        password: "alice-change-me",
        device_id: trustedDeviceId,
        platform: "android",
      }),
    });
    assert.equal(trustedLogin.response.status, 200);
    assert.equal(trustedLogin.body.device_status, "trusted");
    assert.ok(trustedLogin.body.token);
    assert.ok(trustedLogin.body.refresh_token);

    const conversations = await request("/api/conversations", {
      headers: { Authorization: `Bearer ${trustedLogin.body.token}` },
    });
    assert.equal(conversations.response.status, 200);

    const pendingDeviceId = "smoke-pending-device";
    const pendingLogin = await request("/api/login", {
      method: "POST",
      body: JSON.stringify({
        user_code: user.user_code,
        password: "alice-change-me",
        device_id: pendingDeviceId,
        platform: "android",
      }),
    });
    assert.equal(pendingLogin.response.status, 200);
    assert.equal(pendingLogin.body.device_status, "pending");

    const deniedConversations = await request("/api/conversations", {
      headers: { Authorization: `Bearer ${pendingLogin.body.token}` },
    });
    assert.equal(deniedConversations.response.status, 403);
    assert.equal(deniedConversations.body.error, "device_pending");

    const deniedPushRegistration = await request("/api/fcm_register", {
      method: "POST",
      headers: { Authorization: `Bearer ${pendingLogin.body.token}` },
      body: JSON.stringify({ token: "pending-device-must-not-register-push" }),
    });
    assert.equal(deniedPushRegistration.response.status, 403);
    assert.equal(deniedPushRegistration.body.error, "device_pending");

    const deniedGroupKeys = await request(
      `/api/conversations/${familyConversation.id}/group_sender_keys?device_id=${pendingDeviceId}`,
      { headers: { Authorization: `Bearer ${pendingLogin.body.token}` } }
    );
    assert.equal(deniedGroupKeys.response.status, 403);
    assert.equal(deniedGroupKeys.body.error, "device_pending");

    const refreshed = await request("/api/session/refresh", {
      method: "POST",
      body: JSON.stringify({
        refresh_token: pendingLogin.body.refresh_token,
        device_id: pendingDeviceId,
      }),
    });
    assert.equal(refreshed.response.status, 200);
    assert.equal(refreshed.body.device_status, "pending");
    assert.notEqual(refreshed.body.refresh_token, pendingLogin.body.refresh_token);

    const replayedRefresh = await request("/api/session/refresh", {
      method: "POST",
      body: JSON.stringify({
        refresh_token: pendingLogin.body.refresh_token,
        device_id: pendingDeviceId,
      }),
    });
    assert.equal(replayedRefresh.response.status, 401);
    assert.equal(replayedRefresh.body.error, "session_expired");

    const identity = await request("/api/device_identity", {
      method: "POST",
      headers: { Authorization: `Bearer ${refreshed.body.token}` },
      body: JSON.stringify({
        device_id: pendingDeviceId,
        platform: "android",
        device_name: "Pending test device",
        key_alg: "test-ed25519",
        public_key: "A".repeat(96),
      }),
    });
    assert.equal(identity.response.status, 200);

    const prekey = await request("/api/direct_prekey", {
      method: "POST",
      headers: { Authorization: `Bearer ${refreshed.body.token}` },
      body: JSON.stringify({
        device_id: pendingDeviceId,
        key_alg: "test-x25519",
        identity_ecdh_public: "B".repeat(96),
        identity_ecdh_signature: "C".repeat(64),
        signed_prekey_public: "D".repeat(96),
        signed_prekey_signature: "E".repeat(64),
        one_time_prekeys: [],
      }),
    });
    assert.equal(prekey.response.status, 200);

    const approved = await request(`/api/devices/${pendingDeviceId}/approve`, {
      method: "POST",
      headers: { Authorization: `Bearer ${trustedLogin.body.token}` },
      body: "{}",
    });
    assert.equal(approved.response.status, 200);
    assert.equal(approved.body.status, "trusted");

    const approvedConversations = await request("/api/conversations", {
      headers: { Authorization: `Bearer ${refreshed.body.token}` },
    });
    assert.equal(approvedConversations.response.status, 200);

    const approvedGroupKeys = await request(
      `/api/conversations/${familyConversation.id}/group_sender_keys?device_id=${pendingDeviceId}`,
      { headers: { Authorization: `Bearer ${refreshed.body.token}` } }
    );
    assert.equal(approvedGroupKeys.response.status, 200);
    assert.ok(Number(approvedGroupKeys.body.epoch || 0) > 0);

    const registeredPush = await request("/api/fcm_register", {
      method: "POST",
      headers: { Authorization: `Bearer ${refreshed.body.token}` },
      body: JSON.stringify({
        token: "approved-device-test-fcm-token-that-is-long-enough-for-validation",
        platform: "android",
        locale: "en",
      }),
    });
    assert.equal(registeredPush.response.status, 200);
    assert.equal(
      db.prepare("SELECT COUNT(*) AS count FROM fcm_tokens WHERE user_id=? AND device_id=?")
        .get(user.id, pendingDeviceId).count,
      1
    );

    const epochBeforeRevoke = db.prepare("SELECT epoch FROM group_key_epochs WHERE conversation_id=?")
      .get(familyConversation.id).epoch;

    const revoked = await request(`/api/devices/${pendingDeviceId}`, {
      method: "DELETE",
      headers: { Authorization: `Bearer ${trustedLogin.body.token}` },
    });
    assert.equal(revoked.response.status, 200);

    const revokedAccess = await request("/api/conversations", {
      headers: { Authorization: `Bearer ${refreshed.body.token}` },
    });
    assert.equal(revokedAccess.response.status, 401);
    assert.equal(revokedAccess.body.error, "session_expired");

    const revokedRefresh = await request("/api/session/refresh", {
      method: "POST",
      body: JSON.stringify({
        refresh_token: refreshed.body.refresh_token,
        device_id: pendingDeviceId,
      }),
    });
    assert.equal(revokedRefresh.response.status, 401);

    const revokedDevice = db.prepare("SELECT status FROM devices WHERE user_id=? AND device_id=?")
      .get(user.id, pendingDeviceId);
    assert.equal(revokedDevice.status, "revoked");
    assert.equal(
      db.prepare("SELECT COUNT(*) AS count FROM auth_sessions WHERE user_id=? AND device_id=? AND status!='revoked'")
        .get(user.id, pendingDeviceId).count,
      0
    );
    assert.equal(
      db.prepare("SELECT COUNT(*) AS count FROM fcm_tokens WHERE user_id=? AND device_id=?")
        .get(user.id, pendingDeviceId).count,
      0
    );
    assert.equal(
      db.prepare("SELECT COUNT(*) AS count FROM direct_prekeys WHERE user_id=? AND device_id=?")
        .get(user.id, pendingDeviceId).count,
      0
    );
    assert.equal(
      db.prepare("SELECT COUNT(*) AS count FROM device_identity_keys WHERE user_id=? AND device_id=?")
        .get(user.id, pendingDeviceId).count,
      0
    );
    const epochAfterRevoke = db.prepare("SELECT epoch FROM group_key_epochs WHERE conversation_id=?")
      .get(familyConversation.id).epoch;
    assert.ok(epochAfterRevoke > epochBeforeRevoke, "revoking a trusted device must rotate group keys");

    const originalTrustedSessionStillWorks = await request("/api/conversations", {
      headers: { Authorization: `Bearer ${trustedLogin.body.token}` },
    });
    assert.equal(originalTrustedSessionStillWorks.response.status, 200);

    // Reinstall creates a new device identity. The revoked installation must stay
    // unusable while the replacement follows the pending -> trusted flow again.
    const reinstalledDeviceId = "smoke-reinstalled-device";
    const reinstalledLogin = await request("/api/login", {
      method: "POST",
      body: JSON.stringify({
        user_code: user.user_code,
        password: "alice-change-me",
        device_id: reinstalledDeviceId,
        platform: "android",
      }),
    });
    assert.equal(reinstalledLogin.response.status, 200);
    assert.equal(reinstalledLogin.body.device_status, "pending");

    const reinstalledIdentity = await request("/api/device_identity", {
      method: "POST",
      headers: { Authorization: `Bearer ${reinstalledLogin.body.token}` },
      body: JSON.stringify({
        device_id: reinstalledDeviceId,
        platform: "android",
        device_name: "Reinstalled phone",
        key_alg: "test-ed25519",
        public_key: "R".repeat(96),
      }),
    });
    assert.equal(reinstalledIdentity.response.status, 200);

    const reinstalledPrekey = await request("/api/direct_prekey", {
      method: "POST",
      headers: { Authorization: `Bearer ${reinstalledLogin.body.token}` },
      body: JSON.stringify({
        device_id: reinstalledDeviceId,
        key_alg: "test-x25519",
        identity_ecdh_public: "S".repeat(96),
        identity_ecdh_signature: "T".repeat(64),
        signed_prekey_public: "U".repeat(96),
        signed_prekey_signature: "V".repeat(64),
        one_time_prekeys: [],
      }),
    });
    assert.equal(reinstalledPrekey.response.status, 200);

    const reinstalledApproved = await request(`/api/devices/${reinstalledDeviceId}/approve`, {
      method: "POST",
      headers: { Authorization: `Bearer ${trustedLogin.body.token}` },
      body: "{}",
    });
    assert.equal(reinstalledApproved.response.status, 200);
    assert.equal(reinstalledApproved.body.status, "trusted");
    const reinstalledAccess = await request("/api/conversations", {
      headers: { Authorization: `Bearer ${reinstalledLogin.body.token}` },
    });
    assert.equal(reinstalledAccess.response.status, 200);
    assert.equal(
      db.prepare("SELECT COUNT(*) AS count FROM direct_prekeys WHERE user_id=? AND device_id=?")
        .get(user.id, reinstalledDeviceId).count,
      1
    );
    assert.equal(
      db.prepare("SELECT COUNT(*) AS count FROM direct_prekeys WHERE user_id=? AND device_id=?")
        .get(user.id, pendingDeviceId).count,
      0
    );

    // Account deletion is approved through the same management endpoint used in
    // production and must remove every account-owned authentication artifact.
    const bob = db.prepare("SELECT id, user_code FROM users WHERE username='bob'").get();
    const bobDeviceId = "smoke-bob-device";
    db.prepare(
      `
        INSERT INTO devices (
          user_id, device_id, platform, device_name, manufacturer, model, status,
          approved_by_device_id, approved_by_admin, created_at, approved_at,
          revoked_at, last_seen_at, last_ip
        )
        VALUES (?, ?, 'android', 'Bob phone', 'test', 'bob',
                'trusted', '', 'smoke', ?, ?, 0, ?, '127.0.0.1')
      `
    ).run(bob.id, bobDeviceId, now, now, now);
    const bobLogin = await request("/api/login", {
      method: "POST",
      body: JSON.stringify({
        user_code: bob.user_code,
        password: "bob-change-me",
        device_id: bobDeviceId,
        platform: "android",
      }),
    });
    assert.equal(bobLogin.response.status, 200);
    const deletionRequested = await request("/api/account_deletion_request", {
      method: "POST",
      headers: { Authorization: `Bearer ${bobLogin.body.token}` },
      body: "{}",
    });
    assert.equal(deletionRequested.response.status, 200);
    const deletionRequest = db
      .prepare("SELECT id FROM account_deletion_requests WHERE user_id=? AND status='pending'")
      .get(bob.id);
    assert.ok(deletionRequest?.id);

    const manageLogin = await request("/manage_api/login", {
      method: "POST",
      headers: { "X-Forwarded-Host": "manage.example.com" },
      body: JSON.stringify({ username: "smoke-admin", password: "smoke-admin-password" }),
    });
    assert.equal(manageLogin.response.status, 200);
    const manageCookie = String(manageLogin.response.headers.get("set-cookie") || "").split(";")[0];
    assert.ok(manageCookie);
    const deletionApproved = await request(`/manage_api/account_deletions/${deletionRequest.id}/approve`, {
      method: "POST",
      headers: { "X-Forwarded-Host": "manage.example.com", Cookie: manageCookie },
      body: "{}",
    });
    assert.equal(deletionApproved.response.status, 200);
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM users WHERE id=?").get(bob.id).count, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM devices WHERE user_id=?").get(bob.id).count, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM auth_sessions WHERE user_id=?").get(bob.id).count, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM fcm_tokens WHERE user_id=?").get(bob.id).count, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM direct_prekeys WHERE user_id=?").get(bob.id).count, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM conversation_members WHERE user_id=?").get(bob.id).count, 0);

    console.log("auth, reinstall, multi-device, and account deletion smoke test passed");
  } finally {
    if (db?.open) db.close();
    if (child.exitCode === null) {
      child.kill("SIGTERM");
      await Promise.race([
        new Promise((resolve) => child.once("exit", resolve)),
        new Promise((resolve) => setTimeout(resolve, 3_000)),
      ]);
    }
    for (let attempt = 0; attempt < 5; attempt += 1) {
      try {
        fs.rmSync(tempDir, { recursive: true, force: true });
        break;
      } catch (error) {
        if (attempt === 4) throw error;
        await new Promise((resolve) => setTimeout(resolve, 150 * (attempt + 1)));
      }
    }
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
