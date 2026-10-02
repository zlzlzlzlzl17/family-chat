function registerConversationRoutes(context) {
  const {
    path,
    fs,
    UPLOAD_DIR,
    GROUP_ADMIN_LIMIT,
    GROUP_MESSAGE_TTL_OPTIONS_MS,
    app,
    db,
    ensureConversationReadState,
    currentGroupKeyEpoch,
    rotateAndBroadcastGroupKeyEpoch,
    authMiddleware,
    upload,
    getUsers,
    getConversationForUser,
    getConversationMembers,
    cleanupConversation,
    promoteGroupAdminIfNeeded,
    clearConversationHistory,
    getDirectConversationBetween,
    getGroupRole,
    isGroupOwner,
    isGroupAdmin,
    countGroupAdmins,
    groupAdminRequestToWire,
    isConversationMember,
    normalizeUserCode,
    groupJoinRequestToWire,
    getConversationTitleForUser,
    getConversationAvatarForUser,
    getConversationSummaries,
    broadcastToConversation,
    broadcastToUser,
  } = context;

app.get("/api/users", authMiddleware, (req, res) => {
  res.json({ items: getUsers() });
});

app.get("/api/conversations", authMiddleware, (req, res) => {
  res.json({ items: getConversationSummaries(req.auth.uid) });
});

app.delete("/api/conversations/:id", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind === "direct") {
    const members = getConversationMembers(conversation.id);
    cleanupConversation(conversation.id);
    for (const member of members) {
      broadcastToUser(member.id, { type: "conversation_removed", conversation_id: conversation.id, kind: "direct" });
    }
    return res.json({ ok: true });
  }

  if (conversation.kind === "group") {
    if (!isGroupOwner(req.auth.uid, conversation.id)) {
      return res.status(403).json({ error: "owner_required" });
    }
    const members = getConversationMembers(conversation.id);
    cleanupConversation(conversation.id);
    for (const member of members) {
      broadcastToUser(member.id, { type: "conversation_removed", conversation_id: conversation.id, kind: "group" });
    }
    return res.json({ ok: true });
  }

  return res.status(400).json({ error: "unsupported_conversation_kind" });
});

app.post("/api/conversations/:id/leave", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (isGroupOwner(req.auth.uid, conversation.id)) {
    return res.status(403).json({ error: "owner_cannot_leave_group" });
  }

  db.prepare("DELETE FROM conversation_members WHERE conversation_id=? AND user_id=?").run(conversation.id, req.auth.uid);
  db.prepare("DELETE FROM conversation_read_states WHERE conversation_id=? AND user_id=?").run(conversation.id, req.auth.uid);
  db.prepare("DELETE FROM conversation_delivery_states WHERE conversation_id=? AND user_id=?").run(conversation.id, req.auth.uid);
  db.prepare("DELETE FROM group_join_requests WHERE conversation_id=? AND requester_id=?").run(conversation.id, req.auth.uid);
  db.prepare("DELETE FROM group_admin_requests WHERE conversation_id=? AND (requester_id=? OR target_user_id=?)").run(conversation.id, req.auth.uid, req.auth.uid);
  const count = db.prepare("SELECT COUNT(*) AS c FROM conversation_members WHERE conversation_id=?").get(conversation.id).c;
  if (Number(count || 0) <= 0) {
    cleanupConversation(conversation.id);
  } else {
    promoteGroupAdminIfNeeded(conversation.id);
    rotateAndBroadcastGroupKeyEpoch(conversation.id);
  }
  broadcastToUser(req.auth.uid, { type: "conversation_removed", conversation_id: conversation.id, kind: "group" });
  broadcastToConversation(conversation.id, { type: "conversation_members_changed" });
  res.json({ ok: true });
});

app.get("/api/conversations/:id/manage", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });

  const members = getConversationMembers(conversation.id).map((member) => ({
    user_code: member.user_code || "",
    username: member.username,
    color: member.color,
    avatar_url: member.avatar_url || "",
    role: member.role || "member",
    joined_at: Number(member.joined_at || 0),
  }));
  const ownRole = conversation.kind === "group" ? getGroupRole(req.auth.uid, conversation.id) : "";
  const pendingJoinRequests = conversation.kind === "group" && isGroupAdmin(req.auth.uid, conversation.id)
    ? db
      .prepare(
        `
        SELECT gjr.*, c.group_code, c.title, c.avatar_url,
               u.user_code AS requester_user_code, u.username AS requester_username,
               u.color AS requester_color, u.avatar_url AS requester_avatar_url
        FROM group_join_requests gjr
        JOIN conversations c ON c.id = gjr.conversation_id
        JOIN users u ON u.id = gjr.requester_id
        WHERE gjr.conversation_id=? AND gjr.status='pending'
        ORDER BY gjr.created_at DESC
      `
      )
      .all(conversation.id)
      .map((row) => groupJoinRequestToWire(row, req.auth.uid))
    : [];
  const pendingAdminRequests = conversation.kind === "group" && isGroupOwner(req.auth.uid, conversation.id)
    ? db
      .prepare(
        `
        SELECT gar.*,
               ru.user_code AS requester_user_code, ru.username AS requester_username,
               tu.user_code AS target_user_code, tu.username AS target_username
        FROM group_admin_requests gar
        JOIN users ru ON ru.id = gar.requester_id
        JOIN users tu ON tu.id = gar.target_user_id
        WHERE gar.conversation_id=? AND gar.status='pending'
        ORDER BY gar.created_at DESC
      `
      )
      .all(conversation.id)
      .map(groupAdminRequestToWire)
    : [];
  const groupKeyEpoch = conversation.kind === "group" ? currentGroupKeyEpoch(conversation.id) : 0;
  const groupDeviceCount = conversation.kind === "group"
    ? Number(
        db
          .prepare(
            `
            SELECT COUNT(*) AS c
            FROM conversation_members cm
            JOIN device_identity_keys dik ON dik.user_id = cm.user_id
            JOIN devices d ON d.user_id=dik.user_id AND d.device_id=dik.device_id AND d.status='trusted'
            WHERE cm.conversation_id=?
            `
          )
          .get(conversation.id)?.c || 0
      )
    : 0;
  const groupReadyDeviceCount = conversation.kind === "group"
    ? Number(
        db
          .prepare(
            `
            SELECT COUNT(*) AS c
            FROM conversation_members cm
            JOIN device_identity_keys dik ON dik.user_id = cm.user_id
            JOIN direct_prekeys dp ON dp.user_id = dik.user_id AND dp.device_id = dik.device_id
            JOIN devices d ON d.user_id=dik.user_id AND d.device_id=dik.device_id AND d.status='trusted'
            WHERE cm.conversation_id=?
            `
          )
          .get(conversation.id)?.c || 0
      )
    : 0;

  res.json({
    conversation: {
      id: conversation.id,
      kind: conversation.kind,
      title: getConversationTitleForUser(conversation, req.auth.uid),
      group_code: conversation.group_code || "",
      avatar_url: conversation.kind === "direct" ? getConversationAvatarForUser(conversation, req.auth.uid) : conversation.avatar_url || "",
      message_ttl_ms: Number(conversation.message_ttl_ms || 0),
      own_role: ownRole,
      can_manage: conversation.kind === "direct" || isGroupAdmin(req.auth.uid, conversation.id),
      can_manage_owner: conversation.kind === "group" && isGroupOwner(req.auth.uid, conversation.id),
      admin_count: conversation.kind === "group" ? countGroupAdmins(conversation.id) : 0,
      admin_limit: GROUP_ADMIN_LIMIT,
      key_epoch: groupKeyEpoch,
      key_device_count: groupDeviceCount,
      key_ready_device_count: groupReadyDeviceCount,
    },
    members,
    pending_join_requests: pendingJoinRequests,
    pending_admin_requests: pendingAdminRequests,
  });
});

app.post("/api/conversations/:id/clear_history", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind === "group" && !isGroupAdmin(req.auth.uid, conversation.id)) {
    return res.status(403).json({ error: "not_group_admin" });
  }

  const result = clearConversationHistory(conversation.id);
  if (conversation.kind === "group") {
    rotateAndBroadcastGroupKeyEpoch(conversation.id);
  }
  broadcastToConversation(conversation.id, {
    type: "conversation_history_deleted",
    conversation_id: conversation.id,
    by: req.auth.username,
    ts: Date.now(),
  });
  res.json({ ok: true, ...result });
});

app.post("/api/conversations/:id/title", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupAdmin(req.auth.uid, conversation.id)) return res.status(403).json({ error: "not_group_admin" });
  const title = String(req.body?.title || "").trim().slice(0, 64);
  if (!title) return res.status(400).json({ error: "bad_group_title" });
  db.prepare("UPDATE conversations SET title=? WHERE id=?").run(title, conversation.id);
  broadcastToConversation(conversation.id, { type: "conversation_updated", conversation_id: conversation.id });
  res.json({ ok: true, title });
});

app.post("/api/conversations/:id/expiration", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupAdmin(req.auth.uid, conversation.id)) return res.status(403).json({ error: "not_group_admin" });
  const ttlMs = Number(req.body?.message_ttl_ms || 0);
  if (!GROUP_MESSAGE_TTL_OPTIONS_MS.has(ttlMs)) return res.status(400).json({ error: "bad_message_ttl" });
  db.prepare("UPDATE conversations SET message_ttl_ms=? WHERE id=?").run(ttlMs, conversation.id);
  broadcastToConversation(conversation.id, { type: "conversation_updated", conversation_id: conversation.id });
  res.json({ ok: true, message_ttl_ms: ttlMs });
});

app.post("/api/conversations/:id/avatar", authMiddleware, upload.single("avatar"), (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupAdmin(req.auth.uid, conversation.id)) return res.status(403).json({ error: "not_group_admin" });
  if (!req.file) return res.status(400).json({ error: "no_file" });

  if (typeof conversation.avatar_url === "string" && conversation.avatar_url.startsWith("/uploads/")) {
    try {
      const currentFile = path.basename(conversation.avatar_url);
      if (currentFile) {
        fs.unlinkSync(path.join(UPLOAD_DIR, currentFile));
      }
    } catch {}
  }

  const avatarUrl = `/uploads/${req.file.filename}`;
  db.prepare("UPDATE conversations SET avatar_url=? WHERE id=?").run(avatarUrl, conversation.id);
  broadcastToConversation(conversation.id, { type: "conversation_updated", conversation_id: conversation.id });
  res.json({ ok: true, avatar_url: avatarUrl });
});

app.post("/api/conversations/:id/members/:userCode/add", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupAdmin(req.auth.uid, conversation.id)) return res.status(403).json({ error: "not_group_admin" });

  const targetCode = normalizeUserCode(req.params.userCode);
  if (!/^\d{8}$/.test(targetCode)) return res.status(400).json({ error: "bad_user_code" });
  const target = db.prepare("SELECT id, user_code, username FROM users WHERE user_code=?").get(targetCode);
  if (!target || Number(target.id) === Number(req.auth.uid)) return res.status(404).json({ error: "user_not_found" });

  if (isConversationMember(target.id, conversation.id)) {
    return res.json({ ok: true, status: "already_member", conversation_id: conversation.id });
  }
  if (!getDirectConversationBetween(req.auth.uid, target.id)) {
    return res.status(403).json({ error: "not_contact" });
  }

  db.prepare(
    "INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?) ON CONFLICT(conversation_id, user_id) DO NOTHING"
  ).run(conversation.id, target.id, Date.now());
  ensureConversationReadState(conversation.id, target.id);
  db.prepare("DELETE FROM group_join_requests WHERE conversation_id=? AND requester_id=?").run(conversation.id, target.id);
  db.prepare("DELETE FROM group_admin_requests WHERE conversation_id=? AND (requester_id=? OR target_user_id=?)").run(conversation.id, target.id, target.id);
  rotateAndBroadcastGroupKeyEpoch(conversation.id);
  broadcastToUser(target.id, { type: "conversation_members_changed", conversation_id: conversation.id, kind: "group" });
  broadcastToConversation(conversation.id, { type: "conversation_members_changed" });
  res.json({ ok: true, status: "added", conversation_id: conversation.id, user_code: target.user_code || "" });
});

app.post("/api/conversations/:id/members/:userCode/remove", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupAdmin(req.auth.uid, conversation.id)) return res.status(403).json({ error: "not_group_admin" });

  const targetCode = normalizeUserCode(req.params.userCode);
  const target = db.prepare("SELECT id, username FROM users WHERE user_code=?").get(targetCode);
  if (!target || Number(target.id) === Number(req.auth.uid)) return res.status(404).json({ error: "user_not_found" });
  const targetRole = getGroupRole(target.id, conversation.id);
  if (targetRole === "owner") return res.status(403).json({ error: "cannot_remove_owner" });
  if (targetRole === "admin" && !isGroupOwner(req.auth.uid, conversation.id)) {
    return res.status(403).json({ error: "owner_required" });
  }

  db.prepare("DELETE FROM conversation_members WHERE conversation_id=? AND user_id=?").run(conversation.id, target.id);
  db.prepare("DELETE FROM conversation_read_states WHERE conversation_id=? AND user_id=?").run(conversation.id, target.id);
  db.prepare("DELETE FROM conversation_delivery_states WHERE conversation_id=? AND user_id=?").run(conversation.id, target.id);
  db.prepare("DELETE FROM group_join_requests WHERE conversation_id=? AND requester_id=?").run(conversation.id, target.id);
  db.prepare("DELETE FROM group_admin_requests WHERE conversation_id=? AND (requester_id=? OR target_user_id=?)").run(conversation.id, target.id, target.id);
  rotateAndBroadcastGroupKeyEpoch(conversation.id);
  broadcastToUser(target.id, { type: "conversation_removed", conversation_id: conversation.id, kind: "group" });
  broadcastToConversation(conversation.id, { type: "conversation_members_changed" });
  res.json({ ok: true });
});

app.post("/api/conversations/:id/transfer_owner", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupOwner(req.auth.uid, conversation.id)) return res.status(403).json({ error: "owner_required" });

  const targetCode = normalizeUserCode(req.body?.target_user_code);
  if (!/^\d{8}$/.test(targetCode)) return res.status(400).json({ error: "bad_user_code" });

  const target = db.prepare(
    `
      SELECT u.id, u.user_code, u.username, cm.role
      FROM users u
      JOIN conversation_members cm ON cm.user_id = u.id
      WHERE cm.conversation_id = ? AND u.user_code = ?
      LIMIT 1
    `
  ).get(conversation.id, targetCode);
  if (!target) return res.status(404).json({ error: "user_not_found" });
  if (Number(target.id) === Number(req.auth.uid)) return res.status(400).json({ error: "cannot_transfer_to_self" });
  if (target.role === "owner") return res.json({ ok: true, status: "already_owner" });

  const currentAdminCount = countGroupAdmins(conversation.id);
  const targetAdminContribution = target.role === "admin" ? 1 : 0;
  const previousOwnerRole = currentAdminCount - targetAdminContribution < GROUP_ADMIN_LIMIT ? "admin" : "member";

  db.transaction(() => {
    db.prepare("UPDATE conversation_members SET role=? WHERE conversation_id=? AND user_id=?")
      .run(previousOwnerRole, conversation.id, req.auth.uid);
    db.prepare("UPDATE conversation_members SET role='owner' WHERE conversation_id=? AND user_id=?")
      .run(conversation.id, target.id);
    db.prepare(
      `
        DELETE FROM group_admin_requests
        WHERE conversation_id=?
          AND (
            requester_id IN (?, ?)
            OR target_user_id IN (?, ?)
          )
      `
    ).run(conversation.id, req.auth.uid, target.id, req.auth.uid, target.id);
  })();

  broadcastToConversation(conversation.id, { type: "conversation_members_changed" });
  res.json({ ok: true, status: "transferred", previous_owner_role: previousOwnerRole, target_user_code: target.user_code });
});

app.post("/api/conversations/:id/admin_requests", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupAdmin(req.auth.uid, conversation.id)) return res.status(403).json({ error: "not_group_admin" });

  const targetCode = normalizeUserCode(req.body?.target_user_code);
  const target = db
    .prepare(
      `
      SELECT u.id, m.role
      FROM users u
      JOIN conversation_members m ON m.user_id=u.id
      WHERE u.user_code=? AND m.conversation_id=?
    `
    )
    .get(targetCode, conversation.id);
  if (!target) return res.status(404).json({ error: "user_not_found" });
  if (target.role === "owner" || target.role === "admin") return res.json({ ok: true, status: "already_admin" });
  if (countGroupAdmins(conversation.id) >= GROUP_ADMIN_LIMIT) return res.status(409).json({ error: "admin_limit_reached" });

  if (isGroupOwner(req.auth.uid, conversation.id)) {
    db.prepare("UPDATE conversation_members SET role='admin' WHERE conversation_id=? AND user_id=?").run(conversation.id, target.id);
    broadcastToConversation(conversation.id, { type: "conversation_members_changed" });
    return res.json({ ok: true, status: "approved" });
  }

  const pending = db
    .prepare(
      "SELECT id FROM group_admin_requests WHERE conversation_id=? AND target_user_id=? AND status='pending' ORDER BY id DESC LIMIT 1"
    )
    .get(conversation.id, target.id);
  if (pending) return res.json({ ok: true, status: "pending", request_id: pending.id });

  const result = db
    .prepare(
      "INSERT INTO group_admin_requests (conversation_id, requester_id, target_user_id, status, created_at, reviewed_at, reviewed_by) VALUES (?, ?, ?, 'pending', ?, 0, 0)"
    )
    .run(conversation.id, req.auth.uid, target.id, Date.now());
  res.json({ ok: true, status: "pending", request_id: Number(result.lastInsertRowid) });
});

app.post("/api/conversations/:id/admins/:userCode/remove", authMiddleware, (req, res) => {
  const conversationId = Math.max(Number(req.params.id || 0), 0);
  const conversation = getConversationForUser(req.auth.uid, conversationId);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  if (!isGroupOwner(req.auth.uid, conversation.id)) return res.status(403).json({ error: "owner_required" });

  const targetCode = normalizeUserCode(req.params.userCode);
  if (!/^\d{8}$/.test(targetCode)) return res.status(400).json({ error: "bad_user_code" });
  const target = db
    .prepare(
      `
      SELECT u.id, u.user_code, u.username, m.role
      FROM users u
      JOIN conversation_members m ON m.user_id=u.id
      WHERE u.user_code=? AND m.conversation_id=?
      LIMIT 1
    `
    )
    .get(targetCode, conversation.id);
  if (!target) return res.status(404).json({ error: "user_not_found" });
  if (target.role === "owner") return res.status(403).json({ error: "cannot_remove_owner" });
  if (target.role !== "admin") return res.json({ ok: true, status: "already_member", target_user_code: target.user_code || "" });

  db.transaction(() => {
    db.prepare("UPDATE conversation_members SET role='member' WHERE conversation_id=? AND user_id=? AND role='admin'")
      .run(conversation.id, target.id);
    db.prepare("DELETE FROM group_admin_requests WHERE conversation_id=? AND target_user_id=?")
      .run(conversation.id, target.id);
  })();

  broadcastToConversation(conversation.id, { type: "conversation_members_changed" });
  res.json({ ok: true, status: "removed", target_user_code: target.user_code || "" });
});

app.post("/api/group_admin_requests/:id/approve", authMiddleware, (req, res) => {
  const requestId = Number(req.params.id || 0);
  const request = db.prepare("SELECT * FROM group_admin_requests WHERE id=? AND status='pending'").get(requestId);
  if (!request) return res.status(404).json({ error: "request_not_found" });
  if (!isGroupOwner(req.auth.uid, request.conversation_id)) return res.status(403).json({ error: "owner_required" });
  if (countGroupAdmins(request.conversation_id) >= GROUP_ADMIN_LIMIT) return res.status(409).json({ error: "admin_limit_reached" });
  db.prepare("UPDATE group_admin_requests SET status='approved', reviewed_at=?, reviewed_by=? WHERE id=?").run(Date.now(), req.auth.uid, requestId);
  db.prepare("UPDATE conversation_members SET role='admin' WHERE conversation_id=? AND user_id=? AND role='member'").run(request.conversation_id, request.target_user_id);
  broadcastToConversation(request.conversation_id, { type: "conversation_members_changed" });
  res.json({ ok: true });
});

app.post("/api/group_admin_requests/:id/reject", authMiddleware, (req, res) => {
  const requestId = Number(req.params.id || 0);
  const request = db.prepare("SELECT * FROM group_admin_requests WHERE id=? AND status='pending'").get(requestId);
  if (!request) return res.status(404).json({ error: "request_not_found" });
  if (!isGroupOwner(req.auth.uid, request.conversation_id)) return res.status(403).json({ error: "owner_required" });
  db.prepare("UPDATE group_admin_requests SET status='rejected', reviewed_at=?, reviewed_by=? WHERE id=?").run(Date.now(), req.auth.uid, requestId);
  res.json({ ok: true });
});
}

module.exports = { registerConversationRoutes };
