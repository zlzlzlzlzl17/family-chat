"use strict";

function createMessageService({ db, maxReplyLength, maxMentions, retentionMs, getFamilyConversationId }) {
  const MAX_REPLY_LEN = maxReplyLength;
  const MAX_MENTIONS = maxMentions;
  const RETENTION_MS = retentionMs;

function safeJsonParse(text, fallback) {
  try {
    return JSON.parse(text);
  } catch {
    return fallback;
  }
}

function sanitizeReplyTo(raw) {
  if (!raw) return "";
  if (typeof raw !== "string") return "";
  const text = raw.slice(0, MAX_REPLY_LEN);
  const parsed = safeJsonParse(text, null);
  if (!parsed || typeof parsed !== "object") return "";

  const out = {
    id: Number(parsed.id) || 0,
    username: typeof parsed.username === "string" ? parsed.username.slice(0, 40) : "",
    color: typeof parsed.color === "string" ? parsed.color.slice(0, 20) : "",
    preview: typeof parsed.preview === "string" ? parsed.preview.slice(0, 160) : "",
  };

  return out.id ? JSON.stringify(out) : "";
}

function getAllowedUsernames(conversationId = 0) {
  if (conversationId > 0) {
    return new Set(
      db
        .prepare(
          `
          SELECT u.username
          FROM conversation_members cm
          JOIN users u ON u.id = cm.user_id
          WHERE cm.conversation_id = ?
        `
        )
        .all(conversationId)
        .map((row) => row.username)
    );
  }
  return new Set(db.prepare("SELECT username FROM users").all().map((row) => row.username));
}

function sanitizeMentions(raw, conversationId = 0) {
  const parsed = Array.isArray(raw) ? raw : safeJsonParse(raw || "[]", []);
  if (!Array.isArray(parsed)) return "[]";

  const allowed = getAllowedUsernames(Number(conversationId) || 0);
  const unique = [];

  for (const value of parsed) {
    if (typeof value !== "string") continue;
    const username = value.trim().replace(/^@+/, "").slice(0, 32);
    if (!username || !allowed.has(username) || unique.includes(username)) continue;
    unique.push(username);
    if (unique.length >= MAX_MENTIONS) break;
  }

  return JSON.stringify(unique);
}

function extractAttachmentFile(payload, kind) {
  const data = safeJsonParse(payload, null);
  if (!data || typeof data !== "object") return null;
  if (kind === "photo" && typeof data.file === "string") return data.file;
  if (data.attachment && typeof data.attachment.file === "string") return data.attachment.file;
  return null;
}

function messageRowToWire(row) {
  const currentUser =
    Number(row.user_id || 0) > 0
      ? db.prepare("SELECT user_code, username, color, avatar_url, is_admin FROM users WHERE id=?").get(row.user_id)
      : null;
  return {
    id: row.id,
    conversation_id: row.conversation_id || getFamilyConversationId(),
    ts: row.ts,
    expires_at: row.expires_at,
    user_code: currentUser?.user_code || "",
    username: currentUser?.username || row.username,
    color: currentUser?.color || row.color,
    avatar_url: currentUser?.avatar_url || "",
    kind: row.kind,
    payload: row.payload,
    e2ee: row.e2ee,
    reply_to: row.reply_to || "",
    mentions: safeJsonParse(row.mentions || "[]", []),
    client_message_id: row.client_message_id || "",
  };
}

function conversationExpiresAt(conversation, now = Date.now()) {
  const ttl = Number(conversation?.message_ttl_ms || 0);
  return ttl > 0 ? now + ttl : now + RETENTION_MS;
}

  return {
    safeJsonParse, sanitizeReplyTo, sanitizeMentions, extractAttachmentFile,
    messageRowToWire, conversationExpiresAt,
  };
}

module.exports = { createMessageService };
