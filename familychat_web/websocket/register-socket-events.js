"use strict";

function registerSocketEvents(context) {
  const {
    server,
    wss,
    db,
    socketHub,
    sessionStatusActive,
    deviceStatusTrusted,
    maxCallSignalTextLength,
    maxPlaintextLength,
    maxCiphertextLength,
    callInviteFallbackDelayMs,
    isAppClient,
    verifyToken,
    getClientType,
    getClientIp,
    setSocketPresence,
    takePendingCallInvitesForUser,
    clearPendingCallFallback,
    getConversationForUser,
    getDirectConversationPeer,
    upsertPendingCallInvite,
    clearPendingCallInvitesForConversation,
    schedulePendingCallFallback,
    sendFcmIncomingCallToUser,
    sendFcmCallHangupToUser,
    markDeliveredForUser,
    markReadForUser,
    sanitizeReplyTo,
    sanitizeMentions,
    conversationExpiresAt,
    messageRowToWire,
    sendPushToConversation,
    sendFcmToConversation,
    logger,
  } = context;

  function normalizeCallSignalPayload(value) {
    if (!value || typeof value !== "object") return null;
    const text = JSON.stringify(value);
    if (!text || text.length > maxCallSignalTextLength) return null;
    return value;
  }

  function sendObject(ws, payload) {
    return socketHub.safeSend(ws, JSON.stringify(payload));
  }

  server.on("upgrade", (req, socket, head) => {
    const url = new URL(req.url, `http://${req.headers.host}`);
    if (url.pathname !== "/ws" || !isAppClient(req)) return socket.destroy();
    const token = url.searchParams.get("token");
    if (!token) return socket.destroy();

    let auth;
    try {
      auth = verifyToken(token);
    } catch {
      return socket.destroy();
    }

    const clientType = getClientType(req);
    if ((auth.client_type || "mobile") !== clientType) return socket.destroy();
    const session = db
      .prepare(
        `SELECT s.*, d.status AS device_status,
                u.user_code, u.username, u.color, u.is_admin, u.account_status
         FROM auth_sessions s
         JOIN devices d ON d.user_id=s.user_id AND d.device_id=s.device_id
         JOIN users u ON u.id=s.user_id
         WHERE s.session_id=? AND s.user_id=?`
      )
      .get(auth.sid, auth.uid);
    if (
      !session ||
      session.client_type !== clientType ||
      session.status !== sessionStatusActive ||
      session.device_status !== deviceStatusTrusted ||
      Number(session.refresh_expires_at || 0) <= Date.now() ||
      (auth.did && auth.did !== session.device_id) ||
      session.account_status === "disabled" ||
      session.account_status === "deleted" ||
      session.account_status === "system"
    ) {
      return socket.destroy();
    }

    wss.handleUpgrade(req, socket, head, (ws) => {
      ws.auth = {
        ...auth,
        uid: session.user_id,
        sid: session.session_id,
        device_id: session.device_id,
        user_code: session.user_code || "",
        username: session.username,
        color: session.color,
        is_admin: !!session.is_admin,
        client_type: clientType,
      };
      ws.clientIp = getClientIp(req);
      wss.emit("connection", ws);
    });
  });

  wss.on("connection", (ws) => {
    ws.isAlive = true;
    ws.last_pong_at = Date.now();
    setSocketPresence(ws, (ws.auth.client_type || "mobile") === "desktop" ? "foreground" : "background");
    ws.on("pong", socketHub.heartbeat);
    socketHub.clients.add(ws);

    for (const invite of takePendingCallInvitesForUser(ws.auth.uid)) {
      clearPendingCallFallback(invite.conversation_id, ws.auth.uid);
      sendObject(ws, {
        type: "call_invite",
        conversation_id: invite.conversation_id,
        user_code: invite.caller_user_code || "",
        username: invite.caller_username || "",
        ts: invite.created_at,
      });
    }

    ws.on("message", async (buffer) => {
      if (buffer.length > 600 * 1024) return;
      try {
        const message = JSON.parse(buffer.toString("utf8"));
        const activeSession = db
          .prepare(
            `SELECT s.status, s.refresh_expires_at, d.status AS device_status
             FROM auth_sessions s
             JOIN devices d ON d.user_id=s.user_id AND d.device_id=s.device_id
             WHERE s.session_id=? AND s.user_id=? AND s.device_id=?`
          )
          .get(ws.auth.sid, ws.auth.uid, ws.auth.device_id);
        if (
          !activeSession ||
          activeSession.status !== sessionStatusActive ||
          activeSession.device_status !== deviceStatusTrusted ||
          Number(activeSession.refresh_expires_at || 0) <= Date.now()
        ) {
          sendObject(ws, { type: "force_logout" });
          try {
            ws.close();
          } catch {}
          return;
        }

        if (message.type === "presence") {
          setSocketPresence(ws, message.state);
          return;
        }
        if (message.type === "delivered" || message.type === "read") {
          const conversation = getConversationForUser(ws.auth.uid, message.conversation_id);
          if (!conversation) return;
          const highest = db
            .prepare("SELECT COALESCE(MAX(id), 0) AS maxId FROM messages WHERE conversation_id=?")
            .get(conversation.id).maxId;
          const field = message.type === "read" ? "last_read_message_id" : "last_delivered_message_id";
          const value = Math.min(Math.max(Number(message[field] || 0), 0), highest);
          if (message.type === "read") markReadForUser(conversation.id, ws.auth.uid, value);
          else markDeliveredForUser(conversation.id, ws.auth.uid, value);
          return;
        }
        if (String(message.type || "").startsWith("call_")) {
          await handleCallSignal(ws, message);
          return;
        }
        if (message.type === "chat" && (message.kind || "text") === "text") {
          await handleTextMessage(ws, message);
        }
      } catch (error) {
        logger.warn("message_handler_failed", {
          userId: ws.auth?.uid || 0,
          error: error?.name || "Error",
        });
      }
    });

    ws.on("close", () => {
      socketHub.clients.delete(ws);
      db.prepare(
        `UPDATE device_presence SET websocket_connected=0, updated_at=?
         WHERE user_id=? AND device_id=? AND session_id=?`
      ).run(Date.now(), ws.auth.uid, ws.auth.device_id, ws.auth.sid);
    });
  });

  async function handleCallSignal(ws, message) {
    const conversation = getConversationForUser(ws.auth.uid, message.conversation_id);
    if (!conversation || conversation.kind !== "direct") return;
    const peer = getDirectConversationPeer(conversation.id, ws.auth.uid);
    if (!peer) return;
    const baseEvent = {
      type: String(message.type),
      conversation_id: conversation.id,
      user_code: ws.auth.user_code || "",
      username: ws.auth.username,
    };

    if (message.type === "call_invite") {
      const createdAt = Date.now();
      upsertPendingCallInvite(
        conversation.id,
        { id: ws.auth.uid, user_code: ws.auth.user_code || "", username: ws.auth.username },
        peer
      );
      const delivered = socketHub.broadcastToUser(peer.id, { ...baseEvent, ts: createdAt });
      logger.info("call_invite_socket_delivery", {
        conversationId: conversation.id,
        callerId: ws.auth.uid,
        calleeId: peer.id,
        deliveredSockets: delivered,
      });
      if (!delivered) {
        clearPendingCallFallback(conversation.id, peer.id);
        const result = await sendFcmIncomingCallToUser(peer.id, {
          conversationId: conversation.id,
          peerUserCode: ws.auth.user_code || "",
          peerUsername: ws.auth.username,
          createdAt,
        });
        if (!result?.delivered) {
          clearPendingCallInvitesForConversation(conversation.id);
          logger.warn("call_invite_unavailable", {
            conversationId: conversation.id,
            callerId: ws.auth.uid,
            calleeId: peer.id,
            reason: result?.reason || "unknown",
          });
          socketHub.broadcastToUser(ws.auth.uid, {
            type: "call_unavailable",
            conversation_id: conversation.id,
            user_code: peer.user_code || "",
            username: peer.username,
          });
        }
      } else {
        schedulePendingCallFallback(
          conversation.id,
          { user_code: ws.auth.user_code || "", username: ws.auth.username },
          peer,
          createdAt
        );
        logger.info("call_invite_fallback_scheduled", {
          conversationId: conversation.id,
          callerId: ws.auth.uid,
          calleeId: peer.id,
          delayMs: callInviteFallbackDelayMs,
        });
      }
      return;
    }

    if (["call_accept", "call_reject", "call_busy", "call_hangup"].includes(message.type)) {
      const hadPendingInvite = !!db
        .prepare("SELECT 1 FROM pending_call_invites WHERE conversation_id=? AND callee_id=? LIMIT 1")
        .get(conversation.id, peer.id);
      clearPendingCallFallback(conversation.id);
      clearPendingCallInvitesForConversation(conversation.id);
      socketHub.broadcastToUser(peer.id, baseEvent);
      if (message.type === "call_hangup" && hadPendingInvite) {
        await sendFcmCallHangupToUser(peer.id, conversation.id);
      }
      return;
    }

    if (message.type === "call_offer" || message.type === "call_answer") {
      const description = normalizeCallSignalPayload(message.description);
      if (!description || typeof description.type !== "string" || typeof description.sdp !== "string") return;
      socketHub.broadcastToUser(peer.id, { ...baseEvent, description });
      return;
    }
    if (message.type === "call_ice") {
      const candidate = normalizeCallSignalPayload(message.candidate);
      if (candidate) socketHub.broadcastToUser(peer.id, { ...baseEvent, candidate });
    }
  }

  async function handleTextMessage(ws, message) {
    const sender = db.prepare("SELECT id, username, color FROM users WHERE id=?").get(ws.auth.uid);
    const conversation = getConversationForUser(ws.auth.uid, message.conversation_id);
    if (!sender || !conversation) return;
    const payload = typeof message.payload === "string" ? message.payload : "";
    if (!payload) return;
    const clientMessageId = String(message.client_message_id || "").trim().slice(0, 80);
    if (!/^[A-Za-z0-9:_-]{8,80}$/.test(clientMessageId)) return;

    const existing = db
      .prepare(
        `SELECT id, conversation_id, ts, expires_at, user_id, username, color, kind, payload,
                e2ee, reply_to, mentions, client_message_id, sender_device_id
         FROM messages WHERE user_id=? AND sender_device_id=? AND client_message_id=?`
      )
      .get(ws.auth.uid, ws.auth.device_id, clientMessageId);
    if (existing) {
      sendObject(ws, { type: "chat", ...messageRowToWire(existing), duplicate: true });
      return;
    }

    const encrypted = message.e2ee ? 1 : 0;
    let storedPayload = payload;
    if (!encrypted) {
      storedPayload = payload.trim().slice(0, maxPlaintextLength);
      if (!storedPayload) return;
    } else if (storedPayload.length > maxCiphertextLength) {
      return;
    }
    const replyTo = sanitizeReplyTo(message.reply_to);
    const mentions = sanitizeMentions(message.mentions, conversation.id);
    const now = Date.now();
    const expiresAt = conversationExpiresAt(conversation, now);
    const result = db
      .prepare(
        `INSERT INTO messages (
           conversation_id, ts, expires_at, user_id, username, color, kind, payload,
           reply_to, mentions, e2ee, client_message_id, sender_device_id
         ) VALUES (?, ?, ?, ?, ?, ?, 'text', ?, ?, ?, ?, ?, ?)`
      )
      .run(
        conversation.id,
        now,
        expiresAt,
        sender.id,
        sender.username,
        sender.color,
        storedPayload,
        replyTo,
        mentions,
        encrypted,
        clientMessageId,
        ws.auth.device_id
      );
    const outgoing = messageRowToWire({
      id: result.lastInsertRowid,
      conversation_id: conversation.id,
      ts: now,
      expires_at: expiresAt,
      user_id: sender.id,
      username: sender.username,
      color: sender.color,
      kind: "text",
      payload: storedPayload,
      e2ee: encrypted,
      reply_to: replyTo,
      mentions,
      client_message_id: clientMessageId,
      sender_device_id: ws.auth.device_id,
    });
    socketHub.broadcastToConversation(conversation.id, { type: "chat", ...outgoing });
    const pushText = encrypted
      ? `${sender.username} sent an encrypted message`
      : `${sender.username}: ${storedPayload.slice(0, 80)}`;
    Promise.resolve(sendPushToConversation(conversation.id, sender.id, pushText)).catch(() => {});
    Promise.resolve(
      sendFcmToConversation(
        conversation.id,
        sender.id,
        conversation.kind === "direct" ? sender.username : conversation.title,
        pushText,
        {
          messageId: Number(result.lastInsertRowid),
          createdAt: now,
          kind: encrypted ? "encrypted_text" : "text",
        }
      )
    ).catch((error) => logger.warn("message_fcm_dispatch_failed", { error: error?.name || "Error" }));
  }
}

module.exports = { registerSocketEvents };
