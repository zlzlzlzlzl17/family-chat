"use strict";

function createFcmTokenRepository(db) {
  function countUnreadMessagesForUser(userId) {
    const row = db
      .prepare(
        `
        SELECT COUNT(*) AS c
        FROM messages m
        JOIN conversation_members cm ON cm.conversation_id = m.conversation_id
        LEFT JOIN conversation_read_states rs
          ON rs.conversation_id = m.conversation_id
         AND rs.user_id = ?
        WHERE cm.user_id = ?
          AND m.user_id != ?
          AND m.kind IN ('text', 'image', 'photo', 'file', 'audio')
          AND m.id > COALESCE(rs.last_read_message_id, 0)
      `
      )
      .get(userId, userId, userId);
    return Math.max(Number(row?.c || 0), 0);
  }

  function listConversationTokens(conversationId, senderUserId) {
    return db
      .prepare(
        `
        SELECT ft.token, ft.user_id, ft.locale
        FROM fcm_tokens ft
        JOIN conversation_members cm ON cm.user_id = ft.user_id
        JOIN devices d ON d.user_id=ft.user_id AND d.device_id=ft.device_id AND d.status='trusted'
        WHERE cm.conversation_id = ?
          AND ft.user_id != ?
      `
      )
      .all(conversationId, senderUserId);
  }

  function listUserTokens(userId) {
    return db
      .prepare(
        `
        SELECT ft.token, ft.locale
        FROM fcm_tokens ft
        JOIN devices d ON d.user_id=ft.user_id AND d.device_id=ft.device_id AND d.status='trusted'
        WHERE ft.user_id = ?
      `
      )
      .all(userId);
  }

  function listBlogNotificationTokens(userId) {
    return db
      .prepare(
        `
        SELECT ft.token, ft.locale
        FROM fcm_tokens ft
        JOIN devices d ON d.user_id=ft.user_id AND d.device_id=ft.device_id AND d.status='trusted'
        WHERE ft.user_id = ?
          AND COALESCE(ft.blog_notifications_enabled, 1) = 1
      `
      )
      .all(userId);
  }

  function markSuccess(token) {
    db.prepare(
      `
        UPDATE fcm_tokens
        SET last_success_at=?, failure_count=0, last_error=''
        WHERE token=?
      `
    ).run(Date.now(), token);
  }

  function markFailure(token, error) {
    db.prepare(
      `
        UPDATE fcm_tokens
        SET last_failure_at=?, failure_count=failure_count+1, last_error=?
        WHERE token=?
      `
    ).run(Date.now(), String(error || "unknown").slice(0, 240), token);
  }

  function remove(token) {
    db.prepare("DELETE FROM fcm_tokens WHERE token=?").run(token);
  }

  return {
    countUnreadMessagesForUser,
    listConversationTokens,
    listUserTokens,
    listBlogNotificationTokens,
    markSuccess,
    markFailure,
    remove,
  };
}

module.exports = { createFcmTokenRepository };
