function registerRelationshipRoutes(context) {
  const {
    app,
    db,
    generateUniqueGroupCode,
    ensureConversationReadState,
    rotateAndBroadcastGroupKeyEpoch,
    ensureDirectConversation,
    authMiddleware,
    getDirectConversationBetween,
    isGroupAdmin,
    isConversationMember,
    normalizeUserCode,
    normalizeGroupCode,
    contactRequestToWire,
    groupJoinRequestToWire,
    broadcastToConversation,
    broadcastToUser,
  } = context;

app.get("/api/contacts/lookup", authMiddleware, (req, res) => {
  const userCode = normalizeUserCode(req.query.user_code);
  if (!/^\d{8}$/.test(userCode)) return res.status(400).json({ error: "bad_user_code" });

  const target = db
    .prepare(
      // System accounts (the announcement author) are not people and cannot hold
      // a conversation. Excluded here rather than in the caller so neither the
      // lookup nor the request path can reach one - a 404 either way, so a code
      // read off a post discloses nothing.
      "SELECT id, user_code, username, color, avatar_url FROM users WHERE user_code=? AND account_status!='system'"
    )
    .get(userCode);
  if (!target || Number(target.id) === Number(req.auth.uid)) {
    return res.status(404).json({ error: "user_not_found" });
  }

  const direct = getDirectConversationBetween(req.auth.uid, target.id);
  const outgoing = db
    .prepare(
      "SELECT status FROM contact_requests WHERE requester_id=? AND target_user_id=? AND status='pending' ORDER BY id DESC LIMIT 1"
    )
    .get(req.auth.uid, target.id);
  const incoming = db
    .prepare(
      "SELECT id, status FROM contact_requests WHERE requester_id=? AND target_user_id=? AND status='pending' ORDER BY id DESC LIMIT 1"
    )
    .get(target.id, req.auth.uid);

  res.json({
    user: {
      user_code: target.user_code,
      username: target.username,
      color: target.color,
      avatar_url: target.avatar_url || "",
    },
    conversation_id: direct?.id || 0,
    is_contact: !!direct,
    outgoing_pending: !!outgoing,
    incoming_request_id: incoming?.id || 0,
    incoming_pending: !!incoming,
  });
});

app.get("/api/contact_requests", authMiddleware, (req, res) => {
  const rows = db
    .prepare(
      `
      SELECT cr.*,
             ru.user_code AS requester_user_code, ru.username AS requester_username,
             ru.color AS requester_color, ru.avatar_url AS requester_avatar_url,
             tu.user_code AS target_user_code, tu.username AS target_username,
             tu.color AS target_color, tu.avatar_url AS target_avatar_url
      FROM contact_requests cr
      JOIN users ru ON ru.id = cr.requester_id
      JOIN users tu ON tu.id = cr.target_user_id
      WHERE (cr.requester_id=? OR cr.target_user_id=?)
        AND cr.status='pending'
      ORDER BY cr.created_at DESC
    `
    )
    .all(req.auth.uid, req.auth.uid);
  res.json({ items: rows.map((row) => contactRequestToWire(row, req.auth.uid)) });
});

app.post("/api/contact_requests", authMiddleware, (req, res) => {
  const userCode = normalizeUserCode(req.body?.user_code);
  if (!/^\d{8}$/.test(userCode)) return res.status(400).json({ error: "bad_user_code" });

  const target = db
    .prepare(
      // System accounts (the announcement author) are not people and cannot hold
      // a conversation. Excluded here rather than in the caller so neither the
      // lookup nor the request path can reach one - a 404 either way, so a code
      // read off a post discloses nothing.
      "SELECT id, user_code, username, color, avatar_url FROM users WHERE user_code=? AND account_status!='system'"
    )
    .get(userCode);
  if (!target || Number(target.id) === Number(req.auth.uid)) {
    return res.status(404).json({ error: "user_not_found" });
  }

  const existing = getDirectConversationBetween(req.auth.uid, target.id);
  if (existing) return res.json({ ok: true, status: "connected", conversation_id: existing.id });

  const incoming = db
    .prepare(
      "SELECT id FROM contact_requests WHERE requester_id=? AND target_user_id=? AND status='pending' ORDER BY id DESC LIMIT 1"
    )
    .get(target.id, req.auth.uid);
  if (incoming) return res.status(409).json({ error: "incoming_request_pending", request_id: incoming.id });

  const outgoing = db
    .prepare(
      "SELECT id FROM contact_requests WHERE requester_id=? AND target_user_id=? AND status='pending' ORDER BY id DESC LIMIT 1"
    )
    .get(req.auth.uid, target.id);
  if (outgoing) return res.json({ ok: true, status: "pending", request_id: outgoing.id });

  const result = db
    .prepare("INSERT INTO contact_requests (requester_id, target_user_id, status, created_at, reviewed_at) VALUES (?, ?, 'pending', ?, 0)")
    .run(req.auth.uid, target.id, Date.now());
  res.json({ ok: true, status: "pending", request_id: Number(result.lastInsertRowid) });
});

app.post("/api/contact_requests/:id/approve", authMiddleware, (req, res) => {
  const requestId = Number(req.params.id || 0);
  const request = db
    .prepare("SELECT * FROM contact_requests WHERE id=? AND target_user_id=? AND status='pending'")
    .get(requestId, req.auth.uid);
  if (!request) return res.status(404).json({ error: "request_not_found" });
  db.prepare("UPDATE contact_requests SET status='approved', reviewed_at=? WHERE id=?").run(Date.now(), requestId);
  const conversationId = ensureDirectConversation(request.requester_id, request.target_user_id);
  res.json({ ok: true, conversation_id: conversationId });
});

app.post("/api/contact_requests/:id/reject", authMiddleware, (req, res) => {
  const requestId = Number(req.params.id || 0);
  const request = db
    .prepare("SELECT * FROM contact_requests WHERE id=? AND target_user_id=? AND status='pending'")
    .get(requestId, req.auth.uid);
  if (!request) return res.status(404).json({ error: "request_not_found" });
  db.prepare("UPDATE contact_requests SET status='rejected', reviewed_at=? WHERE id=?").run(Date.now(), requestId);
  res.json({ ok: true });
});

app.get("/api/groups/lookup", authMiddleware, (req, res) => {
  const groupCode = normalizeGroupCode(req.query.group_code);
  if (!/^\d{10}$/.test(groupCode)) return res.status(400).json({ error: "bad_group_code" });

  const group = db
    .prepare("SELECT id, group_code, title, avatar_url FROM conversations WHERE kind='group' AND group_code=?")
    .get(groupCode);
  if (!group) return res.status(404).json({ error: "group_not_found" });

  const member = isConversationMember(req.auth.uid, group.id);
  const pending = db
    .prepare(
      "SELECT id FROM group_join_requests WHERE conversation_id=? AND requester_id=? AND status='pending' ORDER BY id DESC LIMIT 1"
    )
    .get(group.id, req.auth.uid);
  res.json({
    group: {
      conversation_id: group.id,
      group_code: group.group_code,
      title: group.title,
      avatar_url: group.avatar_url || "",
    },
    is_member: member,
    pending_request_id: pending?.id || 0,
    pending: !!pending,
  });
});

app.post("/api/groups", authMiddleware, (req, res) => {
  const title = String(req.body?.title || "").trim().slice(0, 64);
  if (title.length < 1) return res.status(400).json({ error: "bad_group_title" });
  const groupCode = generateUniqueGroupCode();
  const now = Date.now();
  const result = db
    .prepare("INSERT INTO conversations (kind, slug, group_code, title, avatar_url, created_at) VALUES ('group', ?, ?, ?, '', ?)")
    .run(`group:${groupCode}`, groupCode, title, now);
  const conversationId = Number(result.lastInsertRowid);
  db.prepare(
    "INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'owner', ?)"
  ).run(conversationId, req.auth.uid, now);
  ensureConversationReadState(conversationId, req.auth.uid);
  res.json({ ok: true, conversation_id: conversationId, group_code: groupCode, title });
});

app.get("/api/group_join_requests", authMiddleware, (req, res) => {
  const rows = db
    .prepare(
      `
      SELECT gjr.*, c.group_code, c.title, c.avatar_url,
             u.user_code AS requester_user_code, u.username AS requester_username,
             u.color AS requester_color, u.avatar_url AS requester_avatar_url
      FROM group_join_requests gjr
      JOIN conversations c ON c.id = gjr.conversation_id
      JOIN users u ON u.id = gjr.requester_id
      WHERE gjr.status='pending'
        AND (
          gjr.requester_id=?
          OR EXISTS (
            SELECT 1 FROM conversation_members m
            WHERE m.conversation_id=gjr.conversation_id
              AND m.user_id=?
              AND m.role IN ('owner', 'admin')
          )
          OR EXISTS (SELECT 1 FROM users admin_user WHERE admin_user.id=? AND admin_user.is_admin=1)
        )
      ORDER BY gjr.created_at DESC
    `
    )
    .all(req.auth.uid, req.auth.uid, req.auth.uid);
  res.json({ items: rows.map((row) => groupJoinRequestToWire(row, req.auth.uid)) });
});

app.post("/api/group_join_requests", authMiddleware, (req, res) => {
  const groupCode = normalizeGroupCode(req.body?.group_code);
  if (!/^\d{10}$/.test(groupCode)) return res.status(400).json({ error: "bad_group_code" });
  const group = db
    .prepare("SELECT id FROM conversations WHERE kind='group' AND group_code=?")
    .get(groupCode);
  if (!group) return res.status(404).json({ error: "group_not_found" });
  if (isConversationMember(req.auth.uid, group.id)) {
    return res.json({ ok: true, status: "member", conversation_id: group.id });
  }
  const pending = db
    .prepare("SELECT id FROM group_join_requests WHERE conversation_id=? AND requester_id=? AND status='pending' ORDER BY id DESC LIMIT 1")
    .get(group.id, req.auth.uid);
  if (pending) return res.json({ ok: true, status: "pending", request_id: pending.id });

  const result = db
    .prepare("INSERT INTO group_join_requests (conversation_id, requester_id, status, created_at, reviewed_at, reviewed_by) VALUES (?, ?, 'pending', ?, 0, 0)")
    .run(group.id, req.auth.uid, Date.now());
  res.json({ ok: true, status: "pending", request_id: Number(result.lastInsertRowid) });
});

app.post("/api/group_join_requests/:id/approve", authMiddleware, (req, res) => {
  const requestId = Number(req.params.id || 0);
  const request = db.prepare("SELECT * FROM group_join_requests WHERE id=? AND status='pending'").get(requestId);
  if (!request) return res.status(404).json({ error: "request_not_found" });
  if (!isGroupAdmin(req.auth.uid, request.conversation_id)) return res.status(403).json({ error: "not_group_admin" });

  db.prepare("UPDATE group_join_requests SET status='approved', reviewed_at=?, reviewed_by=? WHERE id=?")
    .run(Date.now(), req.auth.uid, requestId);
  db.prepare(
    "INSERT INTO conversation_members (conversation_id, user_id, role, joined_at) VALUES (?, ?, 'member', ?) ON CONFLICT(conversation_id, user_id) DO NOTHING"
  ).run(request.conversation_id, request.requester_id, Date.now());
  ensureConversationReadState(request.conversation_id, request.requester_id);
  rotateAndBroadcastGroupKeyEpoch(request.conversation_id);
  broadcastToConversation(request.conversation_id, { type: "conversation_members_changed" });
  broadcastToUser(request.requester_id, { type: "conversation_members_changed", conversation_id: request.conversation_id, kind: "group" });
  res.json({ ok: true, conversation_id: request.conversation_id });
});

app.post("/api/group_join_requests/:id/reject", authMiddleware, (req, res) => {
  const requestId = Number(req.params.id || 0);
  const request = db.prepare("SELECT * FROM group_join_requests WHERE id=? AND status='pending'").get(requestId);
  if (!request) return res.status(404).json({ error: "request_not_found" });
  if (!isGroupAdmin(req.auth.uid, request.conversation_id)) return res.status(403).json({ error: "not_group_admin" });
  db.prepare("UPDATE group_join_requests SET status='rejected', reviewed_at=?, reviewed_by=? WHERE id=?")
    .run(Date.now(), req.auth.uid, requestId);
  res.json({ ok: true });
});
}

module.exports = { registerRelationshipRoutes };
