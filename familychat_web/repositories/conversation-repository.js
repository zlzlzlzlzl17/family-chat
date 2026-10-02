"use strict";

function createConversationRepository(db) {
function getFamilyConversationId() {
  const row = db.prepare("SELECT id FROM conversations WHERE slug='familychat'").get();
  return Number(row?.id || 0);
}

function getUsers() {
  return db
    .prepare("SELECT id, user_code, username, color, avatar_url, is_admin, last_login_ip FROM users ORDER BY username")
    .all()
    .map((row) => ({
      id: row.id,
      user_code: row.user_code,
      username: row.username,
      color: row.color,
      avatar_url: row.avatar_url || "",
      is_admin: !!row.is_admin,
      last_login_ip: row.last_login_ip || "",
    }));
}

function getReadStates() {
  const familyConversationId = getFamilyConversationId();
  return getConversationReadStates(familyConversationId);
}

function getConversationReadStates(conversationId) {
  return db
    .prepare(
      `
        SELECT u.user_code, u.username, COALESCE(r.last_read_message_id, 0) AS last_read_message_id
        FROM conversation_members m
        JOIN users u ON u.id = m.user_id
        LEFT JOIN conversation_read_states r
          ON r.user_id = u.id
         AND r.conversation_id = ?
        WHERE m.conversation_id = ?
        ORDER BY u.username
    `
    )
    .all(conversationId, conversationId);
}

function getConversationDeliveryStates(conversationId) {
  return db
    .prepare(
      `
        SELECT u.user_code, u.username, COALESCE(d.last_delivered_message_id, 0) AS last_delivered_message_id
        FROM conversation_members m
        JOIN users u ON u.id = m.user_id
        LEFT JOIN conversation_delivery_states d
          ON d.user_id = u.id
         AND d.conversation_id = ?
        WHERE m.conversation_id = ?
        ORDER BY u.username
    `
    )
    .all(conversationId, conversationId);
}

function upsertReadState(userId, lastReadMessageId) {
  const familyConversationId = getFamilyConversationId();
  upsertConversationReadState(familyConversationId, userId, lastReadMessageId);
  db.prepare(
    `
    INSERT INTO read_states (user_id, last_read_message_id, updated_at)
    VALUES (?, ?, ?)
    ON CONFLICT(user_id) DO UPDATE SET
      last_read_message_id = CASE
        WHEN excluded.last_read_message_id > read_states.last_read_message_id
        THEN excluded.last_read_message_id
        ELSE read_states.last_read_message_id
      END,
      updated_at = CASE
        WHEN excluded.last_read_message_id > read_states.last_read_message_id
        THEN excluded.updated_at
        ELSE read_states.updated_at
      END
  `
  ).run(userId, lastReadMessageId, Date.now());
}

function upsertConversationReadState(conversationId, userId, lastReadMessageId) {
  db.prepare(
    `
    INSERT INTO conversation_read_states (conversation_id, user_id, last_read_message_id, updated_at)
    VALUES (?, ?, ?, ?)
    ON CONFLICT(conversation_id, user_id) DO UPDATE SET
      last_read_message_id = CASE
        WHEN excluded.last_read_message_id > conversation_read_states.last_read_message_id
        THEN excluded.last_read_message_id
        ELSE conversation_read_states.last_read_message_id
      END,
      updated_at = CASE
        WHEN excluded.last_read_message_id > conversation_read_states.last_read_message_id
        THEN excluded.updated_at
        ELSE conversation_read_states.updated_at
      END
  `
  ).run(conversationId, userId, lastReadMessageId, Date.now());

  if (conversationId === getFamilyConversationId()) {
    db.prepare(
      `
      INSERT INTO read_states (user_id, last_read_message_id, updated_at)
      VALUES (?, ?, ?)
      ON CONFLICT(user_id) DO UPDATE SET
        last_read_message_id = CASE
          WHEN excluded.last_read_message_id > read_states.last_read_message_id
          THEN excluded.last_read_message_id
          ELSE read_states.last_read_message_id
        END,
        updated_at = CASE
          WHEN excluded.last_read_message_id > read_states.last_read_message_id
          THEN excluded.updated_at
          ELSE read_states.updated_at
        END
    `
    ).run(userId, lastReadMessageId, Date.now());
  }
}

function upsertConversationDeliveryState(conversationId, userId, lastDeliveredMessageId) {
  db.prepare(
    `
    INSERT INTO conversation_delivery_states (conversation_id, user_id, last_delivered_message_id, updated_at)
    VALUES (?, ?, ?, ?)
    ON CONFLICT(conversation_id, user_id) DO UPDATE SET
      last_delivered_message_id = CASE
        WHEN excluded.last_delivered_message_id > conversation_delivery_states.last_delivered_message_id
        THEN excluded.last_delivered_message_id
        ELSE conversation_delivery_states.last_delivered_message_id
      END,
      updated_at = CASE
        WHEN excluded.last_delivered_message_id > conversation_delivery_states.last_delivered_message_id
        THEN excluded.updated_at
        ELSE conversation_delivery_states.updated_at
      END
  `
  ).run(conversationId, userId, lastDeliveredMessageId, Date.now());
}

function getUserById(userId) {
  return db
    .prepare("SELECT id, user_code, username, color, avatar_url, is_admin, last_login_ip FROM users WHERE id=?")
    .get(userId);
}

function getConversationForUser(userId, conversationId) {
  const resolvedId = Number(conversationId) || getFamilyConversationId();
  return db
    .prepare(
      `
      SELECT c.*
      FROM conversations c
      JOIN conversation_members m
        ON m.conversation_id = c.id
      WHERE c.id = ? AND m.user_id = ?
    `
    )
    .get(resolvedId, userId);
}

function getConversationMembers(conversationId) {
  return db
    .prepare(
      `
      SELECT u.id, u.user_code, u.username, u.color, u.avatar_url, u.is_admin, m.role, m.joined_at
      FROM conversation_members m
      JOIN users u ON u.id = m.user_id
      WHERE m.conversation_id = ?
      ORDER BY u.username
    `
    )
    .all(conversationId);
}

function getDirectConversationPeer(conversationId, userId) {
  return getConversationMembers(conversationId).find((member) => member.id !== userId) || null;
}

function getOwnedGroupsBlockingAccountDeletion(userId) {
  return db
    .prepare(
      `
      SELECT c.id, c.title, c.group_code, COUNT(other.user_id) AS other_member_count
      FROM conversations c
      JOIN conversation_members owner
        ON owner.conversation_id = c.id
       AND owner.user_id = ?
       AND owner.role = 'owner'
      LEFT JOIN conversation_members other
        ON other.conversation_id = c.id
       AND other.user_id != ?
      WHERE c.kind = 'group'
      GROUP BY c.id, c.title, c.group_code
      HAVING COUNT(other.user_id) > 0
      ORDER BY c.title, c.id
    `
    )
    .all(userId, userId);
}

  return {
    getUsers, getReadStates, getConversationReadStates, getConversationDeliveryStates,
    upsertReadState, upsertConversationReadState, upsertConversationDeliveryState,
    getUserById, getConversationForUser, getConversationMembers, getDirectConversationPeer,
    getOwnedGroupsBlockingAccountDeletion,
  };
}

module.exports = { createConversationRepository };
