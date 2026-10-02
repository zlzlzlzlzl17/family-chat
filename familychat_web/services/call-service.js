"use strict";

function createCallService(options) {
  const {
    db, callInviteTtlMs, callInviteFallbackDelayMs, stunUrls, turnUrls,
    turnUsername, turnCredential, sendFcmIncomingCallToUser, logCallDebug,
  } = options;
  const CALL_INVITE_TTL_MS = callInviteTtlMs;
  const CALL_INVITE_FCM_FALLBACK_DELAY_MS = callInviteFallbackDelayMs;
  const CALL_STUN_URLS = stunUrls;
  const CALL_TURN_URLS = turnUrls;
  const CALL_TURN_USERNAME = turnUsername;
  const CALL_TURN_CREDENTIAL = turnCredential;

function upsertPendingCallInvite(conversationId, caller, callee) {
  const now = Date.now();
  db.prepare(
    `
      INSERT INTO pending_call_invites (
        conversation_id,
        caller_id,
        callee_id,
        caller_user_code,
        caller_username,
        created_at,
        expires_at
      )
      VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(callee_id, conversation_id) DO UPDATE SET
        caller_id=excluded.caller_id,
        caller_user_code=excluded.caller_user_code,
        caller_username=excluded.caller_username,
        created_at=excluded.created_at,
        expires_at=excluded.expires_at
    `
  ).run(
    conversationId,
    caller.id,
    callee.id,
    caller.user_code || "",
    caller.username || "",
    now,
    now + CALL_INVITE_TTL_MS
  );
}

function takePendingCallInvitesForUser(userId) {
  const now = Date.now();
  db.prepare("DELETE FROM pending_call_invites WHERE expires_at <= ?").run(now);
  const rows = db
    .prepare(
      `
        SELECT id, conversation_id, caller_id, callee_id, caller_user_code, caller_username, created_at, expires_at
        FROM pending_call_invites
        WHERE callee_id = ?
        ORDER BY created_at ASC
      `
    )
    .all(userId);
  if (rows.length) {
    db.prepare("DELETE FROM pending_call_invites WHERE callee_id = ?").run(userId);
  }
  return rows;
}

function clearPendingCallInvitesForConversation(conversationId) {
  db.prepare("DELETE FROM pending_call_invites WHERE conversation_id = ?").run(conversationId);
  clearPendingCallFallback(conversationId);
}

const pendingCallFallbackTimers = new Map();

function pendingCallFallbackKey(conversationId, calleeId) {
  return `${conversationId}:${calleeId}`;
}

function clearPendingCallFallback(conversationId, calleeId = null) {
  if (calleeId == null) {
    for (const [key, timer] of pendingCallFallbackTimers.entries()) {
      if (!key.startsWith(`${conversationId}:`)) continue;
      clearTimeout(timer);
      pendingCallFallbackTimers.delete(key);
    }
    return;
  }
  const key = pendingCallFallbackKey(conversationId, calleeId);
  const timer = pendingCallFallbackTimers.get(key);
  if (timer) {
    clearTimeout(timer);
    pendingCallFallbackTimers.delete(key);
  }
}

function schedulePendingCallFallback(conversationId, caller, callee, createdAt) {
  const key = pendingCallFallbackKey(conversationId, callee.id);
  clearPendingCallFallback(conversationId, callee.id);
  const timer = setTimeout(async () => {
    pendingCallFallbackTimers.delete(key);
    const stillPending = db
      .prepare("SELECT 1 FROM pending_call_invites WHERE conversation_id = ? AND callee_id = ? LIMIT 1")
      .get(conversationId, callee.id);
    if (!stillPending) return;
    const fcmResult = await sendFcmIncomingCallToUser(callee.id, {
      conversationId,
      peerUserCode: caller.user_code || "",
      peerUsername: caller.username,
      createdAt,
    });
    logCallDebug("fallback_fcm_attempted", {
      conversationId,
      callerId: caller.id,
      calleeId: callee.id,
      delivered: !!fcmResult?.delivered,
      reason: fcmResult?.reason || "",
    });
  }, CALL_INVITE_FCM_FALLBACK_DELAY_MS);
  pendingCallFallbackTimers.set(key, timer);
}

function getCallIceServers() {
  const items = [];
  if (CALL_STUN_URLS.length) {
    items.push({ urls: CALL_STUN_URLS });
  }
  if (CALL_TURN_URLS.length && CALL_TURN_USERNAME && CALL_TURN_CREDENTIAL) {
    items.push({
      urls: CALL_TURN_URLS,
      username: CALL_TURN_USERNAME,
      credential: CALL_TURN_CREDENTIAL,
    });
  }
  return items;
}

  function stop() {
    for (const timer of pendingCallFallbackTimers.values()) clearTimeout(timer);
    pendingCallFallbackTimers.clear();
  }

  return {
    upsertPendingCallInvite, takePendingCallInvitesForUser, clearPendingCallInvitesForConversation,
    clearPendingCallFallback, schedulePendingCallFallback, getCallIceServers, stop,
  };
}

module.exports = { createCallService };
