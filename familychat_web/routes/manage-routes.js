function registerManageRoutes(context) {
  const {
    logger,
    path,
    crypto,
    express,
    bcrypt,
    MANAGE_COOKIE_NAME,
    MANAGE_PUBLIC_DIR,
    RELEASE_CHANNEL_RELEASE,
    RELEASE_CHANNEL_BETA,
    MANAGE_LOGIN_MAX_FAILURES,
    MANAGE_TOTP_CHALLENGE_MAX_ATTEMPTS,
    DEVICE_STATUS_REVOKED,
    app,
    db,
    BLOG_ENABLED,
    blogRepository,
    blogService,
    blogUpload,
    getAnnouncementAccount,
    manageHostOnly,
    signManageToken,
    manageCookieOptions,
    manageAuthMiddleware,
    getManageLoginAttemptKey,
    getManageLoginAttempt,
    resetManageLoginAttempt,
    registerFailedManageLogin,
    generateManageTotpSecret,
    verifyTotpCode,
    buildManageTotpLabel,
    buildManageTotpUri,
    createManageTotpChallenge,
    getManageTotpChallenge,
    consumeManageTotpChallenge,
    registerFailedManageTotpChallenge,
    storage,
    manageApkUpload,
    approveDevice,
    revokeDevice,
    getReadStates,
    getUserById,
    getOnlineUsers,
    getStorageSummary,
    isValidUsername,
    normalizeDeviceId,
    isValidDeviceId,
    getPendingRegistrationRequests,
    getPendingDevicesForManage,
    getPendingAccountDeletionRequests,
    getRegisteredUsersForManage,
    finalizeApprovedRegistration,
    finalizeApprovedAccountDeletion,
    isValidReleaseVersion,
    extractReleaseVersion,
    normalizeReleaseChannel,
    inferReleaseChannel,
    getAppReleaseState,
    getAllAppReleaseStates,
    manageAdminToPublic,
    storeAppRelease,
    clearAttachmentMessages,
    clearAllHistory,
    broadcast,
    broadcastToUser,
  } = context;

function positiveId(value) {
  const parsed = Number.parseInt(String(value || ""), 10);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
}

app.use("/manage_static", manageHostOnly, express.static(MANAGE_PUBLIC_DIR, { extensions: ["html"] }));

app.post("/manage_api/login", manageHostOnly, (req, res) => {
  const username = typeof req.body?.username === "string" ? req.body.username.trim() : "";
  const password = typeof req.body?.password === "string" ? req.body.password : "";
  if (!username || !password) return res.status(400).json({ error: "bad_request" });
  const attemptKey = getManageLoginAttemptKey(req, username);
  const existingAttempt = getManageLoginAttempt(attemptKey);
  const retryAfterMs = Math.max(Number(existingAttempt?.cooldown_until || 0) - Date.now(), 0);
  if (retryAfterMs > 0) {
    return res.status(429).json({ error: "cooldown_active", retry_after_ms: retryAfterMs });
  }

  const admin = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE username=?"
    )
    .get(username);
  if (!admin || !bcrypt.compareSync(password, admin.password_hash)) {
    const failed = registerFailedManageLogin(attemptKey);
    if (failed.retry_after_ms > 0) {
      return res.status(429).json({
        error: "cooldown_active",
        retry_after_ms: failed.retry_after_ms,
      });
    }
    return res.status(401).json({
      error: "invalid_credentials",
      remaining_attempts: Math.max(MANAGE_LOGIN_MAX_FAILURES - failed.fail_count, 0),
    });
  }
  resetManageLoginAttempt(attemptKey);
  if (admin.totp_enabled && admin.totp_secret) {
    const challengeToken = createManageTotpChallenge(admin.id);
    return res.json({
      ok: true,
      requires_totp: true,
      challenge_token: challengeToken,
      username: admin.username,
    });
  }

  const nextSessionId = crypto.randomBytes(16).toString("hex");
  db.prepare("UPDATE manage_admins SET session_id=?, updated_at=? WHERE id=?").run(nextSessionId, Date.now(), admin.id);
  const updated = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE id=?"
    )
    .get(admin.id);

  res.cookie(MANAGE_COOKIE_NAME, signManageToken(updated), manageCookieOptions(req));
  res.json({ ok: true, username: updated.username, requires_totp: false });
});

app.post("/manage_api/login_totp", manageHostOnly, (req, res) => {
  const challengeToken = typeof req.body?.challenge_token === "string" ? req.body.challenge_token.trim() : "";
  const code = typeof req.body?.code === "string" ? req.body.code.trim() : "";
  if (!challengeToken || !code) return res.status(400).json({ error: "bad_request" });

  const challenge = getManageTotpChallenge(challengeToken);
  if (!challenge) return res.status(401).json({ error: "invalid_or_expired_challenge" });
  if (Number(challenge.expires_at || 0) <= Date.now()) {
    consumeManageTotpChallenge(challengeToken);
    return res.status(401).json({ error: "invalid_or_expired_challenge" });
  }

  const admin = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE id=?"
    )
    .get(challenge.admin_id);
  if (!admin || !admin.totp_enabled || !admin.totp_secret) {
    consumeManageTotpChallenge(challengeToken);
    return res.status(401).json({ error: "totp_not_enabled" });
  }

  if (!verifyTotpCode(admin.totp_secret, code)) {
    const failed = registerFailedManageTotpChallenge(challengeToken);
    return res.status(failed.locked ? 429 : 401).json({
      error: failed.locked ? "totp_challenge_locked" : "invalid_totp_code",
      remaining_attempts: Math.max(MANAGE_TOTP_CHALLENGE_MAX_ATTEMPTS - failed.attempts, 0),
    });
  }

  consumeManageTotpChallenge(challengeToken);
  const nextSessionId = crypto.randomBytes(16).toString("hex");
  db.prepare("UPDATE manage_admins SET session_id=?, updated_at=? WHERE id=?").run(nextSessionId, Date.now(), admin.id);
  const updated = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE id=?"
    )
    .get(admin.id);
  res.cookie(MANAGE_COOKIE_NAME, signManageToken(updated), manageCookieOptions(req));
  res.json({ ok: true, username: updated.username });
});

app.post("/manage_api/logout", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const nextSessionId = crypto.randomBytes(16).toString("hex");
  db.prepare("UPDATE manage_admins SET session_id=?, updated_at=? WHERE id=?").run(nextSessionId, Date.now(), req.manageAdmin.id);
  res.clearCookie(MANAGE_COOKIE_NAME, { path: "/" });
  res.json({ ok: true });
});

app.get("/manage_api/me", manageHostOnly, manageAuthMiddleware, (req, res) => {
  res.json(manageAdminToPublic(req.manageAdmin));
});

app.get("/manage_api/totp_status", manageHostOnly, manageAuthMiddleware, (req, res) => {
  res.json(manageAdminToPublic(req.manageAdmin));
});

app.post("/manage_api/totp/setup", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const pendingSecret = generateManageTotpSecret();
  db.prepare("UPDATE manage_admins SET totp_pending_secret=?, updated_at=? WHERE id=?").run(
    pendingSecret,
    Date.now(),
    req.manageAdmin.id
  );
  res.json({
    ok: true,
    secret: pendingSecret,
    otpauth_url: buildManageTotpUri(req.manageAdmin.username, pendingSecret),
    label: buildManageTotpLabel(req.manageAdmin.username),
  });
});

app.post("/manage_api/totp/enable", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const code = typeof req.body?.code === "string" ? req.body.code.trim() : "";
  if (!code) return res.status(400).json({ error: "bad_request" });

  const admin = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE id=?"
    )
    .get(req.manageAdmin.id);
  const pendingSecret = String(admin?.totp_pending_secret || "");
  if (!pendingSecret) return res.status(400).json({ error: "totp_setup_not_started" });
  if (!verifyTotpCode(pendingSecret, code)) return res.status(401).json({ error: "invalid_totp_code" });

  db.prepare(
    "UPDATE manage_admins SET totp_secret=?, totp_pending_secret='', totp_enabled=1, updated_at=? WHERE id=?"
  ).run(pendingSecret, Date.now(), req.manageAdmin.id);
  const updated = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE id=?"
    )
    .get(req.manageAdmin.id);
  res.json({ ok: true, admin: manageAdminToPublic(updated) });
});

app.post("/manage_api/totp/disable", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const currentPassword = typeof req.body?.current_password === "string" ? req.body.current_password : "";
  const code = typeof req.body?.code === "string" ? req.body.code.trim() : "";
  if (!currentPassword || !code) return res.status(400).json({ error: "bad_request" });
  if (!req.manageAdmin.totp_enabled || !req.manageAdmin.totp_secret) {
    return res.status(400).json({ error: "totp_not_enabled" });
  }
  if (!bcrypt.compareSync(currentPassword, req.manageAdmin.password_hash)) {
    return res.status(401).json({ error: "invalid_current_password" });
  }
  if (!verifyTotpCode(req.manageAdmin.totp_secret, code)) {
    return res.status(401).json({ error: "invalid_totp_code" });
  }

  db.prepare(
    "UPDATE manage_admins SET totp_secret='', totp_pending_secret='', totp_enabled=0, updated_at=? WHERE id=?"
  ).run(Date.now(), req.manageAdmin.id);
  const updated = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE id=?"
    )
    .get(req.manageAdmin.id);
  res.json({ ok: true, admin: manageAdminToPublic(updated) });
});

app.get("/manage_api/overview", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const storage = getStorageSummary();
  const onlineUsers = getOnlineUsers();
  const registeredUsers = getRegisteredUsersForManage();
  const pendingRequests = getPendingRegistrationRequests();
  const pendingDevices = getPendingDevicesForManage();
  const pendingAccountDeletionRequests = getPendingAccountDeletionRequests();
  const messageStats = db
    .prepare(
      `
      SELECT
        COUNT(*) AS total_messages,
        SUM(CASE WHEN kind IN ('photo', 'image', 'audio', 'file') THEN 1 ELSE 0 END) AS attachment_messages
      FROM messages
    `
    )
    .get();

  res.json({
    admin: manageAdminToPublic(req.manageAdmin),
    storage,
    online_users: onlineUsers,
    online_count: onlineUsers.length,
    registered_users: registeredUsers,
    pending_registration_requests: pendingRequests,
    pending_devices: pendingDevices,
    pending_account_deletion_requests: pendingAccountDeletionRequests,
    total_messages: Number(messageStats?.total_messages || 0),
    attachment_messages: Number(messageStats?.attachment_messages || 0),
    app_release: getAppReleaseState(RELEASE_CHANNEL_RELEASE),
    app_beta_release: getAppReleaseState(RELEASE_CHANNEL_BETA),
    app_prerelease: getAppReleaseState(RELEASE_CHANNEL_BETA),
    blog_enabled: !!BLOG_ENABLED,
    blog_stats: BLOG_ENABLED && blogRepository ? blogRepository.getStats() : {
      post_count: 0,
      comment_count: 0,
      media_count: 0,
      media_bytes: 0,
    },
  });
});

app.get("/manage_api/blog/posts", manageHostOnly, manageAuthMiddleware, (req, res) => {
  if (!BLOG_ENABLED || !blogRepository || !blogService) {
    return res.status(503).json({ error: "blog_disabled" });
  }
  const limit = blogService.clampLimit(req.query.limit, 20, 50);
  const cursor = blogService.parseCursor(req.query.cursor);
  const rows = blogRepository.listPosts(0, cursor, limit);
  res.setHeader("Cache-Control", "private, no-store");
  res.json({
    items: rows.map((post) => blogService.postToWire(post, "/manage_api/blog/media")),
    next_cursor: rows.length === limit ? String(rows[rows.length - 1].id) : "",
  });
});

app.get("/manage_api/blog/posts/:id/comments", manageHostOnly, manageAuthMiddleware, (req, res) => {
  if (!BLOG_ENABLED || !blogRepository || !blogService) {
    return res.status(503).json({ error: "blog_disabled" });
  }
  const postId = positiveId(req.params.id);
  if (!postId) return res.status(400).json({ error: "bad_blog_post_id" });
  if (!blogRepository.getPostOwner(postId)) return res.status(404).json({ error: "blog_post_not_found" });
  const limit = blogService.clampLimit(req.query.limit, 100, 200);
  const cursor = blogService.parseCursor(req.query.cursor);
  const rows = blogRepository.listComments(postId, cursor, limit);
  res.setHeader("Cache-Control", "private, no-store");
  res.json({
    items: rows.map(blogService.commentToWire),
    next_cursor: rows.length === limit ? String(rows[rows.length - 1].id) : "",
  });
});

app.get("/manage_api/blog/media/:id", manageHostOnly, manageAuthMiddleware, async (req, res) => {
  if (!BLOG_ENABLED || !blogRepository || !blogService) {
    return res.status(503).json({ error: "blog_disabled" });
  }
  const mediaId = positiveId(req.params.id);
  if (!mediaId) return res.status(400).json({ error: "bad_blog_media_id" });
  const media = blogRepository.getMedia(mediaId);
  if (!media) return res.status(404).json({ error: "blog_media_not_found" });
  res.setHeader("Cache-Control", "private, no-store");
  try {
    await blogService.sendMedia(req, res, media);
  } catch (error) {
    logger.warn("manage_blog_media_read_failed", { mediaId, error: error?.name || "Error" });
    if (res.headersSent) return res.destroy(error);
    return res.status(String(error?.message) === "blog_media_missing" ? 404 : 500).json({
      error: String(error?.message || "blog_media_read_failed"),
    });
  }
});

app.delete("/manage_api/blog/posts/:id", manageHostOnly, manageAuthMiddleware, (req, res) => {
  if (!BLOG_ENABLED || !blogRepository || !blogService) {
    return res.status(503).json({ error: "blog_disabled" });
  }
  const postId = positiveId(req.params.id);
  if (!postId) return res.status(400).json({ error: "bad_blog_post_id" });
  if (!blogRepository.getPostOwner(postId)) return res.status(404).json({ error: "blog_post_not_found" });
  const files = blogRepository.getPostMediaFiles(postId);
  blogRepository.deletePost(postId);
  blogService.deleteFiles(files);
  broadcast({ type: "blog_post_deleted", post_id: postId });
  logger.info("manage_blog_post_deleted", { postId, adminId: req.manageAdmin.id });
  res.json({ ok: true });
});

app.delete("/manage_api/blog/comments/:id", manageHostOnly, manageAuthMiddleware, (req, res) => {
  if (!BLOG_ENABLED || !blogRepository || !blogService) {
    return res.status(503).json({ error: "blog_disabled" });
  }
  const commentId = positiveId(req.params.id);
  if (!commentId) return res.status(400).json({ error: "bad_blog_comment_id" });
  const comment = blogRepository.getCommentOwner(commentId);
  if (!comment) return res.status(404).json({ error: "blog_comment_not_found" });
  blogRepository.deleteComment(commentId);
  const post = blogRepository.getPost(comment.post_id, 0);
  const commentCount = Number(post?.comment_count || 0);
  broadcast({
    type: "blog_comment_deleted",
    comment_id: commentId,
    post_id: comment.post_id,
    comment_count: commentCount,
  });
  logger.info("manage_blog_comment_deleted", { commentId, postId: comment.post_id, adminId: req.manageAdmin.id });
  res.json({ ok: true, comment_count: commentCount });
});

app.post("/manage_api/registrations/:id/approve", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const requestId = Math.max(Number(req.params.id || 0), 0);
  if (!requestId) return res.status(400).json({ error: "bad_request_id" });
  const approved = finalizeApprovedRegistration(requestId, req.manageAdmin.username);
  if (!approved) return res.status(404).json({ error: "request_not_found" });
  if (approved.error === "username_taken") return res.status(409).json({ error: "username_taken" });
  res.json({ ok: true, user: approved });
});

app.post("/manage_api/registrations/:id/reject", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const requestId = Math.max(Number(req.params.id || 0), 0);
  const note = typeof req.body?.note === "string" ? req.body.note.trim().slice(0, 120) : "";
  if (!requestId) return res.status(400).json({ error: "bad_request_id" });
  const row = db.prepare("SELECT status FROM registration_requests WHERE id=?").get(requestId);
  if (!row || row.status !== "pending") return res.status(404).json({ error: "request_not_found" });
  db.prepare(
    "UPDATE registration_requests SET status='rejected', review_note=?, reviewed_at=?, reviewed_by=? WHERE id=?"
  ).run(note || "rejected", Date.now(), req.manageAdmin.username, requestId);
  res.json({ ok: true });
});

app.post("/manage_api/devices/:userId/:deviceId/approve", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const userId = Math.max(Number(req.params.userId || 0), 0);
  const deviceId = normalizeDeviceId(req.params.deviceId);
  if (!userId || !isValidDeviceId(deviceId)) return res.status(400).json({ error: "bad_device_id" });
  const approved = approveDevice(userId, deviceId, "", req.manageAdmin.username);
  if (!approved) return res.status(404).json({ error: "device_not_found" });
  if (approved.error) return res.status(409).json({ error: approved.error });
  res.json({ ok: true, device_id: deviceId, status: approved.status });
});

app.post("/manage_api/devices/:userId/:deviceId/reject", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const userId = Math.max(Number(req.params.userId || 0), 0);
  const deviceId = normalizeDeviceId(req.params.deviceId);
  if (!userId || !isValidDeviceId(deviceId)) return res.status(400).json({ error: "bad_device_id" });
  const revoked = revokeDevice(userId, deviceId, "device_rejected");
  if (!revoked) return res.status(404).json({ error: "device_not_found" });
  res.json({ ok: true, device_id: deviceId, status: DEVICE_STATUS_REVOKED });
});

app.post("/manage_api/account_deletions/:id/approve", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const requestId = Math.max(Number(req.params.id || 0), 0);
  if (!requestId) return res.status(400).json({ error: "bad_request_id" });
  const request = db.prepare("SELECT * FROM account_deletion_requests WHERE id=? AND status='pending'").get(requestId);
  if (!request) return res.status(404).json({ error: "request_not_found" });
  let deleted;
  try {
    deleted = finalizeApprovedAccountDeletion(requestId, req.manageAdmin.username);
  } catch (error) {
    logger.error("account_deletion_approve_failed", {
      requestId,
      userId: request.user_id,
      error: error?.name || "Error",
    });
    return res.status(500).json({ error: "account_deletion_failed" });
  }
  if (deleted?.error === "owner_groups_block_deletion") {
    return res.status(409).json({ error: deleted.error, groups: deleted.groups || [] });
  }
  if (!deleted) return res.status(404).json({ error: "request_not_found" });
  broadcastToUser(request.user_id, { type: "force_logout", reason: "account_deleted" });
  broadcast({ type: "user_deleted", user_code: deleted?.user_code || request.user_code_snapshot || "" });
  res.json({ ok: true, user: deleted });
});

app.post("/manage_api/account_deletions/:id/reject", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const requestId = Math.max(Number(req.params.id || 0), 0);
  const note = typeof req.body?.note === "string" ? req.body.note.trim().slice(0, 120) : "";
  if (!requestId) return res.status(400).json({ error: "bad_request_id" });
  const row = db.prepare("SELECT status FROM account_deletion_requests WHERE id=?").get(requestId);
  if (!row || row.status !== "pending") return res.status(404).json({ error: "request_not_found" });
  db.prepare(
    "UPDATE account_deletion_requests SET status='rejected', review_note=?, reviewed_at=?, reviewed_by=? WHERE id=?"
  ).run(note || "rejected", Date.now(), req.manageAdmin.username, requestId);
  res.json({ ok: true });
});

app.post("/manage_api/users/:id/username", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const userId = Math.max(Number(req.params.id || 0), 0);
  const username = typeof req.body?.username === "string" ? req.body.username.trim() : "";
  if (!userId || !isValidUsername(username)) return res.status(400).json({ error: "invalid_username" });
  const existing = db.prepare("SELECT id FROM users WHERE username=?").get(username);
  if (existing && existing.id !== userId) return res.status(409).json({ error: "username_taken" });
  const user = getUserById(userId);
  if (!user) return res.status(404).json({ error: "user_not_found" });

  db.prepare("UPDATE users SET username=? WHERE id=?").run(username, userId);
  const updated = getUserById(userId);
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
  res.json({ ok: true, user: updated });
});

app.post("/manage_api/app_release", manageHostOnly, manageAuthMiddleware, manageApkUpload.single("apk"), (req, res) => {
  if (!req.file) return res.status(400).json({ error: "no_file" });
  const requestedVersion = typeof req.body?.version === "string" ? req.body.version.trim() : "";
  const requestedChannel = typeof req.body?.channel === "string" ? req.body.channel.trim() : "";
  const version = extractReleaseVersion(requestedVersion) || extractReleaseVersion(req.file.originalname);
  if (!isValidReleaseVersion(version)) {
    return res.status(400).json({ error: "invalid_version" });
  }
  const ext = path.extname(req.file.originalname || "").toLowerCase();
  if (ext !== ".apk") {
    return res.status(400).json({ error: "apk_only" });
  }

  const channel = normalizeReleaseChannel(requestedChannel, inferReleaseChannel(req.file.originalname));
  const release = storeAppRelease(channel, version, req.file.originalname || "", req.file.buffer);
  res.json({
    ok: true,
    channel,
    app_release: release,
    app_releases: getAllAppReleaseStates(),
  });
});

app.post("/manage_api/clear_files", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const result = clearAttachmentMessages();
  broadcast({ type: "attachments_cleared", ts: Date.now(), by: req.manageAdmin.username });
  res.json({ ok: true, ...result });
});

app.post("/manage_api/clear_history", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const result = clearAllHistory();
  broadcast({ type: "history_deleted", ts: Date.now(), by: req.manageAdmin.username });
  broadcast({ type: "read_snapshot", items: getReadStates() });
  res.json({ ok: true, ...result });
});

// ---------------------------------------------------------------------------
// Update announcements.
//
// These publish to the blog as the "Update Announcement" account, which is not
// a person and cannot sign in. Two rules hold the feature in shape:
//
//   1. Publishing is only possible AS the announcement account. The admin
//      cannot choose an author, so nothing here can put words in a family
//      member's mouth.
//   2. Editing is only possible ON an announcement. requireOwnAnnouncement
//      refuses any post the announcement account did not write - without it,
//      an admin could silently rewrite a family member's post, which is a
//      different and much worse power than the one being asked for.
// ---------------------------------------------------------------------------
function announcementAuthorOr503(res) {
  if (!BLOG_ENABLED || !blogRepository || !blogService) {
    res.status(503).json({ error: "blog_disabled" });
    return null;
  }
  const author = getAnnouncementAccount();
  if (!author) {
    res.status(500).json({ error: "announcement_account_missing" });
    return null;
  }
  return author;
}

function requireOwnAnnouncement(postId, authorId, res) {
  const owner = blogRepository.getPostOwner(postId);
  if (!owner) {
    res.status(404).json({ error: "blog_post_not_found" });
    return false;
  }
  if (Number(owner.author_user_id) !== Number(authorId)) {
    res.status(403).json({ error: "not_an_announcement" });
    return false;
  }
  return true;
}

app.post(
  "/manage_api/blog/announcements",
  manageHostOnly,
  manageAuthMiddleware,
  blogUpload.array("media", 9),
  (req, res) => {
    const author = announcementAuthorOr503(res);
    if (!author) return;
    const body = typeof req.body?.body === "string" ? req.body.body.trim() : "";
    const files = Array.isArray(req.files) ? req.files : [];
    if (!body && !files.length) {
      blogService.deleteFiles(files.map((file) => ({ storage_name: file.filename })));
      return res.status(400).json({ error: "empty_announcement" });
    }
    if (body.length > 5000) {
      blogService.deleteFiles(files.map((file) => ({ storage_name: file.filename })));
      return res.status(400).json({ error: "announcement_too_long" });
    }
    try {
      const media = blogService.encryptUploadedMedia(
        blogService.normalizeMedia(files, req.body?.media_metadata)
      );
      // createPost returns the id, not a row - the wire shape needs the joined
      // author columns, so it has to be read back.
      const postId = blogRepository.createPost(author.id, body, media);
      const item = blogService.postToWire(blogRepository.getPost(postId, 0));
      broadcast({ type: "blog_post_created", item });
      logger.info("manage_announcement_published", {
        postId,
        adminId: req.manageAdmin.id,
        mediaCount: media.length,
      });
      res.status(201).json({ ok: true, item });
    } catch (error) {
      blogService.deleteFiles(files.map((file) => ({ storage_name: file.filename })));
      logger.warn("manage_announcement_failed", { detail: error.message });
      res.status(500).json({ error: "announcement_failed" });
    }
  }
);

app.put("/manage_api/blog/announcements/:id", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const author = announcementAuthorOr503(res);
  if (!author) return;
  const postId = positiveId(req.params.id);
  if (!postId) return res.status(400).json({ error: "bad_blog_post_id" });
  if (!requireOwnAnnouncement(postId, author.id, res)) return;
  const body = typeof req.body?.body === "string" ? req.body.body.trim() : "";
  if (!body) return res.status(400).json({ error: "empty_announcement" });
  if (body.length > 5000) return res.status(400).json({ error: "announcement_too_long" });
  blogRepository.updatePostBody(postId, body);
  broadcast({ type: "blog_post_updated", post_id: postId });
  logger.info("manage_announcement_edited", { postId, adminId: req.manageAdmin.id });
  res.json({ ok: true, item: blogService.postToWire(blogRepository.getPost(postId, 0)) });
});

app.post("/manage_api/clear_blog", manageHostOnly, manageAuthMiddleware, (req, res) => {
  if (!BLOG_ENABLED || !blogRepository || !blogService) {
    return res.status(503).json({ error: "blog_disabled" });
  }
  const { files, deletedPosts, deletedComments } = blogRepository.deleteAllPosts();
  blogService.deleteFiles(files);
  broadcast({ type: "blog_cleared", ts: Date.now(), by: req.manageAdmin.username });
  logger.info("manage_blog_cleared", {
    adminId: req.manageAdmin.id,
    deletedPosts,
    deletedComments,
    deletedFiles: files.length,
  });
  res.json({ ok: true, deletedPosts, deletedComments, deletedFiles: files.length });
});

app.post("/manage_api/change_password", manageHostOnly, manageAuthMiddleware, (req, res) => {
  const currentPassword = typeof req.body?.current_password === "string" ? req.body.current_password : "";
  const newPassword = typeof req.body?.new_password === "string" ? req.body.new_password : "";
  if (!bcrypt.compareSync(currentPassword, req.manageAdmin.password_hash)) {
    return res.status(401).json({ error: "invalid_current_password" });
  }
  if (newPassword.length < 8) return res.status(400).json({ error: "password_too_short" });

  const nextSessionId = crypto.randomBytes(16).toString("hex");
  const passwordHash = bcrypt.hashSync(newPassword, 12);
  db.prepare("UPDATE manage_admins SET password_hash=?, session_id=?, updated_at=? WHERE id=?").run(
    passwordHash,
    nextSessionId,
    Date.now(),
    req.manageAdmin.id
  );
  const updated = db
    .prepare("SELECT id, username, password_hash, session_id, updated_at FROM manage_admins WHERE id=?")
    .get(req.manageAdmin.id);
  res.cookie(MANAGE_COOKIE_NAME, signManageToken(updated), manageCookieOptions(req));
  res.json({ ok: true });
});


}

module.exports = { registerManageRoutes };
