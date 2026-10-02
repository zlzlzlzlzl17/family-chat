"use strict";

function createConversationSummaryService(options) {
  const {
    db, fs, path, dbPath, uploadDir, getConversationMembers, getUserById,
    isSocketOnline, getClients,
  } = options;
  const DB_PATH = dbPath;
  const UPLOAD_DIR = uploadDir;

function getConversationTitleForUser(conversation, userId) {
  if (conversation.kind !== "direct") return conversation.title;
  const other = getConversationMembers(conversation.id).find((member) => member.id !== userId);
  return other?.username || conversation.title;
}

function getConversationAvatarForUser(conversation, userId) {
  if (conversation.kind !== "direct") return conversation.avatar_url || "";
  const other = getConversationMembers(conversation.id).find((member) => member.id !== userId);
  return other?.avatar_url || "";
}

function buildConversationPreview(message) {
  if (!message) return "";
  if (message.kind === "recalled") return "Message recalled";
  if (message.kind === "attachment_cleared") return "Attachment removed";
  if (message.kind === "security_notice") return "Safety code changed";
  if (message.kind === "text") return message.e2ee ? "Encrypted message" : String(message.payload || "").slice(0, 80);
  if (message.kind === "image" || message.kind === "photo") return "Image";
  if (message.kind === "audio") return "Voice note";
  if (message.kind === "file") return "File";
  return message.kind || "";
}

function getConversationSummaries(userId) {
  const rows = db
    .prepare(
      `
      SELECT c.id, c.kind, c.slug, c.group_code, c.title, c.avatar_url,
             COALESCE(crs.last_read_message_id, 0) AS last_read_message_id
      FROM conversations c
      JOIN conversation_members m ON m.conversation_id = c.id
      LEFT JOIN conversation_read_states crs
        ON crs.conversation_id = c.id
       AND crs.user_id = ?
      WHERE m.user_id = ?
      ORDER BY CASE WHEN c.slug = 'familychat' THEN 0 ELSE 1 END, c.id
    `
    )
    .all(userId, userId);

  return rows.map((conversation) => {
    const lastMessage = db
      .prepare(
        `
        SELECT id, conversation_id, ts, expires_at, username, color, kind, payload, e2ee, reply_to, mentions
        FROM messages
        WHERE conversation_id = ?
        ORDER BY id DESC
        LIMIT 1
      `
      )
      .get(conversation.id);
    const unreadCount = db
      .prepare(
        `
        SELECT COUNT(*) AS c
        FROM messages
        WHERE conversation_id = ?
          AND id > ?
          AND user_id != ?
          AND kind IN ('text', 'image', 'photo', 'file', 'audio')
      `
      )
      .get(conversation.id, conversation.last_read_message_id || 0, userId).c;

    const title = getConversationTitleForUser(conversation, userId);
    const avatarUrl = getConversationAvatarForUser(conversation, userId);
    const directOther = conversation.kind === "direct"
      ? getConversationMembers(conversation.id).find((member) => member.id !== userId)
      : null;

    return {
      id: conversation.id,
      kind: conversation.kind,
      slug: conversation.slug,
      group_code: conversation.group_code || "",
      title,
      avatar_url: avatarUrl,
      direct_user_code: directOther?.user_code || "",
      direct_username: directOther?.username || "",
      last_message_ts: lastMessage?.ts || 0,
      last_message_preview: buildConversationPreview(lastMessage),
      unread_count: unreadCount,
      last_read_message_id: conversation.last_read_message_id || 0,
    };
  });
}

function getOnlineUsers() {
  const unique = new Map();
  const now = Date.now();
  for (const ws of getClients()) {
    if (!isSocketOnline(ws, now)) continue;
    if (!unique.has(ws.auth.uid)) {
      const user = getUserById(ws.auth.uid);
      unique.set(ws.auth.uid, {
        id: ws.auth.uid,
        user_code: user?.user_code || ws.auth.user_code || "",
        username: user?.username || ws.auth.username,
        color: user?.color || ws.auth.color,
        is_admin: !!(user?.is_admin ?? ws.auth.is_admin),
        ip: ws.clientIp || "",
        client_type: ws.auth.client_type || "mobile",
      });
    }
  }
  return Array.from(unique.values()).sort((a, b) => a.username.localeCompare(b.username));
}

function getPathSize(targetPath) {
  if (!fs.existsSync(targetPath)) return 0;
  const stat = fs.statSync(targetPath);
  if (!stat.isDirectory()) return stat.size;
  let total = 0;
  for (const entry of fs.readdirSync(targetPath, { withFileTypes: true })) {
    total += getPathSize(path.join(targetPath, entry.name));
  }
  return total;
}

function getStorageSummary() {
  const stats = fs.statfsSync(path.dirname(DB_PATH));
  const blockSize = Number(stats.bsize || stats.frsize || 0);
  const totalBytes = Number(stats.blocks || 0) * blockSize;
  const freeBytes = Number(stats.bavail || stats.bfree || 0) * blockSize;
  const usedBytes = Math.max(totalBytes - freeBytes, 0);
  return {
    total_bytes: totalBytes,
    used_bytes: usedBytes,
    available_bytes: freeBytes,
    uploads_bytes: getPathSize(UPLOAD_DIR),
    database_bytes: getPathSize(DB_PATH),
    project_path: path.dirname(DB_PATH),
  };
}

  return {
    getConversationTitleForUser, getConversationAvatarForUser, getConversationSummaries,
    getOnlineUsers, getStorageSummary,
  };
}

module.exports = { createConversationSummaryService };
