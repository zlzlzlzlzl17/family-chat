"use strict";

function createDeviceAccountService(options) {
  const {
    db, crypto, fs, path, uploadDir, deviceStatusTrusted, deviceStatusRevoked,
    getClientIp, generateUniqueUserCode, ensureReadState, ensureFamilyConversation,
    getUserById, getOwnedGroupsBlockingAccountDeletion, cleanupConversation,
    promoteGroupAdminIfNeeded, rotateAndBroadcastGroupKeyEpoch,
  } = options;
  const UPLOAD_DIR = uploadDir;
  const DEVICE_STATUS_TRUSTED = deviceStatusTrusted;
  const DEVICE_STATUS_REVOKED = deviceStatusRevoked;

function isValidUsername(username) {
  return /^[A-Za-z0-9_\-\u4e00-\u9fff]{2,24}$/.test(String(username || "").trim());
}

function pickUserColor() {
  const palette = ["#128c7e", "#34b7f1", "#f39c12", "#8e44ad", "#e67e22", "#16a085"];
  return palette[crypto.randomInt(0, palette.length)];
}

function normalizeDeviceId(value) {
  return String(value || "").trim().slice(0, 80);
}

function isValidDeviceId(value) {
  return /^[A-Za-z0-9._:-]{8,80}$/.test(normalizeDeviceId(value));
}

function getDevice(userId, deviceId) {
  return db.prepare("SELECT * FROM devices WHERE user_id=? AND device_id=?").get(userId, deviceId);
}

function ensureLoginDevice(user, req, requestedDeviceId) {
  let deviceId = normalizeDeviceId(requestedDeviceId);
  if (!isValidDeviceId(deviceId)) {
    const legacy = db
      .prepare(
        `
          SELECT device_id
          FROM devices
          WHERE user_id=? AND status='trusted'
          ORDER BY last_seen_at DESC
          LIMIT 1
        `
      )
      .get(user.id);
    deviceId = legacy?.device_id || "";
  }
  if (!isValidDeviceId(deviceId)) return null;

  const now = Date.now();
  const existing = getDevice(user.id, deviceId);
  if (existing?.status === DEVICE_STATUS_REVOKED) {
    return { ...existing, denied: true };
  }
  const platform = String(req.body?.platform || "android").trim().slice(0, 32) || "android";
  const deviceName = String(req.body?.device_name || "").trim().slice(0, 120);
  const manufacturer = String(req.body?.manufacturer || "").trim().slice(0, 80);
  const model = String(req.body?.model || "").trim().slice(0, 120);
  if (!existing) {
    db.prepare(
      `
        INSERT INTO devices (
          user_id, device_id, platform, device_name, manufacturer, model, status,
          approved_by_device_id, approved_by_admin, created_at, approved_at,
          revoked_at, last_seen_at, last_ip
        )
        VALUES (?, ?, ?, ?, ?, ?, 'pending', '', '', ?, 0, 0, ?, ?)
      `
    ).run(
      user.id,
      deviceId,
      platform,
      deviceName,
      manufacturer,
      model,
      now,
      now,
      getClientIp(req)
    );
  } else {
    db.prepare(
      `
        UPDATE devices
        SET platform=?, device_name=?, manufacturer=?, model=?, last_seen_at=?, last_ip=?
        WHERE user_id=? AND device_id=?
      `
    ).run(platform, deviceName, manufacturer, model, now, getClientIp(req), user.id, deviceId);
  }
  return { ...getDevice(user.id, deviceId), newly_created: !existing };
}

function normalizePublicKey(value) {
  return String(value || "").trim().replace(/\s+/g, "").slice(0, 4096);
}

function deviceKeyFingerprint(publicKey) {
  return crypto.createHash("sha256").update(String(publicKey || ""), "utf8").digest("hex");
}

function deviceIdentityToWire(row) {
  if (!row) return null;
  return {
    user_code: row.user_code || "",
    username: row.username || "",
    device_id: row.device_id || "",
    platform: row.platform || "",
    device_name: row.device_name || "",
    key_alg: row.key_alg || "",
    public_key: row.public_key || "",
    fingerprint: row.fingerprint || "",
    created_at: Number(row.created_at || 0),
    updated_at: Number(row.updated_at || 0),
    last_seen_at: Number(row.last_seen_at || 0),
    status: row.status || DEVICE_STATUS_TRUSTED,
    manufacturer: row.manufacturer || "",
    model: row.model || "",
    approved_at: Number(row.approved_at || 0),
    revoked_at: Number(row.revoked_at || 0),
  };
}

function getPendingRegistrationRequests() {
  return db
    .prepare(
      `
      SELECT id, requested_username, request_ip, status, review_note, created_at, reviewed_at, reviewed_by
      FROM registration_requests
      WHERE status='pending'
      ORDER BY created_at ASC
    `
    )
    .all();
}

function getPendingDevicesForManage() {
  return db
    .prepare(
      `
        SELECT d.user_id, d.device_id, d.platform, d.device_name, d.manufacturer,
               d.model, d.created_at, d.last_seen_at, d.last_ip,
               u.user_code, u.username,
               COALESCE(dik.fingerprint, '') AS fingerprint
        FROM devices d
        JOIN users u ON u.id=d.user_id
        LEFT JOIN device_identity_keys dik
          ON dik.user_id=d.user_id AND dik.device_id=d.device_id
        WHERE d.status='pending'
        ORDER BY d.created_at ASC
      `
    )
    .all()
    .map((row) => ({
      ...row,
      created_at: Number(row.created_at || 0),
      last_seen_at: Number(row.last_seen_at || 0),
    }));
}

function getPendingAccountDeletionRequests() {
  return db
    .prepare(
      `
      SELECT adr.id, adr.user_id, adr.username_snapshot, adr.user_code_snapshot,
             adr.request_ip, adr.status, adr.review_note, adr.created_at,
             u.username, u.user_code, u.last_login_ip
      FROM account_deletion_requests adr
      LEFT JOIN users u ON u.id = adr.user_id
      WHERE adr.status='pending'
      ORDER BY adr.created_at ASC
    `
    )
    .all()
    .map((row) => ({
      id: row.id,
      user_id: row.user_id,
      username: row.username || row.username_snapshot || "",
      user_code: row.user_code || row.user_code_snapshot || "",
      request_ip: row.request_ip || "",
      last_login_ip: row.last_login_ip || "",
      created_at: Number(row.created_at || 0),
    }));
}

function getRegisteredUsersForManage() {
  return db
    .prepare(
      `
      SELECT id, user_code, username, is_admin, last_login_ip, color, avatar_url
      FROM users
      WHERE account_status != 'system'
      ORDER BY username
    `
    )
    .all()
    .map((row) => ({
      id: row.id,
      user_code: row.user_code,
      username: row.username,
      is_admin: !!row.is_admin,
      last_login_ip: row.last_login_ip || "",
      color: row.color,
      avatar_url: row.avatar_url || "",
    }));
}

function finalizeApprovedRegistration(requestId, reviewedBy) {
  const request = db
    .prepare(
      `
      SELECT id, requested_username, password_hash, request_ip, status, request_token_hash
      FROM registration_requests
      WHERE id=?
    `
    )
    .get(requestId);
  if (!request || request.status !== "pending") return null;
  if (db.prepare("SELECT 1 AS ok FROM users WHERE username=?").get(request.requested_username)) {
    db.prepare(
      "UPDATE registration_requests SET status='rejected', review_note=?, reviewed_at=?, reviewed_by=? WHERE id=?"
    ).run("username_taken", Date.now(), reviewedBy, request.id);
    return { error: "username_taken" };
  }

  const info = {
    user_code: generateUniqueUserCode(),
    username: request.requested_username,
    password_hash: request.password_hash,
    color: pickUserColor(),
    request_ip: request.request_ip || "",
  };

  const insertResult = db
    .prepare(
      `
      INSERT INTO users (
        user_code, username, password_hash, color, avatar_url, avatar_file,
        is_admin, session_id, last_login_ip, account_status, password_changed_at
      )
      VALUES (?, ?, ?, ?, '', '', 0, '', ?, 'active', ?)
    `
  )
    .run(info.user_code, info.username, info.password_hash, info.color, info.request_ip, Date.now());
  const userId = Number(insertResult.lastInsertRowid);
  ensureReadState(userId);
  ensureFamilyConversation();
  db.prepare(
    `
      UPDATE registration_requests
      SET status='approved', review_note='', reviewed_at=?, reviewed_by=?, approved_user_code=?
      WHERE id=?
    `
  ).run(Date.now(), reviewedBy, info.user_code, request.id);
  return getUserById(userId);
}

function deleteUserAccount(userId) {
  const user = getUserById(userId);
  if (!user) return null;

  const blockingOwnedGroups = getOwnedGroupsBlockingAccountDeletion(userId);
  if (blockingOwnedGroups.length) {
    return { error: "owner_groups_block_deletion", groups: blockingOwnedGroups };
  }

  return db.transaction(() => {
    const directConversations = db
      .prepare(
        `
        SELECT c.id
        FROM conversations c
        JOIN conversation_members m ON m.conversation_id = c.id
        WHERE m.user_id=? AND c.kind='direct'
      `
      )
      .all(userId);
    for (const conversation of directConversations) {
      cleanupConversation(conversation.id);
    }

    const groupConversations = db
      .prepare(
        `
        SELECT c.id
        FROM conversations c
        JOIN conversation_members m ON m.conversation_id = c.id
        WHERE m.user_id=? AND c.kind='group'
      `
      )
      .all(userId);
    for (const conversation of groupConversations) {
      db.prepare("DELETE FROM conversation_members WHERE conversation_id=? AND user_id=?").run(conversation.id, userId);
      db.prepare("DELETE FROM conversation_read_states WHERE conversation_id=? AND user_id=?").run(conversation.id, userId);
      db.prepare("DELETE FROM conversation_delivery_states WHERE conversation_id=? AND user_id=?").run(conversation.id, userId);
      const count = db
        .prepare("SELECT COUNT(*) AS c FROM conversation_members WHERE conversation_id=?")
        .get(conversation.id).c;
      if (Number(count || 0) <= 0) {
        cleanupConversation(conversation.id);
      } else {
        promoteGroupAdminIfNeeded(conversation.id);
        rotateAndBroadcastGroupKeyEpoch(conversation.id);
      }
    }

    db.prepare("DELETE FROM contact_requests WHERE requester_id=? OR target_user_id=?").run(userId, userId);
    db.prepare("DELETE FROM group_join_requests WHERE requester_id=? OR reviewed_by=?").run(userId, userId);
    db.prepare("DELETE FROM group_admin_requests WHERE requester_id=? OR target_user_id=? OR reviewed_by=?").run(userId, userId, userId);
    db.prepare("DELETE FROM group_sender_key_envelopes WHERE sender_user_id=? OR recipient_user_id=?").run(userId, userId);
    db.prepare("DELETE FROM direct_one_time_prekeys WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM direct_prekeys WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM device_identity_keys WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM device_presence WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM auth_sessions WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM devices WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM fcm_tokens WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM push_subs WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM read_states WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM conversation_read_states WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM conversation_delivery_states WHERE user_id=?").run(userId);
    db.prepare("DELETE FROM pending_call_invites WHERE caller_id=? OR callee_id=?").run(userId, userId);
    db.prepare("DELETE FROM account_deletion_requests WHERE user_id=?").run(userId);

    if (user.avatar_file) {
      try {
        fs.unlinkSync(path.join(UPLOAD_DIR, user.avatar_file));
      } catch {}
    }

    db.prepare("DELETE FROM users WHERE id=?").run(userId);
    return user;
  })();
}

function finalizeApprovedAccountDeletion(requestId, reviewedBy) {
  const request = db
    .prepare("SELECT * FROM account_deletion_requests WHERE id=? AND status='pending'")
    .get(requestId);
  if (!request) return null;
  const deletedUser = deleteUserAccount(request.user_id);
  if (deletedUser?.error) return deletedUser;
  db.prepare(
    "UPDATE account_deletion_requests SET status='approved', review_note='', reviewed_at=?, reviewed_by=? WHERE id=?"
  ).run(Date.now(), reviewedBy, requestId);
  return deletedUser || {
    id: request.user_id,
    username: request.username_snapshot || "",
    user_code: request.user_code_snapshot || "",
  };
}

  return {
    isValidUsername, normalizeDeviceId, isValidDeviceId, getDevice, ensureLoginDevice,
    normalizePublicKey, deviceKeyFingerprint, deviceIdentityToWire, getPendingRegistrationRequests,
    getPendingDevicesForManage, getPendingAccountDeletionRequests, getRegisteredUsersForManage,
    finalizeApprovedRegistration, finalizeApprovedAccountDeletion,
  };
}

module.exports = { createDeviceAccountService };
