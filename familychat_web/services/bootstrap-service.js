"use strict";

function createBootstrapService({ db, runtime, bcrypt, crypto, logger }) {
  const RUNTIME = runtime;

function seedUsersIfEmpty() {
  const count = db.prepare("SELECT COUNT(*) AS c FROM users").get().c;
  if (count > 0) return;
  if (!RUNTIME.seedDemoUsers) return;

  const users = [
    { username: "alice", password: "alice-change-me", color: "#128c7e", is_admin: 1 },
    { username: "bob", password: "bob-change-me", color: "#34b7f1", is_admin: 0 },
    { username: "carol", password: "carol-change-me", color: "#f39c12", is_admin: 0 },
  ];

  const insert = db.prepare(
    "INSERT INTO users (user_code, username, password_hash, color, is_admin, session_id, last_login_ip) VALUES (?, ?, ?, ?, ?, ?, ?)"
  );

  for (const user of users) {
    insert.run(
      generateUniqueUserCode(),
      user.username,
      bcrypt.hashSync(user.password, 12),
      user.color,
      user.is_admin,
      "",
      ""
    );
  }
}

function generateUniqueUserCode() {
  for (let i = 0; i < 1000; i += 1) {
    const code = String(crypto.randomInt(0, 100000000)).padStart(8, "0");
    const exists = db.prepare("SELECT 1 AS ok FROM users WHERE user_code=?").get(code);
    if (!exists) return code;
  }
  throw new Error("unable_to_generate_user_code");
}

function ensureUserCodes() {
  const rows = db.prepare("SELECT id, user_code FROM users ORDER BY id").all();
  const update = db.prepare("UPDATE users SET user_code=? WHERE id=?");
  for (const row of rows) {
    if (String(row.user_code || "").match(/^\d{8}$/)) continue;
    update.run(generateUniqueUserCode(), row.id);
  }
  db.exec("CREATE UNIQUE INDEX IF NOT EXISTS users_user_code_idx ON users(user_code);");
}

function migrateLegacyAuthState() {
  const now = Date.now();
  db.prepare("UPDATE users SET account_status='active' WHERE account_status='' OR account_status IS NULL").run();
  const identities = db
    .prepare(
      `
        SELECT user_id, device_id, platform, device_name, created_at, updated_at, last_seen_at
        FROM device_identity_keys
        ORDER BY user_id, updated_at DESC
      `
    )
    .all();
  const insertDevice = db.prepare(
    `
      INSERT INTO devices (
        user_id, device_id, platform, device_name, manufacturer, model, status,
        approved_by_device_id, approved_by_admin, created_at, approved_at,
        revoked_at, last_seen_at, last_ip
      )
      VALUES (?, ?, ?, ?, '', '', 'trusted', '', 'legacy_migration', ?, ?, 0, ?, '')
      ON CONFLICT(user_id, device_id) DO NOTHING
    `
  );
  for (const identity of identities) {
    insertDevice.run(
      identity.user_id,
      identity.device_id,
      identity.platform || "android",
      identity.device_name || "",
      Number(identity.created_at || now),
      Number(identity.updated_at || now),
      Number(identity.last_seen_at || identity.updated_at || now)
    );
  }

  const latestDeviceByUser = new Map();
  for (const identity of identities) {
    if (!latestDeviceByUser.has(identity.user_id)) {
      latestDeviceByUser.set(identity.user_id, identity.device_id);
    }
  }
  const insertLegacySession = db.prepare(
    `
      INSERT INTO auth_sessions (
        session_id, user_id, device_id, client_type, refresh_token_hash, status,
        created_at, last_seen_at, access_expires_at, refresh_expires_at,
        revoked_at, ip, user_agent
      )
      VALUES (?, ?, ?, ?, '', 'active', ?, ?, ?, ?, 0, ?, 'legacy-client')
      ON CONFLICT(session_id) DO NOTHING
    `
  );
  for (const user of db.prepare("SELECT id, session_id, desktop_session_id, last_login_ip FROM users").all()) {
    const deviceId = latestDeviceByUser.get(user.id);
    if (!deviceId) continue;
    for (const [sessionId, clientType] of [
      [user.session_id, "mobile"],
      [user.desktop_session_id, "desktop"],
    ]) {
      if (!sessionId) continue;
      insertLegacySession.run(
        sessionId,
        user.id,
        deviceId,
        clientType,
        now,
        now,
        now + 7 * 24 * 60 * 60 * 1000,
        now + 7 * 24 * 60 * 60 * 1000,
        user.last_login_ip || ""
      );
    }
  }

  db.prepare(
    `
      UPDATE fcm_tokens
      SET device_id=COALESCE(
        (
          SELECT d.device_id
          FROM devices d
          WHERE d.user_id=fcm_tokens.user_id AND d.status='trusted'
          ORDER BY d.last_seen_at DESC
          LIMIT 1
        ),
        ''
      )
      WHERE device_id=''
    `
  ).run();
}

function generateUniqueGroupCode() {
  for (let i = 0; i < 1000; i += 1) {
    const code = String(crypto.randomInt(0, 10000000000)).padStart(10, "0");
    const exists = db.prepare("SELECT 1 AS ok FROM conversations WHERE group_code=?").get(code);
    if (!exists) return code;
  }
  throw new Error("unable_to_generate_group_code");
}

function ensureGroupCodes() {
  const rows = db.prepare("SELECT id, group_code FROM conversations WHERE kind='group' ORDER BY id").all();
  const update = db.prepare("UPDATE conversations SET group_code=? WHERE id=?");
  for (const row of rows) {
    if (String(row.group_code || "").match(/^\d{10}$/)) continue;
    update.run(generateUniqueGroupCode(), row.id);
  }
}

function ensureGroupAdmins() {
  db.prepare(
    `
      UPDATE conversation_members
      SET role='admin'
      WHERE conversation_id IN (SELECT id FROM conversations WHERE kind='group')
        AND user_id IN (SELECT id FROM users WHERE is_admin=1)
        AND role != 'owner'
    `
  ).run();

  const groups = db.prepare("SELECT id FROM conversations WHERE kind='group' ORDER BY id").all();
  const hasOwner = db.prepare(
    "SELECT 1 AS ok FROM conversation_members WHERE conversation_id=? AND role='owner' LIMIT 1"
  );
  const preferredOwner = db.prepare(
    `
      SELECT m.user_id
      FROM conversation_members m
      JOIN users u ON u.id = m.user_id
      WHERE m.conversation_id=?
        AND u.username IN ('zlzl', 'zl')
      ORDER BY CASE u.username WHEN 'zlzl' THEN 0 ELSE 1 END
      LIMIT 1
    `
  );
  const fallbackOwner = db.prepare(
    `
      SELECT m.user_id
      FROM conversation_members m
      JOIN users u ON u.id = m.user_id
      WHERE m.conversation_id=?
      ORDER BY u.is_admin DESC, m.joined_at, m.user_id
      LIMIT 1
    `
  );
  const promote = db.prepare("UPDATE conversation_members SET role='owner' WHERE conversation_id=? AND user_id=?");
  for (const group of groups) {
    if (hasOwner.get(group.id)) continue;
    const member = preferredOwner.get(group.id) || fallbackOwner.get(group.id);
    if (member) promote.run(group.id, member.user_id);
  }
}

/**
 * The account that update announcements are published under.
 *
 * It is a row in `users` because blog_posts.author_user_id is a foreign key
 * there - a post has to have an author, and announcements should carry a name
 * rather than borrow a family member's.
 *
 * It is emphatically not a person, and is fenced off as one:
 *   - `account_status='system'` is refused by every sign-in path, the same way
 *     'disabled' and 'deleted' are;
 *   - the password hash is bcrypt over 32 random bytes that are discarded here
 *     and never stored, so even if a status check were missed there is no
 *     password that opens it;
 *   - it is hidden from contact lookup, so nobody can add it as a contact;
 *   - it is excluded from the manage family list, because it is not family.
 *
 * The only thing that can write as this account is the announcement endpoint on
 * the manage API.
 */
const ANNOUNCEMENT_USERNAME = "Update Announcement";
const ANNOUNCEMENT_STATUS = "system";

function ensureAnnouncementAccount() {
  const existing = db
    .prepare("SELECT id, account_status FROM users WHERE username=?")
    .get(ANNOUNCEMENT_USERNAME);
  if (existing) {
    // Repair rather than recreate: if the row was seeded before this status
    // existed, or somebody flipped it, it must go back to being unusable.
    if (existing.account_status !== ANNOUNCEMENT_STATUS) {
      db.prepare("UPDATE users SET account_status=? WHERE id=?").run(ANNOUNCEMENT_STATUS, existing.id);
      logger.warn("announcement_account_status_repaired", { userId: existing.id });
    }
    return existing.id;
  }
  const unusablePassword = crypto.randomBytes(32).toString("hex");
  const id = Number(
    db
      .prepare(
        `INSERT INTO users (user_code, username, password_hash, color, is_admin, session_id, last_login_ip, account_status)
         VALUES (?, ?, ?, ?, 0, '', '', ?)`
      )
      .run(
        generateUniqueUserCode(),
        ANNOUNCEMENT_USERNAME,
        bcrypt.hashSync(unusablePassword, 12),
        "#2E8168",
        ANNOUNCEMENT_STATUS
      ).lastInsertRowid
  );
  logger.info("announcement_account_created", { userId: id });
  return id;
}

function getAnnouncementAccount() {
  return (
    db
      .prepare("SELECT id, user_code, username, color, avatar_url FROM users WHERE username=? AND account_status=?")
      .get(ANNOUNCEMENT_USERNAME, ANNOUNCEMENT_STATUS) || null
  );
}

function seedManageAdminIfEmpty() {
  const count = db.prepare("SELECT COUNT(*) AS c FROM manage_admins").get().c;
  if (count > 0) return;

  if (!RUNTIME.bootstrapManageUsername || !RUNTIME.bootstrapManagePassword) {
    logger.warn("manage_bootstrap_missing", {
      required: "MANAGE_BOOTSTRAP_USERNAME,MANAGE_BOOTSTRAP_PASSWORD",
    });
    return;
  }

  db.prepare(
    "INSERT INTO manage_admins (username, password_hash, session_id, updated_at) VALUES (?, ?, ?, ?)"
  ).run(
    RUNTIME.bootstrapManageUsername,
    bcrypt.hashSync(RUNTIME.bootstrapManagePassword, 12),
    "",
    Date.now()
  );
}

  function initialize() {
    seedUsersIfEmpty();
    ensureUserCodes();
    migrateLegacyAuthState();
    seedManageAdminIfEmpty();
    ensureAnnouncementAccount();
  }

  return {
    initialize,
    generateUniqueUserCode,
    generateUniqueGroupCode,
    ensureGroupCodes,
    ensureGroupAdmins,
    getAnnouncementAccount,
    ANNOUNCEMENT_USERNAME,
    ANNOUNCEMENT_STATUS,
  };
}

module.exports = { createBootstrapService };
