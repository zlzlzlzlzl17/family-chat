function registerMessageAttachmentRoutes(context) {
  const {
    logger,
    path,
    fs,
    crypto,
    express,
    bcrypt,
    UPLOAD_DIR,
    MAX_CIPHERTEXT_LEN,
    MAX_PLAINTEXT_LEN,
    app,
    db,
    authMiddleware,
    upload,
    safeJsonParse,
    sanitizeReplyTo,
    sanitizeMentions,
    extractAttachmentFile,
    messageRowToWire,
    conversationExpiresAt,
    getReadStates,
    getConversationReadStates,
    getConversationDeliveryStates,
    getUserById,
    getConversationForUser,
    isValidUsername,
    clearAllHistory,
    broadcast,
    broadcastToConversation,
    markDeliveredForUser,
    markReadForUser,
    forceLogoutSession,
    maskFcmToken,
    logFcmDebug,
    sendPushToConversation,
    sendFcmToConversation,
  } = context;

function findIdempotentMessage(auth, clientMessageId) {
  return db
    .prepare(
      `
        SELECT id, conversation_id, ts, expires_at, user_id, username, color, kind, payload,
               e2ee, reply_to, mentions, client_message_id, sender_device_id
        FROM messages
        WHERE user_id=? AND sender_device_id=? AND client_message_id=?
      `
    )
    .get(auth.uid, auth.device_id, clientMessageId);
}

function validClientMessageId(value) {
  return /^[A-Za-z0-9:_-]{8,80}$/.test(value);
}

function dispatchMessageNotifications(conversation, sender, message, kind) {
  const encrypted = !!message.e2ee;
  const pushText = encrypted
    ? `${sender.username} sent an encrypted ${kind === "text" ? "message" : kind}`
    : `${sender.username}: ${String(message.payload || "").slice(0, 80)}`;
  Promise.resolve(sendPushToConversation(conversation.id, sender.id, pushText)).catch(() => {});
  Promise.resolve(
    sendFcmToConversation(
      conversation.id,
      sender.id,
      conversation.kind === "direct" ? sender.username : conversation.title,
      pushText,
      {
        messageId: Number(message.id),
        createdAt: Number(message.ts),
        kind: encrypted && kind === "text" ? "encrypted_text" : kind,
      }
    )
  ).catch((error) => {
    logFcmDebug("message_dispatch_failed", {
      conversationId: conversation.id,
      senderUserId: sender.id,
      messageId: Number(message.id),
      error: String(error?.message || error || "unknown"),
    }, true);
  });
}

function discardUpload(file) {
  if (!file) return;
  try { fs.unlinkSync(file.path || path.join(UPLOAD_DIR, file.filename)); } catch {}
}

function validAttachmentPayload(inputPayload) {
  return !!(
    inputPayload &&
    Number(inputPayload.v || 0) === 3 &&
    typeof inputPayload.meta === "string" &&
    inputPayload.enc &&
    typeof inputPayload.enc.iv === "string" &&
    inputPayload.key_wrap &&
    typeof inputPayload.key_wrap.payload === "string"
  );
}

function storedAttachmentPayload(inputPayload, kind, fileName, fileSize) {
  return JSON.stringify({
    v: 3,
    attachment: {
      url: `/uploads/${fileName}`,
      file: fileName,
      kind,
      cipherSize: fileSize,
    },
    enc: {
      iv: inputPayload.enc.iv.slice(0, 128),
      v: 3,
      kdf: String(inputPayload.enc.kdf || "HKDF-SHA256").slice(0, 64),
      aead: String(inputPayload.enc.aead || "AES-256-GCM").slice(0, 64),
      stream: String(inputPayload.enc.stream || "file-key-gcm").slice(0, 64),
    },
    meta: inputPayload.meta.slice(0, MAX_CIPHERTEXT_LEN),
    key_wrap: {
      scheme: String(inputPayload.key_wrap.scheme || "").slice(0, 40),
      payload: String(inputPayload.key_wrap.payload || "").slice(0, MAX_CIPHERTEXT_LEN),
    },
    display: {
      width: Math.max(0, Math.min(Number(inputPayload.display?.width || 0), 20000)),
      height: Math.max(0, Math.min(Number(inputPayload.display?.height || 0), 20000)),
      sticker: !!inputPayload.display?.sticker,
    },
  });
}

function insertAttachmentMessage({ auth, conversation, sender, kind, inputPayload, fileName, fileSize, replyTo, mentions, clientMessageId }) {
  const duplicate = findIdempotentMessage(auth, clientMessageId);
  if (duplicate) return { duplicate: true, message: messageRowToWire(duplicate) };
  const now = Date.now();
  const expiresAt = conversationExpiresAt(conversation, now);
  const payload = storedAttachmentPayload(inputPayload, kind, fileName, fileSize);
  let result;
  try {
    result = db
      .prepare(
        `
          INSERT INTO messages (
            conversation_id, ts, expires_at, user_id, username, color, kind, payload,
            reply_to, mentions, e2ee, client_message_id, sender_device_id
          )
          VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
        `
      )
      .run(
        conversation.id,
        now,
        expiresAt,
        sender.id,
        sender.username,
        sender.color,
        kind,
        payload,
        replyTo,
        mentions,
        clientMessageId,
        auth.device_id
      );
  } catch (error) {
    if (String(error?.code || "").includes("SQLITE_CONSTRAINT")) {
      const existing = findIdempotentMessage(auth, clientMessageId);
      if (existing) return { duplicate: true, message: messageRowToWire(existing) };
    }
    throw error;
  }
  return {
    duplicate: false,
    message: messageRowToWire({
      id: result.lastInsertRowid,
      conversation_id: conversation.id,
      ts: now,
      expires_at: expiresAt,
      user_id: sender.id,
      username: sender.username,
      color: sender.color,
      kind,
      payload,
      e2ee: 1,
      reply_to: replyTo,
      mentions,
      client_message_id: clientMessageId,
      sender_device_id: auth.device_id,
    }),
  };
}

app.get("/api/read_states", authMiddleware, (req, res) => {
  const conversation = req.query.conversation_id
    ? getConversationForUser(req.auth.uid, req.query.conversation_id)
    : null;
  res.json({ items: conversation ? getConversationReadStates(conversation.id) : getReadStates() });
});

app.get("/api/delivery_states", authMiddleware, (req, res) => {
  const conversation = req.query.conversation_id
    ? getConversationForUser(req.auth.uid, req.query.conversation_id)
    : null;
  if (!conversation) return res.json({ items: [] });
  res.json({ items: getConversationDeliveryStates(conversation.id) });
});

app.post("/api/delivered", authMiddleware, (req, res) => {
  const conversation = getConversationForUser(req.auth.uid, req.body.conversation_id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  const highest = db
    .prepare("SELECT COALESCE(MAX(id), 0) AS maxId FROM messages WHERE conversation_id=?")
    .get(conversation.id).maxId;
  const lastDeliveredMessageId = Math.min(Math.max(Number(req.body.last_delivered_message_id || 0), 0), highest);
  if (lastDeliveredMessageId > 0) {
    logger.info("receipt_delivered_requested", {
      conversationId: conversation.id,
      userId: req.auth.uid,
      deviceId: req.auth.device_id,
      messageId: lastDeliveredMessageId,
    });
    markDeliveredForUser(conversation.id, req.auth.uid, lastDeliveredMessageId);
  }
  res.json({ ok: true, conversation_id: conversation.id, last_delivered_message_id: lastDeliveredMessageId });
});

app.post("/api/read", authMiddleware, (req, res) => {
  const conversation = getConversationForUser(req.auth.uid, req.body.conversation_id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  const highest = db
    .prepare("SELECT COALESCE(MAX(id), 0) AS maxId FROM messages WHERE conversation_id=?")
    .get(conversation.id).maxId;
  const lastReadMessageId = Math.min(Math.max(Number(req.body.last_read_message_id || 0), 0), highest);
  if (lastReadMessageId > 0) {
    markReadForUser(conversation.id, req.auth.uid, lastReadMessageId);
  }
  res.json({ ok: true, conversation_id: conversation.id, last_read_message_id: lastReadMessageId });
});

app.get("/api/history", authMiddleware, (req, res) => {
  const conversation = getConversationForUser(req.auth.uid, req.query.conversation_id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });

  const sinceId = Math.max(Number(req.query.since_id || 0), 0);
  const beforeId = Math.max(Number(req.query.before_id || 0), 0);
  const limit = Math.min(Math.max(Number(req.query.limit || 200), 1), 500);
  const pageSize = limit + 1;
  let rows = [];

  if (beforeId > 0) {
    rows = db
      .prepare(
        `
        SELECT id, conversation_id, ts, expires_at, user_id, username, color, kind, payload, e2ee, reply_to, mentions, client_message_id, sender_device_id
        FROM messages
        WHERE conversation_id = ? AND id < ?
        ORDER BY id DESC
        LIMIT ?
      `
      )
      .all(conversation.id, beforeId, pageSize);
    const hasMore = rows.length > limit;
    if (hasMore) rows = rows.slice(0, limit);
    rows.reverse();
    const items = rows.map(messageRowToWire);
    const latestDelivered = rows.length > 0 ? rows[rows.length - 1].id : 0;
    if (latestDelivered > 0) {
      markDeliveredForUser(conversation.id, req.auth.uid, latestDelivered);
    }
    return res.json({ items, has_more: hasMore });
  }

  if (sinceId > 0) {
    rows = db
      .prepare(
        `
        SELECT id, conversation_id, ts, expires_at, user_id, username, color, kind, payload, e2ee, reply_to, mentions, client_message_id, sender_device_id
        FROM messages
        WHERE conversation_id = ? AND id > ?
        ORDER BY id ASC
        LIMIT ?
      `
      )
      .all(conversation.id, sinceId, pageSize);
    const hasMore = rows.length > limit;
    if (hasMore) rows = rows.slice(0, limit);
    const items = rows.map(messageRowToWire);
    const latestDelivered = rows.length > 0 ? rows[rows.length - 1].id : 0;
    if (latestDelivered > 0) {
      markDeliveredForUser(conversation.id, req.auth.uid, latestDelivered);
    }
    return res.json({ items, has_more: hasMore });
  }

  rows = db
    .prepare(
      `
      SELECT id, conversation_id, ts, expires_at, user_id, username, color, kind, payload, e2ee, reply_to, mentions, client_message_id, sender_device_id
      FROM messages
      WHERE conversation_id = ?
      ORDER BY id DESC
      LIMIT ?
    `
    )
    .all(conversation.id, pageSize);
  const hasMore = rows.length > limit;
  if (hasMore) rows = rows.slice(0, limit);
  rows.reverse();

  const items = rows.map(messageRowToWire);
  const latestDelivered = rows.length > 0 ? rows[rows.length - 1].id : 0;
  if (latestDelivered > 0) {
    markDeliveredForUser(conversation.id, req.auth.uid, latestDelivered);
  }
  res.json({ items, has_more: hasMore });
});

app.post("/api/delete_history", authMiddleware, (req, res) => {
  if (!req.auth.is_admin) return res.status(403).json({ error: "forbidden" });
  clearAllHistory();

  broadcast({ type: "history_deleted", ts: Date.now(), by: req.auth.username });
  broadcast({ type: "read_snapshot", items: getReadStates() });
  res.json({ ok: true });
});

app.post("/api/change_password", authMiddleware, (req, res) => {
  const { new_password: newPassword } = req.body || {};
  if (typeof newPassword !== "string" || newPassword.length < 8) {
    return res.status(400).json({ error: "password_too_short" });
  }

  const hash = bcrypt.hashSync(newPassword, 12);
  const now = Date.now();
  const revokedSessions = db
    .prepare("SELECT session_id FROM auth_sessions WHERE user_id=? AND session_id!=? AND status!='revoked'")
    .all(req.auth.uid, req.auth.sid);
  db.transaction(() => {
    db.prepare("UPDATE users SET password_hash=?, password_changed_at=? WHERE id=?").run(
      hash,
      now,
      req.auth.uid
    );
    db.prepare(
      `
        UPDATE auth_sessions
        SET status='revoked', revoked_at=?
        WHERE user_id=? AND session_id!=? AND status!='revoked'
      `
    ).run(now, req.auth.uid, req.auth.sid);
    db.prepare("DELETE FROM fcm_tokens WHERE user_id=? AND device_id!=?").run(
      req.auth.uid,
      req.auth.device_id
    );
    db.prepare(
      "UPDATE device_presence SET websocket_connected=0, updated_at=? WHERE user_id=? AND device_id!=?"
    ).run(now, req.auth.uid, req.auth.device_id);
  })();
  for (const session of revokedSessions) {
    forceLogoutSession(session.session_id, "password_changed");
  }
  res.json({ ok: true });
});

app.post("/api/username", authMiddleware, (req, res) => {
  const username = typeof req.body?.username === "string" ? req.body.username.trim() : "";
  if (!isValidUsername(username)) {
    return res.status(400).json({ error: "invalid_username" });
  }
  const existing = db.prepare("SELECT id FROM users WHERE username=?").get(username);
  if (existing && existing.id !== req.auth.uid) {
    return res.status(409).json({ error: "username_taken" });
  }

  db.prepare("UPDATE users SET username=? WHERE id=?").run(username, req.auth.uid);
  const updated = getUserById(req.auth.uid);
  broadcast({
    type: "user_updated",
    user: {
      user_code: updated.user_code || "",
      username: updated.username,
      color: updated.color,
      avatar_url: updated.avatar_url || "",
      is_admin: !!updated.is_admin,
    },
  });
  res.json({
    ok: true,
    user: {
      user_code: updated.user_code || "",
      username: updated.username,
      color: updated.color,
      avatar_url: updated.avatar_url || "",
      is_admin: !!updated.is_admin,
    },
  });
});

app.post("/api/avatar", authMiddleware, upload.single("avatar"), (req, res) => {
  if (!req.file) return res.status(400).json({ error: "no_file" });

  const user = db.prepare("SELECT avatar_file FROM users WHERE id=?").get(req.auth.uid);
  if (!user) return res.status(401).json({ error: "unauthorized" });

  if (user.avatar_file) {
    try {
      fs.unlinkSync(path.join(UPLOAD_DIR, user.avatar_file));
    } catch {}
  }

  const avatarUrl = `/uploads/${req.file.filename}`;
  db.prepare("UPDATE users SET avatar_url=?, avatar_file=? WHERE id=?").run(avatarUrl, req.file.filename, req.auth.uid);
  const updatedUser = getUserById(req.auth.uid);
  broadcast({
    type: "user_updated",
    user: {
      user_code: updatedUser.user_code || "",
      username: updatedUser.username,
      color: updatedUser.color,
      avatar_url: updatedUser.avatar_url || "",
      is_admin: !!updatedUser.is_admin,
    },
  });
  res.json({ ok: true, avatar_url: avatarUrl });
});

app.post("/api/messages/:id/recall", authMiddleware, (req, res) => {
  const messageId = Math.max(Number(req.params.id || 0), 0);
  if (!messageId) return res.status(400).json({ error: "bad_message_id" });

  const message = db
    .prepare(
      `
      SELECT id, conversation_id, user_id, kind, payload
      FROM messages
      WHERE id = ?
    `
    )
    .get(messageId);
  if (!message) return res.status(404).json({ error: "message_not_found" });

  const conversation = getConversationForUser(req.auth.uid, message.conversation_id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (message.user_id !== req.auth.uid && !req.auth.is_admin) {
    return res.status(403).json({ error: "forbidden" });
  }

  const file = extractAttachmentFile(message.payload, message.kind);
  if (file) {
    try {
      fs.unlinkSync(path.join(UPLOAD_DIR, file));
    } catch {}
  }

  db.prepare(
    `
    UPDATE messages
    SET kind='recalled',
        payload='',
        reply_to='',
        mentions='[]',
        e2ee=0
    WHERE id=?
  `
  ).run(messageId);

  const updated = db
    .prepare(
      `
      SELECT id, conversation_id, ts, expires_at, user_id, username, color, kind, payload, e2ee, reply_to, mentions, client_message_id, sender_device_id
      FROM messages
      WHERE id = ?
    `
    )
    .get(messageId);

  broadcastToConversation(conversation.id, { type: "message_recalled", message: messageRowToWire(updated) });
  res.json({ ok: true, message: messageRowToWire(updated) });
});

app.post("/api/push_subscribe", authMiddleware, (req, res) => {
  const sub = req.body || {};
  if (!sub.endpoint || !sub.keys || !sub.keys.p256dh || !sub.keys.auth) {
    return res.status(400).json({ error: "bad_subscription" });
  }

  db.prepare(
    `
    INSERT INTO push_subs (user_id, endpoint, p256dh, auth, created_at)
    VALUES (?, ?, ?, ?, ?)
    ON CONFLICT(endpoint) DO UPDATE SET
      user_id=excluded.user_id,
      p256dh=excluded.p256dh,
      auth=excluded.auth
  `
  ).run(req.auth.uid, sub.endpoint, sub.keys.p256dh, sub.keys.auth, Date.now());

  res.json({ ok: true });
});

app.post("/api/fcm_register", authMiddleware, (req, res) => {
  const token = typeof req.body?.token === "string" ? req.body.token.trim() : "";
  const locale = typeof req.body?.locale === "string" ? req.body.locale.trim() : "";
  const manufacturer = typeof req.body?.manufacturer === "string" ? req.body.manufacturer.trim() : "";
  const model = typeof req.body?.model === "string" ? req.body.model.trim() : "";
  const blogNotificationsEnabled = ![false, 0, "0", "false"].includes(req.body?.blog_notifications_enabled);
  if (!token) return res.status(400).json({ error: "bad_token" });
  const now = Date.now();
  db.prepare(
    `
    INSERT INTO fcm_tokens (
      user_id, device_id, token, platform, locale, manufacturer, model,
      blog_notifications_enabled, created_at, updated_at
    )
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(token) DO UPDATE SET
      user_id=excluded.user_id,
      device_id=excluded.device_id,
      platform=excluded.platform,
      locale=excluded.locale,
      manufacturer=excluded.manufacturer,
      model=excluded.model,
      blog_notifications_enabled=excluded.blog_notifications_enabled,
      updated_at=excluded.updated_at,
      failure_count=0,
      last_error=''
  `
  ).run(
    req.auth.uid,
    req.auth.device_id,
    token,
    "android",
    /^zh/i.test(locale) ? "zh" : "en",
    manufacturer.slice(0, 40),
    model.slice(0, 80),
    blogNotificationsEnabled ? 1 : 0,
    now,
    now
  );
  logFcmDebug("token_registered", {
    userId: req.auth.uid,
    token: maskFcmToken(token),
    manufacturer: manufacturer.slice(0, 40),
    model: model.slice(0, 80),
    blogNotificationsEnabled,
  });
  res.json({ ok: true });
});

app.post("/api/fcm_unregister", authMiddleware, (req, res) => {
  const token = typeof req.body?.token === "string" ? req.body.token.trim() : "";
  if (!token) return res.status(400).json({ error: "bad_token" });
  db.prepare("DELETE FROM fcm_tokens WHERE token = ? AND user_id = ?").run(token, req.auth.uid);
  logFcmDebug("token_unregistered", {
    userId: req.auth.uid,
    token: maskFcmToken(token),
  });
  res.json({ ok: true });
});

app.post("/api/messages", authMiddleware, (req, res) => {
  const conversation = getConversationForUser(req.auth.uid, req.body?.conversation_id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if ((req.body?.kind || "text") !== "text") {
    return res.status(400).json({ error: "bad_kind" });
  }

  const clientMessageId = String(req.body?.client_message_id || "").trim().slice(0, 80);
  if (!validClientMessageId(clientMessageId)) {
    return res.status(400).json({ error: "bad_client_message_id" });
  }
  const existing = findIdempotentMessage(req.auth, clientMessageId);
  if (existing) {
    return res.json({ ok: true, duplicate: true, message: messageRowToWire(existing) });
  }

  const sender = db.prepare("SELECT id, username, color FROM users WHERE id=?").get(req.auth.uid);
  if (!sender) return res.status(401).json({ error: "unauthorized" });
  const e2ee = req.body?.e2ee ? 1 : 0;
  let payload = typeof req.body?.payload === "string" ? req.body.payload : "";
  if (e2ee) {
    if (!payload || payload.length > MAX_CIPHERTEXT_LEN) {
      return res.status(400).json({ error: "bad_payload" });
    }
  } else {
    payload = payload.trim().slice(0, MAX_PLAINTEXT_LEN);
    if (!payload) return res.status(400).json({ error: "bad_payload" });
  }

  const now = Date.now();
  const expiresAt = conversationExpiresAt(conversation, now);
  const replyTo = sanitizeReplyTo(req.body?.reply_to);
  const mentions = sanitizeMentions(req.body?.mentions, conversation.id);
  let result;
  try {
    result = db
      .prepare(
        `
          INSERT INTO messages (
            conversation_id, ts, expires_at, user_id, username, color, kind, payload,
            reply_to, mentions, e2ee, client_message_id, sender_device_id
          )
          VALUES (?, ?, ?, ?, ?, ?, 'text', ?, ?, ?, ?, ?, ?)
        `
      )
      .run(
        conversation.id,
        now,
        expiresAt,
        sender.id,
        sender.username,
        sender.color,
        payload,
        replyTo,
        mentions,
        e2ee,
        clientMessageId,
        req.auth.device_id
      );
  } catch (error) {
    if (String(error?.code || "").includes("SQLITE_CONSTRAINT")) {
      const duplicate = findIdempotentMessage(req.auth, clientMessageId);
      if (duplicate) {
        return res.json({ ok: true, duplicate: true, message: messageRowToWire(duplicate) });
      }
    }
    throw error;
  }

  const message = messageRowToWire({
    id: result.lastInsertRowid,
    conversation_id: conversation.id,
    ts: now,
    expires_at: expiresAt,
    user_id: sender.id,
    username: sender.username,
    color: sender.color,
    kind: "text",
    payload,
    e2ee,
    reply_to: replyTo,
    mentions,
    client_message_id: clientMessageId,
    sender_device_id: req.auth.device_id,
  });
  broadcastToConversation(conversation.id, { type: "chat", ...message });
  dispatchMessageNotifications(conversation, sender, message, "text");
  res.status(201).json({ ok: true, duplicate: false, message });
});

app.post("/api/attachment_uploads", authMiddleware, (req, res) => {
  const conversation = getConversationForUser(req.auth.uid, req.body?.conversation_id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  const kind = String(req.body?.kind || "").trim();
  if (!["image", "photo", "file", "audio"].includes(kind)) {
    return res.status(400).json({ error: "bad_kind" });
  }
  const clientMessageId = String(req.body?.client_message_id || "").trim().slice(0, 80);
  if (!validClientMessageId(clientMessageId)) {
    return res.status(400).json({ error: "bad_client_message_id" });
  }
  const completed = findIdempotentMessage(req.auth, clientMessageId);
  if (completed) {
    return res.json({ ok: true, complete: true, offset: 0, message: messageRowToWire(completed) });
  }
  const inputPayload = safeJsonParse(req.body?.payload || "", null);
  if (!validAttachmentPayload(inputPayload)) return res.status(400).json({ error: "bad_payload" });
  const totalSize = Math.max(Number(req.body?.total_size || 0), 0);
  if (!Number.isSafeInteger(totalSize) || totalSize <= 0 || totalSize > 22 * 1024 * 1024) {
    return res.status(413).json({ error: "file_too_large" });
  }
  const checksum = String(req.body?.checksum_sha256 || "").trim().toLowerCase();
  if (checksum && !/^[a-f0-9]{64}$/.test(checksum)) {
    return res.status(400).json({ error: "bad_checksum" });
  }
  const existing = db
    .prepare(
      `SELECT * FROM attachment_upload_sessions
       WHERE user_id=? AND device_id=? AND client_message_id=?`
    )
    .get(req.auth.uid, req.auth.device_id, clientMessageId);
  if (existing) {
    if (Number(existing.total_size) !== totalSize || existing.checksum_sha256 !== checksum) {
      return res.status(409).json({ error: "upload_metadata_changed" });
    }
    return res.json({
      ok: true,
      complete: false,
      upload_id: existing.upload_id,
      offset: Number(existing.received_size),
    });
  }

  const uploadId = crypto.randomUUID();
  const partFile = `upload-${uploadId}.part`;
  const now = Date.now();
  db.prepare(
    `
      INSERT INTO attachment_upload_sessions (
        upload_id, user_id, device_id, client_message_id, conversation_id, kind,
        payload, reply_to, mentions, part_file, total_size, received_size,
        checksum_sha256, created_at, updated_at
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
    `
  ).run(
    uploadId,
    req.auth.uid,
    req.auth.device_id,
    clientMessageId,
    conversation.id,
    kind,
    JSON.stringify(inputPayload),
    sanitizeReplyTo(req.body?.reply_to),
    sanitizeMentions(req.body?.mentions, conversation.id),
    partFile,
    totalSize,
    checksum,
    now,
    now
  );
  res.status(201).json({ ok: true, complete: false, upload_id: uploadId, offset: 0 });
});

app.put(
  "/api/attachment_uploads/:id/chunk",
  authMiddleware,
  express.raw({ type: "application/octet-stream", limit: "1mb" }),
  (req, res) => {
    const session = db
      .prepare(
        `SELECT * FROM attachment_upload_sessions
         WHERE upload_id=? AND user_id=? AND device_id=?`
      )
      .get(req.params.id, req.auth.uid, req.auth.device_id);
    if (!session) return res.status(404).json({ error: "upload_not_found" });
    const offset = Math.max(Number(req.headers["x-upload-offset"] || 0), 0);
    if (offset !== Number(session.received_size)) {
      return res.status(409).json({ error: "offset_mismatch", offset: Number(session.received_size) });
    }
    const chunk = Buffer.isBuffer(req.body) ? req.body : Buffer.alloc(0);
    if (!chunk.length || chunk.length > 1024 * 1024) {
      return res.status(400).json({ error: "bad_chunk" });
    }
    if (offset + chunk.length > Number(session.total_size)) {
      return res.status(400).json({ error: "chunk_overflow" });
    }
    const partPath = path.join(UPLOAD_DIR, session.part_file);
    fs.appendFileSync(partPath, chunk);
    const received = offset + chunk.length;
    db.prepare(
      "UPDATE attachment_upload_sessions SET received_size=?, updated_at=? WHERE upload_id=?"
    ).run(received, Date.now(), session.upload_id);
    res.json({ ok: true, offset: received, complete: received === Number(session.total_size) });
  }
);

app.post("/api/attachment_uploads/:id/complete", authMiddleware, (req, res) => {
  const session = db
    .prepare(
      `SELECT * FROM attachment_upload_sessions
       WHERE upload_id=? AND user_id=? AND device_id=?`
    )
    .get(req.params.id, req.auth.uid, req.auth.device_id);
  if (!session) {
    const clientMessageId = String(req.body?.client_message_id || "").trim().slice(0, 80);
    const completed = validClientMessageId(clientMessageId)
      ? findIdempotentMessage(req.auth, clientMessageId)
      : null;
    if (completed) return res.json({ ok: true, duplicate: true, message: messageRowToWire(completed) });
    return res.status(404).json({ error: "upload_not_found" });
  }
  if (Number(session.received_size) !== Number(session.total_size)) {
    return res.status(409).json({ error: "upload_incomplete", offset: Number(session.received_size) });
  }
  const partPath = path.join(UPLOAD_DIR, session.part_file);
  if (!fs.existsSync(partPath) || fs.statSync(partPath).size !== Number(session.total_size)) {
    return res.status(409).json({ error: "upload_file_mismatch" });
  }
  if (session.checksum_sha256) {
    const actual = crypto.createHash("sha256").update(fs.readFileSync(partPath)).digest("hex");
    if (actual !== session.checksum_sha256) {
      return res.status(422).json({ error: "checksum_mismatch" });
    }
  }
  const conversation = getConversationForUser(req.auth.uid, session.conversation_id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  const sender = db.prepare("SELECT id, username, color FROM users WHERE id=?").get(req.auth.uid);
  if (!sender) return res.status(401).json({ error: "unauthorized" });
  const finalName = `${Date.now()}-${crypto.randomBytes(10).toString("hex")}.enc`;
  const finalPath = path.join(UPLOAD_DIR, finalName);
  fs.renameSync(partPath, finalPath);
  try {
    const result = insertAttachmentMessage({
      auth: req.auth,
      conversation,
      sender,
      kind: session.kind,
      inputPayload: safeJsonParse(session.payload, null),
      fileName: finalName,
      fileSize: Number(session.total_size),
      replyTo: session.reply_to,
      mentions: session.mentions,
      clientMessageId: session.client_message_id,
    });
    db.prepare("DELETE FROM attachment_upload_sessions WHERE upload_id=?").run(session.upload_id);
    if (result.duplicate) discardUpload({ path: finalPath });
    else {
      broadcastToConversation(conversation.id, { type: "chat", ...result.message });
      dispatchMessageNotifications(conversation, sender, result.message, session.kind);
    }
    res.json({ ok: true, duplicate: result.duplicate, message: result.message });
  } catch (error) {
    if (fs.existsSync(finalPath) && !fs.existsSync(partPath)) fs.renameSync(finalPath, partPath);
    throw error;
  }
});

app.delete("/api/attachment_uploads/:id", authMiddleware, (req, res) => {
  const session = db
    .prepare("SELECT * FROM attachment_upload_sessions WHERE upload_id=? AND user_id=? AND device_id=?")
    .get(req.params.id, req.auth.uid, req.auth.device_id);
  if (!session) return res.json({ ok: true });
  discardUpload({ path: path.join(UPLOAD_DIR, session.part_file) });
  db.prepare("DELETE FROM attachment_upload_sessions WHERE upload_id=?").run(session.upload_id);
  res.json({ ok: true });
});

app.post("/api/upload_attachment", authMiddleware, upload.single("attachment"), (req, res) => {
  if (!req.file) return res.status(400).json({ error: "no_file" });
  const conversation = getConversationForUser(req.auth.uid, req.body.conversation_id);
  if (!conversation) {
    discardUpload(req.file);
    return res.status(404).json({ error: "conversation_not_found" });
  }

  const kind = typeof req.body.kind === "string" ? req.body.kind.trim() : "";
  if (!["image", "photo", "file", "audio"].includes(kind)) {
    discardUpload(req.file);
    return res.status(400).json({ error: "bad_kind" });
  }

  const clientMessageId = String(req.body.client_message_id || "").trim().slice(0, 80);
  if (!validClientMessageId(clientMessageId)) {
    discardUpload(req.file);
    return res.status(400).json({ error: "bad_client_message_id" });
  }
  const existing = findIdempotentMessage(req.auth, clientMessageId);
  if (existing) {
    discardUpload(req.file);
    return res.json({ ok: true, duplicate: true, message: messageRowToWire(existing) });
  }

  const inputPayload = safeJsonParse(req.body.payload || "", null);
  const payloadVersion = Number(inputPayload?.v || 0);
  if (!validAttachmentPayload(inputPayload) || payloadVersion !== 3) {
    discardUpload(req.file);
    return res.status(400).json({ error: "bad_payload" });
  }

  const info = db.prepare("SELECT id, username, color FROM users WHERE id=?").get(req.auth.uid);
  if (!info) {
    discardUpload(req.file);
    return res.status(401).json({ error: "unauthorized" });
  }

  const now = Date.now();
  const expiresAt = conversationExpiresAt(conversation, now);
  const encPayload = {
    iv: inputPayload.enc.iv.slice(0, 128),
    v: 3,
    kdf: String(inputPayload.enc.kdf || "HKDF-SHA256").slice(0, 64),
    aead: String(inputPayload.enc.aead || "AES-256-GCM").slice(0, 64),
    stream: String(inputPayload.enc.stream || "file-key-gcm").slice(0, 64),
  };
  const outputPayload = {
    v: 3,
    attachment: {
      url: `/uploads/${req.file.filename}`,
      file: req.file.filename,
      kind,
      cipherSize: req.file.size,
    },
    enc: encPayload,
    meta: inputPayload.meta.slice(0, MAX_CIPHERTEXT_LEN),
    key_wrap: {
      scheme: String(inputPayload.key_wrap.scheme || "").slice(0, 40),
      payload: String(inputPayload.key_wrap.payload || "").slice(0, MAX_CIPHERTEXT_LEN),
    },
    display: {
      width: Math.max(0, Math.min(Number(inputPayload.display?.width || 0), 20000)),
      height: Math.max(0, Math.min(Number(inputPayload.display?.height || 0), 20000)),
      sticker: !!inputPayload.display?.sticker,
    },
  };
  const payload = JSON.stringify(outputPayload);

  const replyTo = sanitizeReplyTo(req.body.reply_to);
  const mentions = sanitizeMentions(req.body.mentions, conversation.id);

  const result = db
    .prepare(
      `
      INSERT INTO messages (
        conversation_id, ts, expires_at, user_id, username, color, kind, payload,
        reply_to, mentions, e2ee, client_message_id, sender_device_id
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
    `
    )
    .run(
      conversation.id,
      now,
      expiresAt,
      info.id,
      info.username,
      info.color,
      kind,
      payload,
      replyTo,
      mentions,
      clientMessageId,
      req.auth.device_id
    );

  const message = messageRowToWire({
    id: result.lastInsertRowid,
    conversation_id: conversation.id,
    ts: now,
    expires_at: expiresAt,
    user_id: info.id,
    username: info.username,
    color: info.color,
    kind,
    payload,
    e2ee: 1,
    reply_to: replyTo,
    mentions,
    client_message_id: clientMessageId,
    sender_device_id: req.auth.device_id,
  });

  broadcastToConversation(conversation.id, { type: "chat", ...message });
  dispatchMessageNotifications(conversation, info, message, kind);
  res.status(201).json({ ok: true, duplicate: false, message });
});
}

module.exports = { registerMessageAttachmentRoutes };
