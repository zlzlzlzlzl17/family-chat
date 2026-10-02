"use strict";

const crypto = require("crypto");
const https = require("https");

function createFcmService({ projectId, clientEmail, privateKey, tokenRepository, logger = console }) {
  let accessTokenCache = { token: "", expiresAt: 0 };

  function hasConfig() {
    return !!(projectId && clientEmail && privateKey);
  }

  function maskToken(token) {
    const text = String(token || "");
    if (!text) return "(empty)";
    if (text.length <= 12) return text;
    return `${text.slice(0, 6)}...${text.slice(-6)}`;
  }

  function logFcm(event, details = {}, warning = false) {
    (warning ? logger.warn : logger.info)(event, details);
  }

  function logCall(event, details = {}, warning = false) {
    (warning ? logger.warn : logger.info)(`call_${event}`, details);
  }

  function localizedMessage(locale, unreadCount) {
    const normalized = /^zh/i.test(String(locale || "").trim()) ? "zh" : "en";
    const count = Math.max(Number(unreadCount || 0), 1);
    return {
      title: "Family Chat",
      body:
        normalized === "zh"
          ? count <= 1
            ? "\u6709\u65b0\u6d88\u606f"
            : `\u6709 ${count} \u6761\u65b0\u6d88\u606f`
          : count <= 1
            ? "New message"
            : `${count} new messages`,
      locale: normalized,
      unreadCount: count,
    };
  }

  function shouldDeleteToken(error) {
    const text = String(error?.message || error || "");
    return /UNREGISTERED|registration-token-not-registered|Requested entity was not found/i.test(text);
  }

  function postJson(url, body, headers = {}) {
    return new Promise((resolve, reject) => {
      const data = typeof body === "string" ? body : JSON.stringify(body);
      const req = https.request(
        url,
        {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "Content-Length": Buffer.byteLength(data),
            ...headers,
          },
        },
        (res) => {
          const chunks = [];
          res.on("data", (chunk) => chunks.push(chunk));
          res.on("end", () => {
            const text = Buffer.concat(chunks).toString("utf8");
            if (res.statusCode >= 200 && res.statusCode < 300) resolve(text);
            else reject(new Error(`http_${res.statusCode}:${text}`));
          });
        }
      );
      req.on("error", reject);
      req.write(data);
      req.end();
    });
  }

  async function getAccessToken() {
    const now = Date.now();
    if (accessTokenCache.token && accessTokenCache.expiresAt - 60_000 > now) {
      return accessTokenCache.token;
    }
    if (!hasConfig()) return "";

    const issuedAt = Math.floor(now / 1000);
    const header = Buffer.from(JSON.stringify({ alg: "RS256", typ: "JWT" })).toString("base64url");
    const claim = Buffer.from(
      JSON.stringify({
        iss: clientEmail,
        scope: "https://www.googleapis.com/auth/firebase.messaging",
        aud: "https://oauth2.googleapis.com/token",
        iat: issuedAt,
        exp: issuedAt + 3600,
      })
    ).toString("base64url");
    const unsigned = `${header}.${claim}`;
    const signature = crypto
      .createSign("RSA-SHA256")
      .update(unsigned)
      .end()
      .sign(privateKey)
      .toString("base64url");
    const assertion = `${unsigned}.${signature}`;
    const payload = new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }).toString();

    const text = await new Promise((resolve, reject) => {
      const req = https.request(
        "https://oauth2.googleapis.com/token",
        {
          method: "POST",
          headers: {
            "Content-Type": "application/x-www-form-urlencoded",
            "Content-Length": Buffer.byteLength(payload),
          },
        },
        (res) => {
          const chunks = [];
          res.on("data", (chunk) => chunks.push(chunk));
          res.on("end", () => {
            const body = Buffer.concat(chunks).toString("utf8");
            if (res.statusCode >= 200 && res.statusCode < 300) resolve(body);
            else reject(new Error(`oauth_${res.statusCode}:${body}`));
          });
        }
      );
      req.on("error", reject);
      req.write(payload);
      req.end();
    });

    const json = JSON.parse(text);
    accessTokenCache = {
      token: json.access_token || "",
      expiresAt: now + Number(json.expires_in || 3600) * 1000,
    };
    return accessTokenCache.token;
  }

  async function sendPayload(accessToken, token, payload) {
    return postJson(
      `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`,
      payload,
      { Authorization: `Bearer ${accessToken}` }
    );
  }

  function recordFailure(token, error) {
    tokenRepository.markFailure(token, error?.message || error);
    if (shouldDeleteToken(error)) {
      try {
        tokenRepository.remove(token);
      } catch {}
    }
  }

  async function sendToConversation(conversationId, senderUserId, _title, _bodyText, meta = {}) {
    if (!hasConfig()) {
      logFcm("message_batch_skipped_missing_config", {
        conversationId,
        senderUserId,
        messageId: meta.messageId || 0,
        kind: meta.kind || "text",
      }, true);
      return;
    }

    const tokens = tokenRepository.listConversationTokens(conversationId, senderUserId);
    if (!tokens.length) {
      logFcm("message_batch_skipped_no_tokens", {
        conversationId,
        senderUserId,
        messageId: meta.messageId || 0,
        kind: meta.kind || "text",
      }, true);
      return;
    }

    logFcm("message_batch_start", {
      conversationId,
      senderUserId,
      messageId: meta.messageId || 0,
      createdAt: meta.createdAt || 0,
      kind: meta.kind || "text",
      tokenCount: tokens.length,
    });

    let accessToken;
    try {
      accessToken = await getAccessToken();
    } catch (error) {
      logFcm("message_batch_oauth_failed", {
        conversationId,
        senderUserId,
        messageId: meta.messageId || 0,
        kind: meta.kind || "text",
        error: String(error?.message || error || "unknown"),
      }, true);
      return;
    }
    if (!accessToken) {
      logFcm("message_batch_empty_access_token", {
        conversationId,
        senderUserId,
        messageId: meta.messageId || 0,
        kind: meta.kind || "text",
      }, true);
      return;
    }

    let deliveredCount = 0;
    let failedCount = 0;
    for (const row of tokens) {
      const text = localizedMessage(row.locale, tokenRepository.countUnreadMessagesForUser(row.user_id));
      try {
        await sendPayload(accessToken, row.token, {
          message: {
            token: row.token,
            data: {
              type: "chat",
              url: "/",
              locale: text.locale,
              conversation_id: String(conversationId),
              message_id: String(meta.messageId || 0),
              unread_count: String(text.unreadCount),
              title: text.title,
              body: text.body,
            },
            android: { priority: "high", ttl: "86400s" },
          },
        });
        deliveredCount += 1;
        tokenRepository.markSuccess(row.token);
        logFcm("message_sent", {
          conversationId,
          senderUserId,
          recipientUserId: row.user_id,
          messageId: meta.messageId || 0,
          createdAt: meta.createdAt || 0,
          kind: meta.kind || "text",
          unreadCount: text.unreadCount,
          token: maskToken(row.token),
        });
      } catch (error) {
        failedCount += 1;
        recordFailure(row.token, error);
        logFcm("message_send_failed", {
          conversationId,
          senderUserId,
          recipientUserId: row.user_id,
          messageId: meta.messageId || 0,
          createdAt: meta.createdAt || 0,
          kind: meta.kind || "text",
          token: maskToken(row.token),
          error: String(error?.message || error || "unknown"),
        }, true);
      }
    }
    logFcm("message_batch_complete", {
      conversationId,
      senderUserId,
      messageId: meta.messageId || 0,
      kind: meta.kind || "text",
      deliveredCount,
      failedCount,
    });
  }

  async function sendIncomingCallToUser(userId, invite) {
    if (!hasConfig()) {
      logCall("incoming_fcm_skipped_missing_config", { userId, conversationId: invite.conversationId }, true);
      return { delivered: false, reason: "missing_fcm_config" };
    }
    const tokens = tokenRepository.listUserTokens(userId);
    if (!tokens.length) {
      logCall("incoming_fcm_skipped_no_tokens", { userId, conversationId: invite.conversationId }, true);
      return { delivered: false, reason: "no_fcm_tokens" };
    }

    let accessToken;
    try {
      accessToken = await getAccessToken();
    } catch (error) {
      logCall("incoming_fcm_oauth_failed", {
        userId,
        conversationId: invite.conversationId,
        error: String(error?.message || error || "unknown"),
      }, true);
      return { delivered: false, reason: "oauth_failed" };
    }
    if (!accessToken) {
      logCall("incoming_fcm_empty_access_token", { userId, conversationId: invite.conversationId }, true);
      return { delivered: false, reason: "empty_access_token" };
    }

    let delivered = false;
    let lastError = "";
    for (const row of tokens) {
      const isZh = /^zh/i.test(String(row.locale || "").trim());
      try {
        await sendPayload(accessToken, row.token, {
          message: {
            token: row.token,
            data: {
              type: "incoming_call",
              conversation_id: String(invite.conversationId),
              peer_user_code: invite.peerUserCode || "",
              peer_username: invite.peerUsername,
              created_at: String(invite.createdAt),
              locale: isZh ? "zh" : "en",
            },
            android: {
              priority: "high",
              ttl: "30s",
              collapse_key: `call_${invite.conversationId}`,
            },
          },
        });
        delivered = true;
        tokenRepository.markSuccess(row.token);
        logCall("incoming_fcm_sent", {
          userId,
          conversationId: invite.conversationId,
          token: maskToken(row.token),
        });
      } catch (error) {
        lastError = String(error?.message || error || "unknown");
        recordFailure(row.token, error);
        logCall("incoming_fcm_send_failed", {
          userId,
          conversationId: invite.conversationId,
          token: maskToken(row.token),
          error: lastError,
        }, true);
      }
    }
    if (!delivered) {
      logCall("incoming_fcm_all_tokens_failed", {
        userId,
        conversationId: invite.conversationId,
        tokenCount: tokens.length,
        lastError,
      }, true);
    }
    return { delivered, reason: delivered ? "sent" : lastError || "all_tokens_failed" };
  }

  async function sendCallHangupToUser(userId, conversationId) {
    if (!hasConfig()) return false;
    const tokens = tokenRepository.listUserTokens(userId);
    if (!tokens.length) return false;

    let accessToken;
    try {
      accessToken = await getAccessToken();
    } catch {
      return false;
    }
    if (!accessToken) return false;

    let delivered = false;
    for (const row of tokens) {
      try {
        await sendPayload(accessToken, row.token, {
          message: {
            token: row.token,
            data: { type: "call_hangup", conversation_id: String(conversationId) },
            android: {
              priority: "high",
              ttl: "10s",
              collapse_key: `call_${conversationId}`,
            },
          },
        });
        delivered = true;
        tokenRepository.markSuccess(row.token);
      } catch (error) {
        recordFailure(row.token, error);
      }
    }
    return delivered;
  }

  async function sendHealthCheckToUser(userId, diagnosticId) {
    if (!hasConfig()) return { delivered: false, reason: "missing_fcm_config", tokenCount: 0 };
    const tokens = tokenRepository.listUserTokens(userId);
    if (!tokens.length) return { delivered: false, reason: "no_fcm_tokens", tokenCount: 0 };
    let accessToken;
    try {
      accessToken = await getAccessToken();
    } catch (error) {
      logFcm("diagnostic_oauth_failed", { userId, error: error?.name || "Error" }, true);
      return { delivered: false, reason: "oauth_failed", tokenCount: tokens.length };
    }
    let deliveredCount = 0;
    for (const row of tokens) {
      try {
        await sendPayload(accessToken, row.token, {
          message: {
            token: row.token,
            data: {
              type: "push_diagnostic",
              diagnostic_id: String(diagnosticId || ""),
              created_at: String(Date.now()),
            },
            android: { priority: "high", ttl: "60s" },
          },
        });
        deliveredCount += 1;
        tokenRepository.markSuccess(row.token);
      } catch (error) {
        recordFailure(row.token, error);
      }
    }
    logFcm("diagnostic_dispatched", { userId, deliveredCount, tokenCount: tokens.length });
    return {
      delivered: deliveredCount > 0,
      reason: deliveredCount > 0 ? "sent" : "all_tokens_failed",
      deliveredCount,
      tokenCount: tokens.length,
    };
  }

  async function sendBlogCommentToUser(userId, postId) {
    if (!hasConfig()) {
      logFcm("blog_comment_skipped_missing_config", { userId, postId }, true);
      return { delivered: false, reason: "missing_fcm_config" };
    }
    const tokens = tokenRepository.listBlogNotificationTokens(userId);
    if (!tokens.length) {
      logFcm("blog_comment_skipped_no_enabled_tokens", { userId, postId });
      return { delivered: false, reason: "no_enabled_tokens" };
    }
    let accessToken;
    try {
      accessToken = await getAccessToken();
    } catch (error) {
      logFcm("blog_comment_oauth_failed", {
        userId,
        postId,
        error: String(error?.message || error || "unknown"),
      }, true);
      return { delivered: false, reason: "oauth_failed" };
    }
    let deliveredCount = 0;
    for (const row of tokens) {
      const locale = /^zh/i.test(String(row.locale || "")) ? "zh" : "en";
      const title = "Family Blog";
      const body = locale === "zh" ? "你的帖子有新评论" : "Your post has a new comment";
      try {
        await sendPayload(accessToken, row.token, {
          message: {
            token: row.token,
            data: {
              type: "blog_comment",
              blog_post_id: String(postId),
              locale,
              title,
              body,
            },
            android: {
              priority: "high",
              ttl: "86400s",
              collapse_key: `blog_post_${postId}`,
            },
          },
        });
        deliveredCount += 1;
        tokenRepository.markSuccess(row.token);
        logFcm("blog_comment_sent", { userId, postId, token: maskToken(row.token) });
      } catch (error) {
        recordFailure(row.token, error);
        logFcm("blog_comment_send_failed", {
          userId,
          postId,
          token: maskToken(row.token),
          error: String(error?.message || error || "unknown"),
        }, true);
      }
    }
    return {
      delivered: deliveredCount > 0,
      deliveredCount,
      tokenCount: tokens.length,
      reason: deliveredCount > 0 ? "sent" : "all_tokens_failed",
    };
  }

  return {
    hasConfig,
    sendToConversation,
    sendIncomingCallToUser,
    sendCallHangupToUser,
    sendHealthCheckToUser,
    sendBlogCommentToUser,
  };
}

module.exports = { createFcmService };
