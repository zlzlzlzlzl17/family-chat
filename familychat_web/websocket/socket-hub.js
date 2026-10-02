"use strict";

function createSocketHub({ WebSocket, isConversationMember, logger = console }) {
  const clients = new Set();

  function safeSend(ws, data) {
    try {
      ws.send(data);
      return true;
    } catch (error) {
      logger.warn("send_failed", {
        userId: ws.auth?.uid || null,
        error: error?.message || String(error),
      });
      try {
        ws.terminate();
      } catch {}
      clients.delete(ws);
      return false;
    }
  }

  function broadcast(payload) {
    const data = JSON.stringify(payload);
    let delivered = 0;
    for (const ws of clients) {
      if (ws.readyState !== WebSocket.OPEN) continue;
      if (safeSend(ws, data)) delivered += 1;
    }
    return delivered;
  }

  function broadcastToConversation(conversationId, payload) {
    const data = JSON.stringify({ ...payload, conversation_id: conversationId });
    let delivered = 0;
    for (const ws of clients) {
      if (ws.readyState !== WebSocket.OPEN) continue;
      if (!isConversationMember(ws.auth?.uid, conversationId)) continue;
      if (safeSend(ws, data)) delivered += 1;
    }
    return delivered;
  }

  function broadcastToUser(userId, payload) {
    const data = JSON.stringify(payload);
    let delivered = 0;
    for (const ws of clients) {
      if (ws.readyState !== WebSocket.OPEN || ws.auth?.uid !== userId) continue;
      if (safeSend(ws, data)) delivered += 1;
    }
    return delivered;
  }

  function forceLogoutUser(userId, clientType = null) {
    for (const ws of clients) {
      if (ws.auth?.uid !== userId || ws.readyState !== WebSocket.OPEN) continue;
      if (clientType && (ws.auth.client_type || "mobile") !== clientType) continue;
      try {
        ws.send(JSON.stringify({ type: "force_logout" }));
      } catch {}
      try {
        ws.close();
      } catch {}
    }
  }

  function forceLogoutSession(sessionId, reason = "session_revoked") {
    for (const ws of clients) {
      if (ws.auth?.sid !== sessionId || ws.readyState !== WebSocket.OPEN) continue;
      try {
        ws.send(JSON.stringify({ type: "force_logout", reason }));
      } catch {}
      try {
        ws.close();
      } catch {}
    }
  }

  function forceLogoutDevice(userId, deviceId, reason = "device_revoked") {
    for (const ws of clients) {
      if (
        ws.auth?.uid !== userId ||
        ws.auth?.device_id !== deviceId ||
        ws.readyState !== WebSocket.OPEN
      ) {
        continue;
      }
      try {
        ws.send(JSON.stringify({ type: "force_logout", reason }));
      } catch {}
      try {
        ws.close();
      } catch {}
    }
  }

  function heartbeat() {
    this.isAlive = true;
    this.last_pong_at = Date.now();
  }

  return {
    clients,
    safeSend,
    broadcast,
    broadcastToConversation,
    broadcastToUser,
    forceLogoutUser,
    forceLogoutSession,
    forceLogoutDevice,
    heartbeat,
  };
}

module.exports = { createSocketHub };
