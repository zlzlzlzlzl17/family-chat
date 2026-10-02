"use strict";

function createDeviceSecurityService(options) {
  const {
    db, deviceStatusTrusted, deviceStatusRevoked, getUserById, getDevice,
    getGroupConversationsForUser, rotateGroupKeyEpochsForUser, conversationExpiresAt,
    messageRowToWire, broadcastToConversation, broadcastToUser, forceLogoutDevice,
  } = options;
  const DEVICE_STATUS_TRUSTED = deviceStatusTrusted;
  const DEVICE_STATUS_REVOKED = deviceStatusRevoked;

function insertSystemMessage(conversationId, userId, kind, payloadObject) {
  const user = getUserById(userId);
  const now = Date.now();
  const conversation = db.prepare("SELECT * FROM conversations WHERE id=?").get(conversationId);
  if (!conversation || !user) return null;
  const payload = JSON.stringify(payloadObject || {});
  const result = db
    .prepare(
      `
      INSERT INTO messages (conversation_id, ts, expires_at, user_id, username, color, kind, payload, reply_to, mentions, e2ee)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, '', '[]', 0)
    `
    )
    .run(conversationId, now, conversationExpiresAt(conversation, now), user.id, user.username, user.color, kind, payload);
  const row = db
    .prepare(
      `
      SELECT id, conversation_id, ts, expires_at, username, color, kind, payload, e2ee, reply_to, mentions
      FROM messages
      WHERE id=?
    `
    )
    .get(result.lastInsertRowid);
  const message = messageRowToWire(row);
  broadcastToConversation(conversationId, { type: "chat", ...message });
  return message;
}

function emitDeviceSessionNotices(userId, deviceId, deviceName, noticeType, oldFingerprint = "", newFingerprint = "") {
  if (!noticeType) return;
  const user = getUserById(userId);
  if (!user) return;
  const directConversations = db
    .prepare(
      `
      SELECT c.id
      FROM conversations c
      JOIN conversation_members m ON m.conversation_id = c.id
      WHERE c.kind='direct' AND m.user_id=?
    `
    )
    .all(userId);
  const groupConversations = getGroupConversationsForUser(userId);
  const payload = {
    type: noticeType,
    user_code: user.user_code || "",
    username: user.username,
    device_id: deviceId,
    device_name: deviceName || "",
    old_fingerprint: oldFingerprint || "",
    new_fingerprint: newFingerprint || "",
  };
  for (const conversation of directConversations) {
    insertSystemMessage(conversation.id, userId, "security_notice", payload);
  }
  for (const conversation of groupConversations) {
    insertSystemMessage(conversation.id, userId, "security_notice", payload);
  }
  if (noticeType === "safety_code_changed" || noticeType === "device_added" || noticeType === "device_removed") {
    rotateGroupKeyEpochsForUser(userId);
  }
}

function emitDeviceSafetyChangeNotices(userId, deviceId, deviceName, oldFingerprint, newFingerprint) {
  if (!oldFingerprint || !newFingerprint || oldFingerprint === newFingerprint) return;
  emitDeviceSessionNotices(userId, deviceId, deviceName, "safety_code_changed", oldFingerprint, newFingerprint);
}

function emitDeviceAddedNotices(userId, deviceId, deviceName, fingerprint) {
  emitDeviceSessionNotices(userId, deviceId, deviceName, "device_added", "", fingerprint || "");
}

function emitDeviceRemovedNotices(userId, deviceId, deviceName, fingerprint) {
  emitDeviceSessionNotices(userId, deviceId, deviceName, "device_removed", fingerprint || "", "");
}

function broadcastDirectPeerKeysChanged(userId, deviceId) {
  const user = getUserById(userId);
  if (!user) return;
  const directConversations = db
    .prepare(
      `
      SELECT c.id
      FROM conversations c
      JOIN conversation_members m ON m.conversation_id = c.id
      WHERE c.kind='direct' AND m.user_id=?
      `
    )
    .all(userId);
  for (const conversation of directConversations) {
    broadcastToConversation(conversation.id, {
      type: "direct_peer_keys_changed",
      conversation_id: conversation.id,
      user_code: user.user_code || "",
      device_id: deviceId,
    });
  }
}

function approveDevice(userId, deviceId, approvedByDeviceId = "", approvedByAdmin = "") {
  const device = getDevice(userId, deviceId);
  if (!device || device.status === DEVICE_STATUS_REVOKED) return null;
  if (device.status === DEVICE_STATUS_TRUSTED) return device;
  const keysReady = db
    .prepare(
      `
        SELECT 1 AS ok
        FROM device_identity_keys dik
        JOIN direct_prekeys dp ON dp.user_id=dik.user_id AND dp.device_id=dik.device_id
        WHERE dik.user_id=? AND dik.device_id=?
      `
    )
    .get(userId, deviceId);
  if (!keysReady) return { error: "device_keys_not_ready" };
  const now = Date.now();
  db.transaction(() => {
    db.prepare(
      `
        UPDATE devices
        SET status='trusted', approved_by_device_id=?, approved_by_admin=?,
            approved_at=?, revoked_at=0
        WHERE user_id=? AND device_id=? AND status='pending'
      `
    ).run(approvedByDeviceId, approvedByAdmin, now, userId, deviceId);
    db.prepare(
      `
        UPDATE auth_sessions
        SET status='active', revoked_at=0
        WHERE user_id=? AND device_id=? AND status='pending'
      `
    ).run(userId, deviceId);
  })();
  const identity = db
    .prepare("SELECT device_name, fingerprint FROM device_identity_keys WHERE user_id=? AND device_id=?")
    .get(userId, deviceId);
  emitDeviceAddedNotices(
    userId,
    deviceId,
    identity?.device_name || device.device_name || "",
    identity?.fingerprint || ""
  );
  broadcastDirectPeerKeysChanged(userId, deviceId);
  broadcastToUser(userId, { type: "device_approved", device_id: deviceId });
  broadcastToUser(userId, { type: "devices_changed", device_id: deviceId });
  return getDevice(userId, deviceId);
}

function revokeDevice(userId, deviceId, reason = "device_revoked") {
  const device = getDevice(userId, deviceId);
  if (!device || device.status === DEVICE_STATUS_REVOKED) return null;
  const identity = db
    .prepare("SELECT device_name, fingerprint FROM device_identity_keys WHERE user_id=? AND device_id=?")
    .get(userId, deviceId);
  const now = Date.now();
  db.transaction(() => {
    db.prepare(
      "UPDATE devices SET status='revoked', revoked_at=?, last_seen_at=? WHERE user_id=? AND device_id=?"
    ).run(now, now, userId, deviceId);
    db.prepare(
      "UPDATE auth_sessions SET status='revoked', revoked_at=? WHERE user_id=? AND device_id=?"
    ).run(now, userId, deviceId);
    db.prepare(
      "DELETE FROM group_sender_key_envelopes WHERE (sender_user_id=? AND sender_device_id=?) OR (recipient_user_id=? AND recipient_device_id=?)"
    ).run(userId, deviceId, userId, deviceId);
    db.prepare("DELETE FROM direct_one_time_prekeys WHERE user_id=? AND device_id=?").run(userId, deviceId);
    db.prepare("DELETE FROM direct_prekeys WHERE user_id=? AND device_id=?").run(userId, deviceId);
    db.prepare("DELETE FROM device_identity_keys WHERE user_id=? AND device_id=?").run(userId, deviceId);
    db.prepare("DELETE FROM fcm_tokens WHERE user_id=? AND device_id=?").run(userId, deviceId);
    db.prepare("DELETE FROM device_presence WHERE user_id=? AND device_id=?").run(userId, deviceId);
  })();
  forceLogoutDevice(userId, deviceId, reason);
  if (device.status === DEVICE_STATUS_TRUSTED) {
    emitDeviceRemovedNotices(
      userId,
      deviceId,
      identity?.device_name || device.device_name || "",
      identity?.fingerprint || ""
    );
  }
  broadcastToUser(userId, { type: "devices_changed", device_id: deviceId });
  return device;
}

  return {
    emitDeviceSafetyChangeNotices, emitDeviceAddedNotices, emitDeviceRemovedNotices,
    broadcastDirectPeerKeysChanged, approveDevice, revokeDevice,
  };
}

module.exports = { createDeviceSecurityService };
