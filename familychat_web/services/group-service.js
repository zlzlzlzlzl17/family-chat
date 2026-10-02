"use strict";

function createGroupService({ db, deleteAttachmentFiles, clearPendingCallInvitesForConversation }) {
function cleanupConversation(conversationId) {
  const rows = db
    .prepare("SELECT id, kind, payload FROM messages WHERE conversation_id=? AND kind IN ('photo', 'image', 'audio', 'file')")
    .all(conversationId);
  let deletedMessages = 0;
  const cleanupRows = () => {
    deletedMessages = db.prepare("DELETE FROM messages WHERE conversation_id=?").run(conversationId).changes;
    db.prepare("DELETE FROM conversation_read_states WHERE conversation_id=?").run(conversationId);
    db.prepare("DELETE FROM conversation_delivery_states WHERE conversation_id=?").run(conversationId);
    db.prepare("DELETE FROM group_sender_key_envelopes WHERE conversation_id=?").run(conversationId);
    db.prepare("DELETE FROM group_key_epochs WHERE conversation_id=?").run(conversationId);
    db.prepare("DELETE FROM group_join_requests WHERE conversation_id=?").run(conversationId);
    db.prepare("DELETE FROM group_admin_requests WHERE conversation_id=?").run(conversationId);
    db.prepare("DELETE FROM conversation_members WHERE conversation_id=?").run(conversationId);
    db.prepare("DELETE FROM conversations WHERE id=?").run(conversationId);
    clearPendingCallInvitesForConversation(conversationId);
  };
  if (db.inTransaction) cleanupRows();
  else db.transaction(cleanupRows)();
  const deletedFiles = deleteAttachmentFiles(rows);
  return { deletedFiles, deletedMessages };
}

function promoteGroupAdminIfNeeded(conversationId) {
  const group = db.prepare("SELECT id FROM conversations WHERE id=? AND kind='group'").get(conversationId);
  if (!group) return;
  const hasOwner = db
    .prepare("SELECT 1 AS ok FROM conversation_members WHERE conversation_id=? AND role='owner' LIMIT 1")
    .get(conversationId);
  if (hasOwner) return;
  const nextMember = db
    .prepare(
      `
        SELECT user_id
        FROM conversation_members
        WHERE conversation_id=?
        ORDER BY CASE role WHEN 'admin' THEN 0 ELSE 1 END, joined_at, user_id
        LIMIT 1
      `
    )
    .get(conversationId);
  if (nextMember) {
    db.prepare("UPDATE conversation_members SET role='owner' WHERE conversation_id=? AND user_id=?")
      .run(conversationId, nextMember.user_id);
  }
}

function clearConversationHistory(conversationId) {
  const rows = db
    .prepare("SELECT id, kind, payload FROM messages WHERE conversation_id=? AND kind IN ('photo', 'image', 'audio', 'file')")
    .all(conversationId);
  const deletedFiles = deleteAttachmentFiles(rows);
  const deletedMessages = db.prepare("DELETE FROM messages WHERE conversation_id=?").run(conversationId).changes;
  const now = Date.now();
  db.prepare("UPDATE conversation_read_states SET last_read_message_id=0, updated_at=? WHERE conversation_id=?").run(now, conversationId);
  db.prepare("UPDATE conversation_delivery_states SET last_delivered_message_id=0, updated_at=? WHERE conversation_id=?").run(now, conversationId);
  return { deletedFiles, deletedMessages };
}

function directConversationSlug(userAId, userBId) {
  const ids = [Number(userAId), Number(userBId)].sort((a, b) => a - b);
  return `direct:${ids[0]}:${ids[1]}`;
}

function getDirectConversationBetween(userAId, userBId) {
  return db.prepare("SELECT * FROM conversations WHERE kind='direct' AND slug=?").get(directConversationSlug(userAId, userBId));
}

function getGroupRole(userId, conversationId) {
  const member = db
    .prepare("SELECT role FROM conversation_members WHERE conversation_id=? AND user_id=?")
    .get(conversationId, userId);
  return member?.role || "";
}

function isGroupOwner(userId, conversationId) {
  return getGroupRole(userId, conversationId) === "owner";
}

function isGroupAdmin(userId, conversationId) {
  const role = getGroupRole(userId, conversationId);
  return role === "owner" || role === "admin";
}

function countGroupAdmins(conversationId) {
  const row = db
    .prepare("SELECT COUNT(*) AS c FROM conversation_members WHERE conversation_id=? AND role='admin'")
    .get(conversationId);
  return Number(row?.c || 0);
}

function groupAdminRequestToWire(row) {
  if (!row) return null;
  return {
    id: row.id,
    conversation_id: row.conversation_id,
    status: row.status,
    created_at: Number(row.created_at || 0),
    reviewed_at: Number(row.reviewed_at || 0),
    requester_user_code: row.requester_user_code || "",
    requester_username: row.requester_username || "",
    target_user_code: row.target_user_code || "",
    target_username: row.target_username || "",
  };
}

function isConversationMember(userId, conversationId) {
  return !!db
    .prepare("SELECT 1 AS ok FROM conversation_members WHERE conversation_id=? AND user_id=?")
    .get(conversationId, userId);
}

function normalizeUserCode(value) {
  return String(value || "").replace(/\D/g, "").slice(0, 8);
}

function normalizeGroupCode(value) {
  return String(value || "").replace(/\D/g, "").slice(0, 10);
}

function contactRequestToWire(row, viewerId) {
  if (!row) return null;
  const incoming = Number(row.target_user_id) === Number(viewerId);
  return {
    id: row.id,
    direction: incoming ? "incoming" : "outgoing",
    status: row.status,
    created_at: Number(row.created_at || 0),
    reviewed_at: Number(row.reviewed_at || 0),
    user_code: incoming ? row.requester_user_code : row.target_user_code,
    username: incoming ? row.requester_username : row.target_username,
    color: incoming ? row.requester_color : row.target_color,
    avatar_url: incoming ? row.requester_avatar_url || "" : row.target_avatar_url || "",
  };
}

function groupJoinRequestToWire(row, viewerId) {
  if (!row) return null;
  return {
    id: row.id,
    direction: Number(row.requester_id) === Number(viewerId) ? "outgoing" : "incoming",
    status: row.status,
    created_at: Number(row.created_at || 0),
    reviewed_at: Number(row.reviewed_at || 0),
    group_code: row.group_code || "",
    title: row.title || "",
    avatar_url: row.avatar_url || "",
    requester_user_code: row.requester_user_code || "",
    requester_username: row.requester_username || "",
    requester_color: row.requester_color || "",
    requester_avatar_url: row.requester_avatar_url || "",
  };
}

  return {
    cleanupConversation, promoteGroupAdminIfNeeded, clearConversationHistory,
    getDirectConversationBetween, getGroupRole, isGroupOwner, isGroupAdmin, countGroupAdmins,
    groupAdminRequestToWire, isConversationMember, normalizeUserCode, normalizeGroupCode,
    contactRequestToWire, groupJoinRequestToWire,
  };
}

module.exports = { createGroupService };
