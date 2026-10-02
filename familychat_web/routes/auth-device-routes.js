function registerPublicAuthRoutes(context) {
  const {
    bcrypt,
    app,
    db,
    getClientIp,
    hashRefreshToken,
    newOpaqueToken,
    isValidUsername,
  } = context;

app.post("/register_request", (req, res) => {
  const username = typeof req.body?.username === "string" ? req.body.username.trim() : "";
  const password = typeof req.body?.password === "string" ? req.body.password : "";
  if (!isValidUsername(username) || password.length < 8) {
    return res.status(400).json({ error: "invalid_registration_data" });
  }
  if (db.prepare("SELECT 1 AS ok FROM users WHERE username=?").get(username)) {
    return res.status(409).json({ error: "username_taken" });
  }
  if (
    db.prepare("SELECT 1 AS ok FROM registration_requests WHERE requested_username=? AND status='pending'").get(username)
  ) {
    return res.status(409).json({ error: "request_already_pending" });
  }

  const requestToken = newOpaqueToken(32);
  const result = db.prepare(
    `
    INSERT INTO registration_requests (
      requested_username, password_hash, request_ip, status, review_note,
      created_at, reviewed_at, reviewed_by, request_token_hash, approved_user_code
    )
    VALUES (?, ?, ?, 'pending', '', ?, 0, '', ?, '')
  `
  ).run(
    username,
    bcrypt.hashSync(password, 12),
    getClientIp(req),
    Date.now(),
    hashRefreshToken(requestToken)
  );

  res.json({
    ok: true,
    status: "pending",
    request_id: Number(result.lastInsertRowid),
    request_token: requestToken,
  });
});

app.post("/register_request/status", (req, res) => {
  const requestToken = String(req.body?.request_token || "");
  if (requestToken.length < 20) return res.status(400).json({ error: "bad_request_token" });
  const request = db
    .prepare(
      `
        SELECT status, review_note, approved_user_code, reviewed_at
        FROM registration_requests
        WHERE request_token_hash=?
      `
    )
    .get(hashRefreshToken(requestToken));
  if (!request) return res.status(404).json({ error: "request_not_found" });
  res.json({
    status: request.status,
    review_note: request.review_note || "",
    user_code: request.status === "approved" ? request.approved_user_code || "" : "",
    reviewed_at: Number(request.reviewed_at || 0),
  });
});
}

function registerAuthDeviceRoutes(context) {
  const {
    bcrypt,
    MAX_CIPHERTEXT_LEN,
    ACCESS_TOKEN_TTL_SECONDS,
    REFRESH_TOKEN_TTL_MS,
    DEVICE_STATUS_TRUSTED,
    DEVICE_STATUS_REVOKED,
    SESSION_STATUS_PENDING,
    SESSION_STATUS_ACTIVE,
    SESSION_STATUS_REVOKED,
    app,
    db,
    getClientIp,
    getClientType,
    ensureReadState,
    currentGroupKeyEpoch,
    hashRefreshToken,
    newOpaqueToken,
    createAuthSession,
    issueSessionResponse,
    pendingAuthMiddleware,
    authMiddleware,
    getAppLoginAttemptKey,
    getManageLoginAttempt,
    resetManageLoginAttempt,
    registerFailedAppLogin,
    emitDeviceSafetyChangeNotices,
    emitDeviceAddedNotices,
    broadcastDirectPeerKeysChanged,
    approveDevice,
    revokeDevice,
    getUserById,
    getConversationForUser,
    normalizeUserCode,
    normalizeDeviceId,
    isValidDeviceId,
    ensureLoginDevice,
    normalizePublicKey,
    deviceKeyFingerprint,
    deviceIdentityToWire,
    broadcastToUser,
    forceLogoutSession,
  } = context;

app.post("/api/login", (req, res) => {
  const userCode = String(req.body?.user_code || "").trim();
  const legacyUsername = String(req.body?.username || "").trim();
  const password = req.body?.password;
  if ((!/^\d{8}$/.test(userCode) && !legacyUsername) || typeof password !== "string") {
    return res.status(400).json({ error: "bad_request" });
  }
  const loginIdentifier = /^\d{8}$/.test(userCode) ? userCode : legacyUsername;
  const attemptKey = getAppLoginAttemptKey(req, loginIdentifier);
  const existingAttempt = getManageLoginAttempt(attemptKey);
  const retryAfterMs = Math.max(Number(existingAttempt?.cooldown_until || 0) - Date.now(), 0);
  if (retryAfterMs > 0) {
    return res.status(429).json({ error: "cooldown_active", retry_after_ms: retryAfterMs });
  }

  const user = /^\d{8}$/.test(userCode)
    ? db.prepare("SELECT * FROM users WHERE user_code=?").get(userCode)
    : db.prepare("SELECT * FROM users WHERE username=?").get(legacyUsername);
  if (!user) {
    const failed = registerFailedAppLogin(attemptKey);
    return res.status(failed.retry_after_ms > 0 ? 429 : 401).json({
      error: failed.retry_after_ms > 0 ? "cooldown_active" : "invalid_credentials",
      retry_after_ms: failed.retry_after_ms,
    });
  }
  // 'system' is the announcement author, which is not a person and has no
  // password anybody holds. It is listed here so the refusal is explicit rather
  // than resting on the hash being unguessable.
  if (
    user.account_status === "disabled" ||
    user.account_status === "deleted" ||
    user.account_status === "system"
  ) {
    return res.status(403).json({ error: "account_unavailable" });
  }
  if (!bcrypt.compareSync(password, user.password_hash)) {
    const failed = registerFailedAppLogin(attemptKey);
    return res.status(failed.retry_after_ms > 0 ? 429 : 401).json({
      error: failed.retry_after_ms > 0 ? "cooldown_active" : "invalid_credentials",
      retry_after_ms: failed.retry_after_ms,
    });
  }
  resetManageLoginAttempt(attemptKey);

  const clientType = getClientType(req);
  const device = ensureLoginDevice(user, req, req.body?.device_id);
  if (!device) return res.status(400).json({ error: "device_id_required" });
  if (device.denied || device.status === DEVICE_STATUS_REVOKED) {
    return res.status(403).json({ error: "device_revoked" });
  }

  const previousSessions = db
    .prepare(
      `
        SELECT session_id
        FROM auth_sessions
        WHERE user_id=? AND device_id=? AND client_type=? AND status!='revoked'
      `
    )
    .all(user.id, device.device_id, clientType);
  db.prepare(
    `
      UPDATE auth_sessions
      SET status='revoked', revoked_at=?
      WHERE user_id=? AND device_id=? AND client_type=? AND status!='revoked'
    `
  ).run(Date.now(), user.id, device.device_id, clientType);
  for (const previous of previousSessions) {
    forceLogoutSession(previous.session_id, "session_replaced");
  }

  const created = createAuthSession(user, device, clientType, req);
  ensureReadState(user.id);
  db.prepare("UPDATE users SET last_login_ip=? WHERE id=?").run(getClientIp(req), user.id);
  if (device.newly_created) {
    broadcastToUser(user.id, {
      type: "devices_changed",
      device_id: device.device_id,
      device_status: device.status,
    });
  }
  created.session.device_status = device.status;
  res.json(issueSessionResponse(user, created.session, created.refreshToken));
});

app.post("/api/session/refresh", (req, res) => {
  const refreshToken = String(req.body?.refresh_token || "");
  const deviceId = normalizeDeviceId(req.body?.device_id);
  if (refreshToken.length < 40 || !isValidDeviceId(deviceId)) {
    return res.status(400).json({ error: "bad_refresh_request" });
  }
  const now = Date.now();
  const session = db
    .prepare(
      `
        SELECT s.*, d.status AS device_status,
               u.user_code, u.username, u.color, u.avatar_url, u.is_admin, u.account_status
        FROM auth_sessions s
        JOIN devices d ON d.user_id=s.user_id AND d.device_id=s.device_id
        JOIN users u ON u.id=s.user_id
        WHERE s.refresh_token_hash=? AND s.device_id=?
      `
    )
    .get(hashRefreshToken(refreshToken), deviceId);
  if (
    !session ||
    session.status === SESSION_STATUS_REVOKED ||
    Number(session.refresh_expires_at || 0) <= now ||
    session.device_status === DEVICE_STATUS_REVOKED ||
    session.account_status === "disabled" ||
    session.account_status === "deleted" ||
    session.account_status === "system"
  ) {
    return res.status(401).json({ error: "session_expired" });
  }

  const nextRefreshToken = newOpaqueToken(48);
  const currentRefreshTokenHash = hashRefreshToken(refreshToken);
  const nextStatus =
    session.device_status === DEVICE_STATUS_TRUSTED ? SESSION_STATUS_ACTIVE : SESSION_STATUS_PENDING;
  const rotated = db.prepare(
    `
      UPDATE auth_sessions
      SET refresh_token_hash=?, status=?, last_seen_at=?, access_expires_at=?,
          refresh_expires_at=?, ip=?
      WHERE session_id=? AND refresh_token_hash=? AND status!='revoked'
    `
  ).run(
    hashRefreshToken(nextRefreshToken),
    nextStatus,
    now,
    now + ACCESS_TOKEN_TTL_SECONDS * 1000,
    now + REFRESH_TOKEN_TTL_MS,
    getClientIp(req),
    session.session_id,
    currentRefreshTokenHash
  );
  if (rotated.changes !== 1) {
    return res.status(401).json({ error: "session_expired" });
  }
  const updatedSession = db.prepare("SELECT * FROM auth_sessions WHERE session_id=?").get(session.session_id);
  updatedSession.device_status = session.device_status;
  res.json(issueSessionResponse(session, updatedSession, nextRefreshToken));
});

app.get("/api/session/status", pendingAuthMiddleware, (req, res) => {
  res.json({
    user_code: req.auth.user_code || "",
    username: req.auth.username || "",
    device_id: req.auth.device_id,
    device_status: req.auth.device_status,
    session_status: req.authSession.status,
  });
});

app.post("/api/logout", pendingAuthMiddleware, (req, res) => {
  db.prepare("UPDATE auth_sessions SET status='revoked', revoked_at=? WHERE session_id=?").run(
    Date.now(),
    req.auth.sid
  );
  db.prepare("DELETE FROM fcm_tokens WHERE user_id=? AND device_id=?").run(
    req.auth.uid,
    req.auth.device_id
  );
  forceLogoutSession(req.auth.sid, "logout");
  res.json({ ok: true });
});

app.get("/api/me", pendingAuthMiddleware, (req, res) => {
  const user = getUserById(req.auth.uid);
  res.json({
    user_code: user?.user_code || req.auth.user_code || "",
    username: user?.username || req.auth.username,
    color: user?.color || req.auth.color,
    avatar_url: user?.avatar_url || "",
    is_admin: !!(user?.is_admin ?? req.auth.is_admin),
    device_status: req.auth.device_status,
  });
});

app.post("/api/account_deletion_request", authMiddleware, (req, res) => {
  const user = getUserById(req.auth.uid);
  if (!user) return res.status(404).json({ error: "user_not_found" });
  const existing = db
    .prepare("SELECT id FROM account_deletion_requests WHERE user_id=? AND status='pending' ORDER BY id DESC LIMIT 1")
    .get(user.id);
  if (existing) return res.json({ ok: true, status: "pending", request_id: existing.id });

  const result = db
    .prepare(
      `
      INSERT INTO account_deletion_requests (
        user_id, username_snapshot, user_code_snapshot, request_ip, status, review_note, created_at, reviewed_at, reviewed_by
      )
      VALUES (?, ?, ?, ?, 'pending', '', ?, 0, '')
    `
    )
    .run(user.id, user.username, user.user_code || "", getClientIp(req), Date.now());
  res.json({ ok: true, status: "pending", request_id: Number(result.lastInsertRowid) });
});

app.post("/api/device_identity", pendingAuthMiddleware, (req, res) => {
  const deviceId = normalizeDeviceId(req.body?.device_id);
  const publicKey = normalizePublicKey(req.body?.public_key);
  const platform = String(req.body?.platform || "android").trim().slice(0, 32) || "android";
  const deviceName = String(req.body?.device_name || "").trim().slice(0, 120);
  const keyAlg = String(req.body?.key_alg || "").trim().slice(0, 64);
  if (!isValidDeviceId(deviceId) || publicKey.length < 80 || !keyAlg) {
    return res.status(400).json({ error: "bad_device_identity" });
  }
  if (deviceId !== req.auth.device_id) {
    return res.status(403).json({ error: "device_mismatch" });
  }

  const now = Date.now();
  const existingDeviceCount = Number(
    db.prepare("SELECT COUNT(*) AS c FROM device_identity_keys WHERE user_id=?").get(req.auth.uid)?.c || 0
  );
  const fingerprint = deviceKeyFingerprint(publicKey);
  const previousIdentity = db
    .prepare("SELECT fingerprint FROM device_identity_keys WHERE user_id=? AND device_id=?")
    .get(req.auth.uid, deviceId);
  db.prepare(
    `
      INSERT INTO device_identity_keys (
        user_id, device_id, platform, device_name, key_alg, public_key, fingerprint, created_at, updated_at, last_seen_at
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(user_id, device_id) DO UPDATE SET
        platform=excluded.platform,
        device_name=excluded.device_name,
        key_alg=excluded.key_alg,
        public_key=excluded.public_key,
        fingerprint=excluded.fingerprint,
        updated_at=excluded.updated_at,
        last_seen_at=excluded.last_seen_at
    `
  ).run(req.auth.uid, deviceId, platform, deviceName, keyAlg, publicKey, fingerprint, now, now, now);
  if (req.auth.device_status === DEVICE_STATUS_TRUSTED && previousIdentity) {
    emitDeviceSafetyChangeNotices(req.auth.uid, deviceId, deviceName, previousIdentity.fingerprint || "", fingerprint);
  } else if (req.auth.device_status === DEVICE_STATUS_TRUSTED && existingDeviceCount > 0) {
    emitDeviceAddedNotices(req.auth.uid, deviceId, deviceName, fingerprint);
  }
  db.prepare("UPDATE devices SET device_name=?, platform=?, last_seen_at=?, last_ip=? WHERE user_id=? AND device_id=?").run(
    deviceName,
    platform,
    now,
    getClientIp(req),
    req.auth.uid,
    deviceId
  );
  broadcastToUser(req.auth.uid, { type: "devices_changed", device_id: deviceId });

  const row = db
    .prepare(
      `
        SELECT d.*, u.user_code, u.username
        FROM device_identity_keys d
        JOIN users u ON u.id = d.user_id
        WHERE d.user_id=? AND d.device_id=?
      `
    )
    .get(req.auth.uid, deviceId);
  res.json({ ok: true, item: deviceIdentityToWire(row) });
});

app.post("/api/direct_prekey", pendingAuthMiddleware, (req, res) => {
  const deviceId = normalizeDeviceId(req.body?.device_id);
  const identityEcdhPublic = normalizePublicKey(req.body?.identity_ecdh_public);
  const identityEcdhSignature = normalizePublicKey(req.body?.identity_ecdh_signature);
  const signedPrekeyPublic = normalizePublicKey(req.body?.signed_prekey_public);
  const signedPrekeySignature = normalizePublicKey(req.body?.signed_prekey_signature);
  const keyAlg = String(req.body?.key_alg || "").trim().slice(0, 64);
  if (
    !isValidDeviceId(deviceId) ||
    signedPrekeyPublic.length < 80 ||
    signedPrekeySignature.length < 40 ||
    (identityEcdhPublic && identityEcdhPublic.length < 80) ||
    (identityEcdhSignature && identityEcdhSignature.length < 40) ||
    !keyAlg
  ) {
    return res.status(400).json({ error: "bad_direct_prekey" });
  }
  if (deviceId !== req.auth.device_id) {
    return res.status(403).json({ error: "device_mismatch" });
  }
  const identity = db
    .prepare("SELECT id FROM device_identity_keys WHERE user_id=? AND device_id=?")
    .get(req.auth.uid, deviceId);
  if (!identity) return res.status(400).json({ error: "device_identity_required" });

  const now = Date.now();
  const previousPrekey = db
    .prepare(
      `
        SELECT key_alg, identity_ecdh_public, signed_prekey_public
        FROM direct_prekeys
        WHERE user_id=? AND device_id=?
      `
    )
    .get(req.auth.uid, deviceId);
  const prekeyChanged =
    !previousPrekey ||
    previousPrekey.key_alg !== keyAlg ||
    String(previousPrekey.identity_ecdh_public || "") !== identityEcdhPublic ||
    String(previousPrekey.signed_prekey_public || "") !== signedPrekeyPublic;
  const oneTimePrekeys = Array.isArray(req.body?.one_time_prekeys) ? req.body.one_time_prekeys : [];
  const sanitizedOneTimePrekeys = oneTimePrekeys
    .slice(0, 50)
    .map((item) => ({
      id: String(item?.id || "").trim().slice(0, 80),
      publicKey: normalizePublicKey(item?.public_key),
      signature: normalizePublicKey(item?.signature),
    }))
    .filter((item) => item.id && item.publicKey.length >= 80 && item.signature.length >= 40);

  db.transaction(() => {
    db.prepare(
      `
        INSERT INTO direct_prekeys (
          user_id, device_id, key_alg, identity_ecdh_public, identity_ecdh_signature,
          signed_prekey_public, signed_prekey_signature, created_at, updated_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, device_id) DO UPDATE SET
          key_alg=excluded.key_alg,
          identity_ecdh_public=excluded.identity_ecdh_public,
          identity_ecdh_signature=excluded.identity_ecdh_signature,
          signed_prekey_public=excluded.signed_prekey_public,
          signed_prekey_signature=excluded.signed_prekey_signature,
          updated_at=excluded.updated_at
      `
    ).run(
      req.auth.uid,
      deviceId,
      keyAlg,
      identityEcdhPublic,
      identityEcdhSignature,
      signedPrekeyPublic,
      signedPrekeySignature,
      now,
      now
    );

    const insertOneTimePrekey = db.prepare(
      `
        INSERT OR IGNORE INTO direct_one_time_prekeys (
          user_id, device_id, prekey_id, key_alg, public_key, signature, created_at
        )
        VALUES (?, ?, ?, ?, ?, ?, ?)
      `
    );
    for (const item of sanitizedOneTimePrekeys) {
      insertOneTimePrekey.run(req.auth.uid, deviceId, item.id, keyAlg, item.publicKey, item.signature, now);
    }
  })();
  if (prekeyChanged && req.auth.device_status === DEVICE_STATUS_TRUSTED) {
    broadcastDirectPeerKeysChanged(req.auth.uid, deviceId);
  }
  res.json({ ok: true, one_time_prekey_count: sanitizedOneTimePrekeys.length });
});

app.get("/api/device_identities", authMiddleware, (req, res) => {
  const rows = db
    .prepare(
      `
        SELECT dik.*, u.user_code, u.username, d.status, d.manufacturer, d.model,
               d.approved_at, d.revoked_at
        FROM device_identity_keys dik
        JOIN devices d ON d.user_id=dik.user_id AND d.device_id=dik.device_id
        JOIN users u ON u.id = dik.user_id
        WHERE d.status='trusted'
        ORDER BY u.username, dik.updated_at DESC
      `
    )
    .all();
  res.json({ items: rows.map(deviceIdentityToWire) });
});

app.get("/api/devices", authMiddleware, (req, res) => {
  const rows = db
    .prepare(
      `
        SELECT d.user_id, d.device_id, d.platform, d.device_name, d.manufacturer,
               d.model, d.status, d.created_at, d.approved_at, d.revoked_at,
               d.last_seen_at, COALESCE(dik.key_alg, '') AS key_alg,
               COALESCE(dik.public_key, '') AS public_key,
               COALESCE(dik.fingerprint, '') AS fingerprint,
               COALESCE(dik.updated_at, d.last_seen_at) AS updated_at,
               u.user_code, u.username
        FROM devices d
        JOIN users u ON u.id=d.user_id
        LEFT JOIN device_identity_keys dik
          ON dik.user_id=d.user_id AND dik.device_id=d.device_id
        WHERE d.user_id=? AND d.status!='revoked'
        ORDER BY CASE d.status WHEN 'pending' THEN 0 ELSE 1 END, d.last_seen_at DESC
      `
    )
    .all(req.auth.uid);
  res.json({ items: rows.map(deviceIdentityToWire) });
});

app.post("/api/devices/:deviceId/approve", authMiddleware, (req, res) => {
  const deviceId = normalizeDeviceId(req.params.deviceId);
  if (!isValidDeviceId(deviceId)) return res.status(400).json({ error: "bad_device_id" });
  if (deviceId === req.auth.device_id) return res.status(400).json({ error: "cannot_self_approve" });
  const approved = approveDevice(req.auth.uid, deviceId, req.auth.device_id, "");
  if (!approved) return res.status(404).json({ error: "device_not_found" });
  if (approved.error) return res.status(409).json({ error: approved.error });
  res.json({ ok: true, device_id: deviceId, status: approved.status });
});

app.delete("/api/devices/:deviceId", authMiddleware, (req, res) => {
  const deviceId = normalizeDeviceId(req.params.deviceId);
  if (!isValidDeviceId(deviceId)) return res.status(400).json({ error: "bad_device_id" });
  if (deviceId === req.auth.device_id) return res.status(400).json({ error: "cannot_remove_current_device" });
  const revoked = revokeDevice(req.auth.uid, deviceId);
  if (!revoked) return res.status(404).json({ error: "device_not_found" });
  res.json({ ok: true, device_id: deviceId });
});

app.get("/api/conversations/:id/direct_prekeys", authMiddleware, (req, res) => {
  const conversation = getConversationForUser(req.auth.uid, req.params.id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "direct") return res.status(400).json({ error: "not_direct_conversation" });
  const includeSelf = String(req.query.include_self || "") === "1";
  const rows = db
    .prepare(
      `
      SELECT u.id AS user_id, u.user_code, u.username,
             dik.device_id, dik.device_name, dik.key_alg AS identity_key_alg,
             dik.public_key AS identity_public_key, dik.fingerprint AS identity_fingerprint,
             dp.key_alg AS prekey_alg, dp.identity_ecdh_public, dp.identity_ecdh_signature,
             dp.signed_prekey_public, dp.signed_prekey_signature,
             dp.updated_at
      FROM conversation_members cm
      JOIN users u ON u.id = cm.user_id
      JOIN device_identity_keys dik ON dik.user_id = u.id
      JOIN direct_prekeys dp ON dp.user_id = u.id AND dp.device_id = dik.device_id
      JOIN devices d ON d.user_id=u.id AND d.device_id=dik.device_id AND d.status='trusted'
      WHERE cm.conversation_id = ?
        AND (cm.user_id != ? OR (? = 1 AND dik.device_id != ?))
      ORDER BY dp.updated_at DESC
      `
    )
    .all(conversation.id, req.auth.uid, includeSelf ? 1 : 0, req.auth.device_id);
  const claimOneTimePrekey = db.transaction((userId, deviceId) => {
    const row = db
      .prepare(
        `
          SELECT prekey_id, public_key, signature
          FROM direct_one_time_prekeys
          WHERE user_id=? AND device_id=? AND claimed_at=0
          ORDER BY created_at ASC
          LIMIT 1
        `
      )
      .get(userId, deviceId);
    if (!row) return null;
    db.prepare(
      `
        UPDATE direct_one_time_prekeys
        SET claimed_at=?, claimed_by_user_id=?, claimed_for_conversation_id=?
        WHERE user_id=? AND device_id=? AND prekey_id=? AND claimed_at=0
      `
    ).run(Date.now(), req.auth.uid, conversation.id, userId, deviceId, row.prekey_id);
    return row;
  });
  res.json({
    items: rows.map((row) => {
      const oneTimePrekey = claimOneTimePrekey(row.user_id, row.device_id);
      return {
        user_code: row.user_code || "",
        username: row.username || "",
        device_id: row.device_id || "",
        device_name: row.device_name || "",
        identity_key_alg: row.identity_key_alg || "",
        identity_public_key: row.identity_public_key || "",
        identity_fingerprint: row.identity_fingerprint || "",
        prekey_alg: row.prekey_alg || "",
        identity_ecdh_public: row.identity_ecdh_public || "",
        identity_ecdh_signature: row.identity_ecdh_signature || "",
        signed_prekey_public: row.signed_prekey_public || "",
        signed_prekey_signature: row.signed_prekey_signature || "",
        one_time_prekey_id: oneTimePrekey?.prekey_id || "",
        one_time_prekey_public: oneTimePrekey?.public_key || "",
        one_time_prekey_signature: oneTimePrekey?.signature || "",
        updated_at: Number(row.updated_at || 0),
      };
    }),
  });
});

app.get("/api/conversations/:id/group_sender_keys", authMiddleware, (req, res) => {
  const conversation = getConversationForUser(req.auth.uid, req.params.id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  const epoch = currentGroupKeyEpoch(conversation.id);
  const recipientDeviceId = normalizeDeviceId(req.query.device_id);
  if (recipientDeviceId !== req.auth.device_id) {
    return res.status(403).json({ error: "device_mismatch" });
  }
  const devices = db
    .prepare(
      `
      SELECT u.id AS user_id, u.user_code, u.username,
             dik.device_id, dik.device_name, dik.key_alg AS identity_key_alg,
             dik.public_key AS identity_public_key, dik.fingerprint AS identity_fingerprint,
             dp.key_alg AS prekey_alg, dp.identity_ecdh_public, dp.identity_ecdh_signature,
             dp.updated_at
      FROM conversation_members cm
      JOIN users u ON u.id = cm.user_id
      JOIN device_identity_keys dik ON dik.user_id = u.id
      JOIN direct_prekeys dp ON dp.user_id = u.id AND dp.device_id = dik.device_id
      JOIN devices d ON d.user_id=u.id AND d.device_id=dik.device_id AND d.status='trusted'
      WHERE cm.conversation_id=?
      ORDER BY u.username, dik.updated_at DESC
      `
    )
    .all(conversation.id);
  const rows = isValidDeviceId(recipientDeviceId)
    ? db
        .prepare(
          `
          SELECT gske.conversation_id, gske.sender_device_id, gske.recipient_device_id,
                 gske.epoch, gske.key_id, gske.wrapped_key, gske.updated_at,
                 sender.user_code AS sender_user_code, sender.username AS sender_username,
                 dik.device_name AS sender_device_name,
                 dik.public_key AS sender_identity_public_key,
                 dik.fingerprint AS sender_identity_fingerprint
          FROM group_sender_key_envelopes gske
          JOIN users sender ON sender.id = gske.sender_user_id
          LEFT JOIN device_identity_keys dik ON dik.user_id = gske.sender_user_id AND dik.device_id = gske.sender_device_id
          JOIN conversation_members cm ON cm.conversation_id = gske.conversation_id AND cm.user_id = gske.sender_user_id
          WHERE gske.conversation_id=? AND gske.epoch=? AND gske.recipient_user_id=? AND gske.recipient_device_id=?
          ORDER BY sender.username, gske.updated_at DESC
          `
        )
        .all(conversation.id, epoch, req.auth.uid, recipientDeviceId)
    : [];
  res.json({
    epoch,
    devices: devices.map((row) => ({
      user_code: row.user_code || "",
      username: row.username || "",
      device_id: row.device_id || "",
      device_name: row.device_name || "",
      identity_key_alg: row.identity_key_alg || "",
      identity_public_key: row.identity_public_key || "",
      identity_fingerprint: row.identity_fingerprint || "",
      prekey_alg: row.prekey_alg || "",
      identity_ecdh_public: row.identity_ecdh_public || "",
      identity_ecdh_signature: row.identity_ecdh_signature || "",
      updated_at: Number(row.updated_at || 0),
    })),
    items: rows.map((row) => ({
      conversation_id: Number(row.conversation_id || conversation.id),
      sender_user_code: row.sender_user_code || "",
      sender_username: row.sender_username || "",
      device_id: row.sender_device_id || "",
      sender_device_name: row.sender_device_name || "",
      sender_identity_public_key: row.sender_identity_public_key || "",
      sender_identity_fingerprint: row.sender_identity_fingerprint || "",
      recipient_device_id: row.recipient_device_id || "",
      epoch: Number(row.epoch || epoch),
      key_id: row.key_id || "",
      wrapped_key: row.wrapped_key || "",
      updated_at: Number(row.updated_at || 0),
    })),
  });
});

app.post("/api/conversations/:id/group_sender_key", authMiddleware, (req, res) => {
  const conversation = getConversationForUser(req.auth.uid, req.params.id);
  if (!conversation) return res.status(404).json({ error: "conversation_not_found" });
  if (conversation.kind !== "group") return res.status(400).json({ error: "not_group_conversation" });
  const epoch = Math.max(Number(req.body?.epoch || 0), 0);
  const currentEpoch = currentGroupKeyEpoch(conversation.id);
  if (epoch !== currentEpoch) return res.status(409).json({ error: "stale_group_epoch", epoch: currentEpoch });
  const deviceId = normalizeDeviceId(req.body?.device_id);
  const keyId = String(req.body?.key_id || "").trim().slice(0, 80);
  const envelopes = Array.isArray(req.body?.envelopes) ? req.body.envelopes.slice(0, 100) : [];
  if (!isValidDeviceId(deviceId) || !keyId || envelopes.length <= 0) {
    return res.status(400).json({ error: "bad_group_sender_key" });
  }
  if (deviceId !== req.auth.device_id) {
    return res.status(403).json({ error: "device_mismatch" });
  }
  const senderIdentity = db
    .prepare("SELECT 1 AS ok FROM device_identity_keys WHERE user_id=? AND device_id=?")
    .get(req.auth.uid, deviceId);
  if (!senderIdentity) return res.status(400).json({ error: "device_identity_required" });
  const now = Date.now();
  const insertEnvelope = db.prepare(
    `
      INSERT INTO group_sender_key_envelopes (
        conversation_id, sender_user_id, sender_device_id, recipient_user_id, recipient_device_id,
        epoch, key_id, wrapped_key, created_at, updated_at
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(conversation_id, sender_user_id, sender_device_id, recipient_user_id, recipient_device_id, epoch, key_id)
      DO UPDATE SET
        wrapped_key=excluded.wrapped_key,
        updated_at=excluded.updated_at
    `
  );
  let stored = 0;
  db.transaction(() => {
    for (const envelope of envelopes) {
      const recipientCode = normalizeUserCode(envelope?.recipient_user_code);
      const recipientDeviceId = normalizeDeviceId(envelope?.recipient_device_id);
      const wrappedKey = String(envelope?.wrapped_key || "").trim().slice(0, MAX_CIPHERTEXT_LEN);
      if (!recipientCode || !isValidDeviceId(recipientDeviceId) || wrappedKey.length < 20) continue;
      const recipient = db
        .prepare(
          `
          SELECT u.id
          FROM users u
          JOIN conversation_members cm ON cm.user_id = u.id AND cm.conversation_id = ?
          JOIN device_identity_keys dik ON dik.user_id = u.id AND dik.device_id = ?
          JOIN direct_prekeys dp ON dp.user_id = u.id AND dp.device_id = dik.device_id
          JOIN devices d ON d.user_id=u.id AND d.device_id=dik.device_id AND d.status='trusted'
          WHERE u.user_code = ?
          `
        )
        .get(conversation.id, recipientDeviceId, recipientCode);
      if (!recipient) continue;
      insertEnvelope.run(
        conversation.id,
        req.auth.uid,
        deviceId,
        recipient.id,
        recipientDeviceId,
        epoch,
        keyId,
        wrappedKey,
        now,
        now
      );
      stored += 1;
    }
  })();
  if (stored <= 0) return res.status(400).json({ error: "bad_group_sender_key" });
  res.json({ ok: true, epoch, stored });
});
}

module.exports = { registerPublicAuthRoutes, registerAuthDeviceRoutes };
