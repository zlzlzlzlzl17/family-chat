const path = require("path");
const fs = require("fs");
const http = require("http");
const crypto = require("crypto");
const express = require("express");
const cookieParser = require("cookie-parser");
const WebSocket = require("ws");
const Database = require("better-sqlite3");
const bcrypt = require("bcrypt");
const jwt = require("jsonwebtoken");
const multer = require("multer");
const webpush = require("web-push");
const { loadRuntimeConfig } = require("./lib/runtime-config");
const { configureDatabase, reportForeignKeyViolations } = require("./lib/database");
const { initializeSchema } = require("./database/initialize-schema");
const { createLogger } = require("./lib/structured-logger");
const { createFcmTokenRepository } = require("./repositories/fcm-token-repository");
const { createConversationRepository } = require("./repositories/conversation-repository");
const { createBlogRepository } = require("./repositories/blog-repository");
const { createFcmService } = require("./services/fcm-service");
const { registerSystemRoutes } = require("./routes/system-routes");
const { registerManageRoutes } = require("./routes/manage-routes");
const { registerPublicAuthRoutes, registerAuthDeviceRoutes } = require("./routes/auth-device-routes");
const { registerConversationRoutes } = require("./routes/conversation-routes");
const { registerRelationshipRoutes } = require("./routes/relationship-routes");
const { registerMessageAttachmentRoutes } = require("./routes/message-attachment-routes");
const { registerBlogRoutes } = require("./routes/blog-routes");
const { createSocketHub } = require("./websocket/socket-hub");
const { registerSocketEvents } = require("./websocket/register-socket-events");
const { createMaintenanceService } = require("./services/maintenance-service");
const { createBootstrapService } = require("./services/bootstrap-service");
const { createAuthSessionService } = require("./services/auth-session-service");
const { createReleaseService } = require("./services/release-service");
const { createHistoryService } = require("./services/history-service");
const { createDeviceAccountService } = require("./services/device-account-service");
const { createMessageService } = require("./services/message-service");
const { createDeviceSecurityService } = require("./services/device-security-service");
const { createGroupService } = require("./services/group-service");
const { createCallService } = require("./services/call-service");
const { createConversationSummaryService } = require("./services/conversation-summary-service");
const { createBlogService } = require("./services/blog-service");
const { createBlogAtRestService } = require("./services/blog-at-rest-service");

const RUNTIME = loadRuntimeConfig();
const serverLogger = createLogger("server");
const websocketLogger = createLogger("websocket");
const fcmLogger = createLogger("fcm");
const maintenanceLogger = createLogger("maintenance");
const blogLogger = createLogger("blog");
const PORT = process.env.PORT || 3000;
const JWT_SECRET = RUNTIME.jwtSecret;
const DB_PATH = process.env.DB_PATH || path.join(__dirname, "data", "chat.sqlite");
const UPLOAD_DIR = process.env.UPLOAD_DIR || path.join(__dirname, "uploads");
const APP_RELEASE_DIR = process.env.APP_RELEASE_DIR || path.join(__dirname, "app_release");
const BLOG_UPLOAD_DIR = process.env.BLOG_UPLOAD_DIR || path.join(__dirname, "blog_uploads");
const BLOG_AT_REST_KEY = process.env.BLOG_AT_REST_KEY || "";
const BLOG_ENABLED = process.env.BLOG_ENABLED === undefined
  ? !!BLOG_AT_REST_KEY
  : String(process.env.BLOG_ENABLED).toLowerCase() !== "false";
const MANAGE_HOST = (process.env.MANAGE_HOST || "manage.example.com").toLowerCase();
const MANAGE_COOKIE_NAME = "family_manage_token";
const APP_CLIENT_HEADER = "x-familychat-client";
const APP_CLIENT_VALUES = new Set(["android-app", "windows-app"]);
const MOBILE_CLIENT_VALUE = "android-app";
const DESKTOP_CLIENT_VALUE = "windows-app";
const MANAGE_PUBLIC_DIR = path.join(__dirname, "manage_public");
const RELEASE_CHANNEL_RELEASE = "release";
const RELEASE_CHANNEL_BETA = "beta";
const RELEASE_CHANNEL_VALUES = new Set([RELEASE_CHANNEL_RELEASE, RELEASE_CHANNEL_BETA]);
const RELEASE_CHANNEL_ALIASES = new Map([
  ["stable", RELEASE_CHANNEL_RELEASE],
  ["official", RELEASE_CHANNEL_RELEASE],
  ["release", RELEASE_CHANNEL_RELEASE],
  ["pre", RELEASE_CHANNEL_BETA],
  ["preview", RELEASE_CHANNEL_BETA],
  ["prerelease", RELEASE_CHANNEL_BETA],
  ["test", RELEASE_CHANNEL_BETA],
  ["beta", RELEASE_CHANNEL_BETA],
]);

function splitEnvList(value) {
  return String(value || "")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

const VAPID_PUBLIC_KEY = process.env.VAPID_PUBLIC_KEY || "";
const VAPID_PRIVATE_KEY = process.env.VAPID_PRIVATE_KEY || "";
const VAPID_SUBJECT = process.env.VAPID_SUBJECT || "mailto:admin@example.com";
const FCM_PROJECT_ID = process.env.FCM_PROJECT_ID || "";
const FCM_CLIENT_EMAIL = process.env.FCM_CLIENT_EMAIL || "";
const FCM_PRIVATE_KEY = (process.env.FCM_PRIVATE_KEY || "").replace(/\\n/g, "\n");
const CALL_STUN_URLS = splitEnvList(process.env.CALL_STUN_URLS || "stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302");
const CALL_TURN_URLS = splitEnvList(process.env.CALL_TURN_URLS || "");
const CALL_TURN_USERNAME = process.env.CALL_TURN_USERNAME || "";
const CALL_TURN_CREDENTIAL = process.env.CALL_TURN_CREDENTIAL || "";

const RETENTION_MS = 3 * 24 * 60 * 60 * 1000;
const MAX_PLAINTEXT_LEN = 2000;
const MAX_CIPHERTEXT_LEN = 40000;
const MAX_UPLOAD_BYTES = 20 * 1024 * 1024;
const MAX_MANAGE_APK_BYTES = 256 * 1024 * 1024;
const MAX_BLOG_MEDIA_BYTES = 100 * 1024 * 1024;
const MAX_REPLY_LEN = 1200;
const MAX_MENTIONS = 12;
const MANAGE_LOGIN_MAX_FAILURES = 3;
const MANAGE_LOGIN_COOLDOWN_MS = 5 * 60 * 1000;
const APP_LOGIN_MAX_FAILURES = 5;
const APP_LOGIN_COOLDOWN_MS = 5 * 60 * 1000;
const GROUP_ADMIN_LIMIT = 3;
const GROUP_MESSAGE_TTL_OPTIONS_MS = new Set([
  0,
  8 * 60 * 60 * 1000,
  24 * 60 * 60 * 1000,
  72 * 60 * 60 * 1000,
]);
const MANAGE_TOTP_DIGITS = 6;
const MANAGE_TOTP_PERIOD = 30;
const MANAGE_TOTP_SECRET_BYTES = 20;
const MANAGE_TOTP_CHALLENGE_TTL_MS = 5 * 60 * 1000;
const MANAGE_TOTP_CHALLENGE_MAX_ATTEMPTS = 5;
const MAX_CALL_SIGNAL_TEXT_LEN = 64 * 1024;
const CALL_INVITE_TTL_MS = 30 * 1000;
const CALL_INVITE_FCM_FALLBACK_DELAY_MS = 2 * 1000;
const MOBILE_FOREGROUND_PRESENCE_TTL_MS = 70 * 1000;
const ACCESS_TOKEN_TTL_SECONDS = 15 * 60;
const REFRESH_TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const DEVICE_STATUS_PENDING = "pending";
const DEVICE_STATUS_TRUSTED = "trusted";
const DEVICE_STATUS_REVOKED = "revoked";
const SESSION_STATUS_PENDING = "pending";
const SESSION_STATUS_ACTIVE = "active";
const SESSION_STATUS_REVOKED = "revoked";

if (!fs.existsSync(UPLOAD_DIR)) fs.mkdirSync(UPLOAD_DIR, { recursive: true });
if (!fs.existsSync(APP_RELEASE_DIR)) fs.mkdirSync(APP_RELEASE_DIR, { recursive: true });
if (!fs.existsSync(BLOG_UPLOAD_DIR)) fs.mkdirSync(BLOG_UPLOAD_DIR, { recursive: true });

const app = express();
app.set("trust proxy", RUNTIME.trustProxyHops);
app.use(express.json({ limit: "900kb" }));
app.use(cookieParser());
app.use(
  "/uploads",
  express.static(UPLOAD_DIR, {
    setHeaders(res) {
      res.setHeader("Cache-Control", "public, max-age=3600");
    },
  })
);
app.use(
  "/downloads",
  express.static(APP_RELEASE_DIR, {
    setHeaders(res) {
      res.setHeader("Cache-Control", "no-store");
    },
  })
);

const db = new Database(DB_PATH);
configureDatabase(db);

initializeSchema(db, {
  releaseChannelRelease: RELEASE_CHANNEL_RELEASE,
  releaseChannelBeta: RELEASE_CHANNEL_BETA,
});

const bootstrapService = createBootstrapService({
  db,
  runtime: RUNTIME,
  bcrypt,
  crypto,
  logger: serverLogger,
});
const { generateUniqueUserCode, generateUniqueGroupCode, ensureGroupCodes, ensureGroupAdmins } = bootstrapService;

function getRequestHost(req) {
  const forwarded = String(req.headers["x-forwarded-host"] || req.headers.host || "")
    .split(",")[0]
    .trim();
  return forwarded.split(":")[0].toLowerCase();
}

function getClientIp(req) {
  return String(req.ip || req.headers["x-forwarded-for"] || req.socket?.remoteAddress || "")
    .split(",")[0]
    .trim();
}

function isManageHost(req) {
  return getRequestHost(req) === MANAGE_HOST;
}

function isAppClient(req) {
  return APP_CLIENT_VALUES.has(String(req.headers[APP_CLIENT_HEADER] || "").toLowerCase());
}

function getClientType(req) {
  const raw = String(req.headers?.[APP_CLIENT_HEADER] || "").toLowerCase();
  return raw === DESKTOP_CLIENT_VALUE ? "desktop" : "mobile";
}

function normalizePresenceState(value) {
  return value === "background" ? "background" : "foreground";
}

function setSocketPresence(ws, state) {
  ws.presence_state = normalizePresenceState(state);
  ws.presence_updated_at = Date.now();
  if (ws.auth?.uid && ws.auth?.device_id && ws.auth?.sid) {
    db.prepare(
      `
        INSERT INTO device_presence (
          user_id, device_id, session_id, websocket_connected, app_state, updated_at
        )
        VALUES (?, ?, ?, 1, ?, ?)
        ON CONFLICT(user_id, device_id, session_id) DO UPDATE SET
          websocket_connected=1,
          app_state=excluded.app_state,
          updated_at=excluded.updated_at
      `
    ).run(
      ws.auth.uid,
      ws.auth.device_id,
      ws.auth.sid,
      ws.presence_state,
      ws.presence_updated_at
    );
  }
}

function isSocketOnline(ws, now = Date.now()) {
  if (ws.readyState !== WebSocket.OPEN || !ws.auth) return false;
  if ((ws.auth.client_type || "mobile") === "desktop") return true;
  if (normalizePresenceState(ws.presence_state) !== "foreground") return false;
  return now - Number(ws.presence_updated_at || 0) <= MOBILE_FOREGROUND_PRESENCE_TTL_MS;
}

function manageHostOnly(req, res, next) {
  if (!isManageHost(req)) return res.status(404).send("Not found");
  next();
}

function appClientOnly(req, res, next) {
  if (!isAppClient(req)) return res.status(403).json({ error: "app_only" });
  next();
}

function ensureReadState(userId) {
  db.prepare(
    `
    INSERT INTO read_states (user_id, last_read_message_id, updated_at)
    VALUES (?, 0, ?)
    ON CONFLICT(user_id) DO NOTHING
  `
  ).run(userId, Date.now());
}

function ensureFamilyConversation() {
  let row = db.prepare("SELECT id FROM conversations WHERE slug='familychat'").get();
  let created = false;
  if (!row) {
    const result = db
      .prepare(
        `
        INSERT INTO conversations (kind, slug, group_code, title, avatar_url, created_at)
        VALUES ('group', 'familychat', ?, 'Family Chat', '', ?)
      `
      )
      .run(generateUniqueGroupCode(), Date.now());
    row = { id: Number(result.lastInsertRowid) };
    created = true;
  }

  const conversationId = row.id;
  if (created) {
    for (const user of db.prepare("SELECT id, is_admin FROM users").all()) {
      db.prepare(
        `
        INSERT INTO conversation_members (conversation_id, user_id, role, joined_at)
        VALUES (?, ?, ?, ?)
        ON CONFLICT(conversation_id, user_id) DO NOTHING
      `
      ).run(conversationId, user.id, user.is_admin ? "admin" : "member", Date.now());
      ensureConversationReadState(conversationId, user.id);
    }
  }

  db.prepare("UPDATE messages SET conversation_id=? WHERE conversation_id=0").run(conversationId);
  return conversationId;
}

function ensureConversationReadState(conversationId, userId) {
  db.prepare(
    `
    INSERT INTO conversation_read_states (conversation_id, user_id, last_read_message_id, updated_at)
    VALUES (?, ?, 0, ?)
    ON CONFLICT(conversation_id, user_id) DO NOTHING
  `
  ).run(conversationId, userId, Date.now());
}

function currentGroupKeyEpoch(conversationId) {
  const now = Date.now();
  db.prepare(
    "INSERT INTO group_key_epochs (conversation_id, epoch, updated_at) VALUES (?, 1, ?) ON CONFLICT(conversation_id) DO NOTHING"
  ).run(conversationId, now);
  const row = db.prepare("SELECT epoch FROM group_key_epochs WHERE conversation_id=?").get(conversationId);
  return Math.max(Number(row?.epoch || 1), 1);
}

function rotateGroupKeyEpoch(conversationId) {
  const group = db.prepare("SELECT id FROM conversations WHERE id=? AND kind='group'").get(conversationId);
  if (!group) return currentGroupKeyEpoch(conversationId);
  const now = Date.now();
  currentGroupKeyEpoch(conversationId);
  db.prepare("UPDATE group_key_epochs SET epoch=epoch+1, updated_at=? WHERE conversation_id=?").run(now, conversationId);
  return currentGroupKeyEpoch(conversationId);
}

function rotateAndBroadcastGroupKeyEpoch(conversationId) {
  const epoch = rotateGroupKeyEpoch(conversationId);
  broadcastToConversation(conversationId, { type: "group_key_epoch_rotated", conversation_id: conversationId, epoch });
  return epoch;
}

function getGroupConversationsForUser(userId) {
  return db
    .prepare(
      `
      SELECT c.id, c.title
      FROM conversations c
      JOIN conversation_members cm ON cm.conversation_id = c.id
      WHERE c.kind='group' AND cm.user_id=?
      ORDER BY c.id
      `
    )
    .all(userId);
}

function rotateGroupKeyEpochsForUser(userId) {
  const rows = getGroupConversationsForUser(userId);
  for (const row of rows) {
    rotateAndBroadcastGroupKeyEpoch(row.id);
  }
}

function getFamilyConversationId() {
  return db.prepare("SELECT id FROM conversations WHERE slug='familychat'").get().id;
}

function ensureDirectConversation(userAId, userBId) {
  const ids = [Number(userAId), Number(userBId)].sort((a, b) => a - b);
  const slug = `direct:${ids[0]}:${ids[1]}`;
  let row = db.prepare("SELECT id FROM conversations WHERE slug=?").get(slug);
  if (!row) {
    const users = db
      .prepare("SELECT id, username FROM users WHERE id IN (?, ?) ORDER BY username")
      .all(ids[0], ids[1]);
    const title = users.map((user) => user.username).join(" & ");
    const result = db
      .prepare(
        `
        INSERT INTO conversations (kind, slug, title, avatar_url, created_at)
        VALUES ('direct', ?, ?, '', ?)
      `
      )
      .run(slug, title, Date.now());
    row = { id: Number(result.lastInsertRowid) };
  }

  for (const userId of ids) {
    db.prepare(
      `
      INSERT INTO conversation_members (conversation_id, user_id, role, joined_at)
      VALUES (?, ?, 'member', ?)
      ON CONFLICT(conversation_id, user_id) DO NOTHING
    `
    ).run(row.id, userId, Date.now());
    ensureConversationReadState(row.id, userId);
  }

  return row.id;
}

function ensureAllDirectConversations() {
  const users = db.prepare("SELECT id FROM users ORDER BY id").all();
  for (let i = 0; i < users.length; i += 1) {
    for (let j = i + 1; j < users.length; j += 1) {
      ensureDirectConversation(users[i].id, users[j].id);
    }
  }
}

bootstrapService.initialize();
reportForeignKeyViolations(db);
for (const row of db.prepare("SELECT id FROM users").all()) ensureReadState(row.id);
ensureFamilyConversation();
ensureGroupCodes();
ensureGroupAdmins();

const fcmTokenRepository = createFcmTokenRepository(db);
const fcmService = createFcmService({
  projectId: FCM_PROJECT_ID,
  clientEmail: FCM_CLIENT_EMAIL,
  privateKey: FCM_PRIVATE_KEY,
  tokenRepository: fcmTokenRepository,
  logger: fcmLogger,
});

const authSessionService = createAuthSessionService({
  db, crypto, jwt, jwtSecret: JWT_SECRET,
  accessTokenTtlSeconds: ACCESS_TOKEN_TTL_SECONDS,
  refreshTokenTtlMs: REFRESH_TOKEN_TTL_MS,
  deviceStatusTrusted: DEVICE_STATUS_TRUSTED,
  deviceStatusRevoked: DEVICE_STATUS_REVOKED,
  sessionStatusActive: SESSION_STATUS_ACTIVE,
  sessionStatusPending: SESSION_STATUS_PENDING,
  sessionStatusRevoked: SESSION_STATUS_REVOKED,
  manageCookieName: MANAGE_COOKIE_NAME,
  manageLoginMaxFailures: MANAGE_LOGIN_MAX_FAILURES,
  manageLoginCooldownMs: MANAGE_LOGIN_COOLDOWN_MS,
  appLoginMaxFailures: APP_LOGIN_MAX_FAILURES,
  appLoginCooldownMs: APP_LOGIN_COOLDOWN_MS,
  manageTotpDigits: MANAGE_TOTP_DIGITS,
  manageTotpPeriod: MANAGE_TOTP_PERIOD,
  manageTotpSecretBytes: MANAGE_TOTP_SECRET_BYTES,
  manageTotpChallengeTtlMs: MANAGE_TOTP_CHALLENGE_TTL_MS,
  manageTotpChallengeMaxAttempts: MANAGE_TOTP_CHALLENGE_MAX_ATTEMPTS,
  getClientIp, getClientType,
});
const {
  hashRefreshToken, newOpaqueToken, createAuthSession, issueSessionResponse, signManageToken,
  verifyToken, pendingAuthMiddleware, authMiddleware, manageCookieOptions, manageAuthMiddleware,
  getManageLoginAttemptKey, getAppLoginAttemptKey, getManageLoginAttempt, resetManageLoginAttempt,
  registerFailedManageLogin, registerFailedAppLogin, generateManageTotpSecret, verifyTotpCode,
  buildManageTotpLabel, buildManageTotpUri, createManageTotpChallenge, getManageTotpChallenge,
  consumeManageTotpChallenge, registerFailedManageTotpChallenge,
} = authSessionService;

if (VAPID_PUBLIC_KEY && VAPID_PRIVATE_KEY) {
  webpush.setVapidDetails(VAPID_SUBJECT, VAPID_PUBLIC_KEY, VAPID_PRIVATE_KEY);
} else {
  serverLogger.warn("web_push_disabled", { reason: "missing_vapid_configuration" });
}

const storage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, UPLOAD_DIR),
  filename: (req, file, cb) => {
    const ext = (path.extname(file.originalname) || ".bin").slice(0, 12);
    cb(null, `${Date.now()}_${crypto.randomBytes(8).toString("hex")}${ext}`);
  },
});

const upload = multer({
  storage,
  limits: { fileSize: MAX_UPLOAD_BYTES },
});

const manageApkUpload = multer({
  storage: multer.memoryStorage(),
  limits: { fileSize: MAX_MANAGE_APK_BYTES },
});

const blogStorage = multer.diskStorage({
  destination: (_req, _file, cb) => cb(null, BLOG_UPLOAD_DIR),
  filename: (_req, file, cb) => {
    const ext = (path.extname(file.originalname) || ".bin").replace(/[^.A-Za-z0-9]/g, "").slice(0, 12);
    cb(null, `${Date.now()}_${crypto.randomBytes(12).toString("hex")}${ext}`);
  },
});
const blogUpload = multer({
  storage: blogStorage,
  limits: { fileSize: MAX_BLOG_MEDIA_BYTES, files: 9, fields: 4 },
});

const blogAtRestService = BLOG_ENABLED
  ? createBlogAtRestService({ keyText: BLOG_AT_REST_KEY, fs, path })
  : null;
const blogRepository = createBlogRepository(db, blogAtRestService);
const blogService = createBlogService({
  repository: blogRepository,
  fs,
  path,
  blogUploadDir: BLOG_UPLOAD_DIR,
  atRest: blogAtRestService,
  logger: blogLogger,
});
if (BLOG_ENABLED) {
  blogService.migrateAtRest();
} else {
  blogLogger.warn("disabled", { reason: "BLOG_AT_REST_KEY_not_configured_or_BLOG_ENABLED_false" });
}

const messageService = createMessageService({
  db,
  maxReplyLength: MAX_REPLY_LEN,
  maxMentions: MAX_MENTIONS,
  retentionMs: RETENTION_MS,
  getFamilyConversationId,
});
const {
  safeJsonParse, sanitizeReplyTo, sanitizeMentions, extractAttachmentFile,
  messageRowToWire, conversationExpiresAt,
} = messageService;

const deviceSecurityService = createDeviceSecurityService({
  db, deviceStatusTrusted: DEVICE_STATUS_TRUSTED, deviceStatusRevoked: DEVICE_STATUS_REVOKED,
  getUserById: (...args) => conversationRepository.getUserById(...args),
  getDevice: (...args) => deviceAccountService.getDevice(...args),
  getGroupConversationsForUser, rotateGroupKeyEpochsForUser, conversationExpiresAt,
  messageRowToWire, broadcastToConversation, broadcastToUser, forceLogoutDevice,
});
const {
  emitDeviceSafetyChangeNotices, emitDeviceAddedNotices, emitDeviceRemovedNotices,
  broadcastDirectPeerKeysChanged, approveDevice, revokeDevice,
} = deviceSecurityService;

const conversationRepository = createConversationRepository(db);
const {
  getUsers, getReadStates, getConversationReadStates, getConversationDeliveryStates,
  upsertReadState, upsertConversationReadState, upsertConversationDeliveryState,
  getUserById, getConversationForUser, getConversationMembers, getDirectConversationPeer,
  getOwnedGroupsBlockingAccountDeletion,
} = conversationRepository;

const groupService = createGroupService({
  db,
  deleteAttachmentFiles: (...args) => historyService.deleteAttachmentFiles(...args),
  clearPendingCallInvitesForConversation: (...args) => callService.clearPendingCallInvitesForConversation(...args),
});
const {
  cleanupConversation, promoteGroupAdminIfNeeded, clearConversationHistory,
  getDirectConversationBetween, getGroupRole, isGroupOwner, isGroupAdmin, countGroupAdmins,
  groupAdminRequestToWire, isConversationMember, normalizeUserCode, normalizeGroupCode,
  contactRequestToWire, groupJoinRequestToWire,
} = groupService;

const callService = createCallService({
  db, callInviteTtlMs: CALL_INVITE_TTL_MS,
  callInviteFallbackDelayMs: CALL_INVITE_FCM_FALLBACK_DELAY_MS,
  stunUrls: CALL_STUN_URLS, turnUrls: CALL_TURN_URLS,
  turnUsername: CALL_TURN_USERNAME, turnCredential: CALL_TURN_CREDENTIAL,
  sendFcmIncomingCallToUser, logCallDebug,
});
const {
  upsertPendingCallInvite, takePendingCallInvitesForUser, clearPendingCallInvitesForConversation,
  clearPendingCallFallback, schedulePendingCallFallback, getCallIceServers,
} = callService;

const conversationSummaryService = createConversationSummaryService({
  db, fs, path, dbPath: DB_PATH, uploadDir: UPLOAD_DIR,
  getConversationMembers, getUserById, isSocketOnline, getClients: () => clients,
});
const {
  getConversationTitleForUser, getConversationAvatarForUser, getConversationSummaries,
  getOnlineUsers, getStorageSummary,
} = conversationSummaryService;

const deviceAccountService = createDeviceAccountService({
  db, crypto, fs, path, uploadDir: UPLOAD_DIR,
  deviceStatusTrusted: DEVICE_STATUS_TRUSTED, deviceStatusRevoked: DEVICE_STATUS_REVOKED,
  getClientIp, generateUniqueUserCode, ensureReadState, ensureFamilyConversation,
  getUserById, getOwnedGroupsBlockingAccountDeletion, cleanupConversation,
  promoteGroupAdminIfNeeded, rotateAndBroadcastGroupKeyEpoch,
});
const {
  isValidUsername, normalizeDeviceId, isValidDeviceId, getDevice, ensureLoginDevice,
  normalizePublicKey, deviceKeyFingerprint, deviceIdentityToWire, getPendingRegistrationRequests,
  getPendingDevicesForManage, getPendingAccountDeletionRequests, getRegisteredUsersForManage,
  finalizeApprovedRegistration, finalizeApprovedAccountDeletion,
} = deviceAccountService;

const releaseService = createReleaseService({
  db, fs, path, crypto, appReleaseDir: APP_RELEASE_DIR,
  releaseChannelRelease: RELEASE_CHANNEL_RELEASE, releaseChannelBeta: RELEASE_CHANNEL_BETA,
  releaseChannelAliases: RELEASE_CHANNEL_ALIASES, releaseChannelValues: RELEASE_CHANNEL_VALUES,
});
const {
  isValidReleaseVersion, extractReleaseVersion, normalizeReleaseChannel, inferReleaseChannel,
  getAppReleaseState, getAllAppReleaseStates, manageAdminToPublic, storeAppRelease,
} = releaseService;
const historyService = createHistoryService({ db, fs, path, uploadDir: UPLOAD_DIR, extractAttachmentFile });
const { deleteAttachmentFiles, clearAttachmentMessages, clearAllHistory } = historyService;

function createRouteContext() {
  return {
    logger: serverLogger,
    ACCESS_TOKEN_TTL_SECONDS,
    DEVICE_STATUS_REVOKED,
    DEVICE_STATUS_TRUSTED,
    GROUP_ADMIN_LIMIT,
    GROUP_MESSAGE_TTL_OPTIONS_MS,
    MANAGE_COOKIE_NAME,
    MANAGE_LOGIN_MAX_FAILURES,
    MANAGE_PUBLIC_DIR,
    MANAGE_TOTP_CHALLENGE_MAX_ATTEMPTS,
    MAX_CIPHERTEXT_LEN,
    MAX_PLAINTEXT_LEN,
    REFRESH_TOKEN_TTL_MS,
    RELEASE_CHANNEL_BETA,
    RELEASE_CHANNEL_RELEASE,
    SESSION_STATUS_ACTIVE,
    SESSION_STATUS_PENDING,
    SESSION_STATUS_REVOKED,
    UPLOAD_DIR,
    BLOG_ENABLED,
    app,
    approveDevice,
    authMiddleware,
    bcrypt,
    blogRepository,
    blogService,
    blogUpload,
    getAnnouncementAccount: bootstrapService.getAnnouncementAccount,
    broadcast,
    broadcastDirectPeerKeysChanged,
    broadcastToConversation,
    broadcastToUser,
    buildManageTotpLabel,
    buildManageTotpUri,
    cleanupConversation,
    clearAllHistory,
    clearAttachmentMessages,
    clearConversationHistory,
    consumeManageTotpChallenge,
    contactRequestToWire,
    conversationExpiresAt,
    countGroupAdmins,
    createAuthSession,
    createManageTotpChallenge,
    crypto,
    currentGroupKeyEpoch,
    db,
    deviceIdentityToWire,
    deviceKeyFingerprint,
    emitDeviceAddedNotices,
    emitDeviceSafetyChangeNotices,
    ensureConversationReadState,
    ensureDirectConversation,
    ensureLoginDevice,
    ensureReadState,
    express,
    extractAttachmentFile,
    extractReleaseVersion,
    finalizeApprovedAccountDeletion,
    finalizeApprovedRegistration,
    forceLogoutSession,
    fs,
    generateManageTotpSecret,
    generateUniqueGroupCode,
    getAllAppReleaseStates,
    getAppLoginAttemptKey,
    getAppReleaseState,
    getClientIp,
    getClientType,
    getConversationAvatarForUser,
    getConversationDeliveryStates,
    getConversationForUser,
    getConversationMembers,
    getConversationReadStates,
    getConversationSummaries,
    getConversationTitleForUser,
    getDirectConversationBetween,
    getGroupRole,
    getManageLoginAttempt,
    getManageLoginAttemptKey,
    getManageTotpChallenge,
    getOnlineUsers,
    getPendingAccountDeletionRequests,
    getPendingDevicesForManage,
    getPendingRegistrationRequests,
    getReadStates,
    getRegisteredUsersForManage,
    getStorageSummary,
    getUserById,
    getUsers,
    groupAdminRequestToWire,
    groupJoinRequestToWire,
    hashRefreshToken,
    inferReleaseChannel,
    isConversationMember,
    isGroupAdmin,
    isGroupOwner,
    isValidDeviceId,
    isValidReleaseVersion,
    isValidUsername,
    issueSessionResponse,
    logFcmDebug,
    manageAdminToPublic,
    manageApkUpload,
    manageAuthMiddleware,
    manageCookieOptions,
    manageHostOnly,
    markDeliveredForUser,
    markReadForUser,
    maskFcmToken,
    messageRowToWire,
    newOpaqueToken,
    normalizeDeviceId,
    normalizeGroupCode,
    normalizePublicKey,
    normalizeReleaseChannel,
    normalizeUserCode,
    path,
    pendingAuthMiddleware,
    promoteGroupAdminIfNeeded,
    registerFailedAppLogin,
    registerFailedManageLogin,
    registerFailedManageTotpChallenge,
    resetManageLoginAttempt,
    revokeDevice,
    rotateAndBroadcastGroupKeyEpoch,
    safeJsonParse,
    sanitizeMentions,
    sanitizeReplyTo,
    sendFcmToConversation,
    sendPushToConversation,
    signManageToken,
    storage,
    storeAppRelease,
    upload,
    verifyTotpCode,
  };
}

registerManageRoutes(createRouteContext());
registerPublicAuthRoutes(createRouteContext());

app.use("/api", appClientOnly);

registerSystemRoutes({
  app,
  authMiddleware,
  vapidPublicKey: VAPID_PUBLIC_KEY,
  defaultReleaseChannel: RELEASE_CHANNEL_RELEASE,
  normalizeReleaseChannel,
  getAppReleaseState,
  getCallIceServers,
  sendPushHealthCheck: (userId, diagnosticId) => fcmService.sendHealthCheckToUser(userId, diagnosticId),
  logger: serverLogger,
});

registerAuthDeviceRoutes(createRouteContext());

registerConversationRoutes(createRouteContext());

registerRelationshipRoutes(createRouteContext());

registerMessageAttachmentRoutes(createRouteContext());

registerBlogRoutes({
  app,
  authMiddleware,
  blogEnabled: BLOG_ENABLED,
  blogUpload,
  repository: blogRepository,
  service: blogService,
  broadcast,
  sendBlogCommentToUser: (userId, postId) => fcmService.sendBlogCommentToUser(userId, postId),
  logger: blogLogger,
});


const server = http.createServer(app);
const wss = new WebSocket.Server({ noServer: true });
const socketHub = createSocketHub({ WebSocket, isConversationMember, logger: websocketLogger });
const clients = socketHub.clients;

function broadcast(obj) {
  return socketHub.broadcast(obj);
}

function broadcastToConversation(conversationId, obj) {
  return socketHub.broadcastToConversation(conversationId, obj);
}

function broadcastToUser(userId, obj) {
  return socketHub.broadcastToUser(userId, obj);
}

function markDeliveredForUser(conversationId, userId, messageId) {
  const before =
    db
      .prepare(
        "SELECT COALESCE(last_delivered_message_id, 0) AS last_delivered_message_id FROM conversation_delivery_states WHERE conversation_id=? AND user_id=?"
      )
      .get(conversationId, userId)?.last_delivered_message_id || 0;
  upsertConversationDeliveryState(conversationId, userId, messageId);
  const after =
    db
      .prepare(
        "SELECT COALESCE(last_delivered_message_id, 0) AS last_delivered_message_id FROM conversation_delivery_states WHERE conversation_id=? AND user_id=?"
      )
      .get(conversationId, userId)?.last_delivered_message_id || 0;
  if (after > before) {
    const user = getUserById(userId);
    if (user) {
      serverLogger.info("receipt_delivered_advanced", {
        conversationId,
        userId,
        userCode: user.user_code || "",
        previousMessageId: before,
        messageId: after,
      });
      broadcastToConversation(conversationId, {
        type: "delivered_receipt",
        conversation_id: conversationId,
        user_code: user.user_code || "",
        username: user.username,
        last_delivered_message_id: after,
      });
    }
  }
}

function markReadForUser(conversationId, userId, messageId) {
  const before =
    db
      .prepare(
        "SELECT COALESCE(last_read_message_id, 0) AS last_read_message_id FROM conversation_read_states WHERE conversation_id=? AND user_id=?"
      )
      .get(conversationId, userId)?.last_read_message_id || 0;
  upsertConversationReadState(conversationId, userId, messageId);
  const after =
    db
      .prepare(
        "SELECT COALESCE(last_read_message_id, 0) AS last_read_message_id FROM conversation_read_states WHERE conversation_id=? AND user_id=?"
      )
      .get(conversationId, userId)?.last_read_message_id || 0;
  if (after > before) {
    const user = getUserById(userId);
    if (user) {
      broadcastToConversation(conversationId, {
        type: "read_receipt",
        conversation_id: conversationId,
        user_code: user.user_code || "",
        username: user.username,
        last_read_message_id: after,
      });
    }
  }
}

function forceLogoutUser(userId, clientType = null) {
  socketHub.forceLogoutUser(userId, clientType);
}

function forceLogoutSession(sessionId, reason = "session_revoked") {
  socketHub.forceLogoutSession(sessionId, reason);
}

function forceLogoutDevice(userId, deviceId, reason = "device_revoked") {
  socketHub.forceLogoutDevice(userId, deviceId, reason);
}

registerSocketEvents({
  server,
  wss,
  db,
  socketHub,
  sessionStatusActive: SESSION_STATUS_ACTIVE,
  deviceStatusTrusted: DEVICE_STATUS_TRUSTED,
  maxCallSignalTextLength: MAX_CALL_SIGNAL_TEXT_LEN,
  maxPlaintextLength: MAX_PLAINTEXT_LEN,
  maxCiphertextLength: MAX_CIPHERTEXT_LEN,
  callInviteFallbackDelayMs: CALL_INVITE_FCM_FALLBACK_DELAY_MS,
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
  logger: websocketLogger,
});

const maintenanceService = createMaintenanceService({
  db,
  fs,
  path,
  uploadDir: UPLOAD_DIR,
  clients,
  socketHub,
  extractAttachmentFile,
  broadcast,
  cleanupBlogOrphans: blogService.cleanupOrphanFiles,
  logger: maintenanceLogger,
});
maintenanceService.start();
async function sendPushToConversation(conversationId, senderUserId, bodyText) {
  if (!VAPID_PUBLIC_KEY || !VAPID_PRIVATE_KEY) return;

  const subs = db
    .prepare(
      `
      SELECT ps.endpoint, ps.p256dh, ps.auth
      FROM push_subs ps
      JOIN conversation_members cm ON cm.user_id = ps.user_id
      WHERE cm.conversation_id = ?
        AND ps.user_id != ?
    `
    )
    .all(conversationId, senderUserId);

  const conversation = db.prepare("SELECT title FROM conversations WHERE id=?").get(conversationId);
  const payload = JSON.stringify({ title: conversation?.title || "Family Chat", body: bodyText, url: "/" });

  for (const subRow of subs) {
    const sub = {
      endpoint: subRow.endpoint,
      keys: { p256dh: subRow.p256dh, auth: subRow.auth },
    };

    try {
      await webpush.sendNotification(sub, payload);
    } catch {
      try {
        db.prepare("DELETE FROM push_subs WHERE endpoint=?").run(subRow.endpoint);
      } catch {}
    }
  }
}

function maskFcmToken(token) {
  const text = String(token || "");
  if (!text) return "(empty)";
  if (text.length <= 12) return text;
  return `${text.slice(0, 6)}...${text.slice(-6)}`;
}

function logCallDebug(event, details = {}) {
  websocketLogger.info(`call_${event}`, details);
}

function logCallWarn(event, details = {}) {
  websocketLogger.warn(`call_${event}`, details);
}

function logFcmDebug(event, details = {}) {
  fcmLogger.info(event, details);
}

function logFcmWarn(event, details = {}) {
  fcmLogger.warn(event, details);
}

async function sendFcmToConversation(conversationId, senderUserId, title, bodyText, meta = {}) {
  return fcmService.sendToConversation(conversationId, senderUserId, title, bodyText, meta);
}

async function sendFcmIncomingCallToUser(userId, invite) {
  return fcmService.sendIncomingCallToUser(userId, invite);
}

async function sendFcmCallHangupToUser(userId, conversationId) {
  return fcmService.sendCallHangupToUser(userId, conversationId);
}
const DISABLED_CHAT_HTML = `<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
    <title>Family Chat</title>
    <style>
      body {
        margin: 0;
        min-height: 100vh;
        display: grid;
        place-items: center;
        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
        background: #efeae2;
        color: #1f2937;
      }
      main {
        width: min(92vw, 520px);
        background: #ffffff;
        border-radius: 28px;
        padding: 32px;
        box-shadow: 0 16px 40px rgba(15, 23, 42, 0.12);
      }
      h1 { margin: 0 0 12px; color: #128c7e; }
      p { margin: 0 0 10px; line-height: 1.6; }
      a { color: #128c7e; }
    </style>
  </head>
  <body>
    <main>
      <h1>Family Chat</h1>
      <p>Web chat has been disabled. Please use the Android app to sign in and chat.</p>
      <p>Management portal: <a href="https://${MANAGE_HOST}/">https://${MANAGE_HOST}/</a></p>
    </main>
  </body>
</html>`;

app.use((req, res) => {
  if (req.method !== "GET" && req.method !== "HEAD") {
    return res.status(404).json({ error: "not_found" });
  }
  if (isManageHost(req)) {
    return res.sendFile(path.join(MANAGE_PUBLIC_DIR, "index.html"));
  }
  return res.status(403).type("html").send(DISABLED_CHAT_HTML);
});

server.listen(PORT, RUNTIME.host, () => {
  serverLogger.info("listening", { host: RUNTIME.host, port: Number(PORT) });
});

function shutdown(signal) {
  serverLogger.info("shutdown_started", { signal });
  maintenanceService.stop();
  callService.stop();
  for (const ws of clients) {
    try {
      ws.close(1001, "server_shutdown");
    } catch {}
  }
  server.close(() => {
    runCatchingDatabaseClose();
    process.exit(0);
  });
  setTimeout(() => process.exit(1), 10_000).unref();
}

function runCatchingDatabaseClose() {
  try {
    db.close();
  } catch (error) {
    serverLogger.warn("database_close_failed", { error: error?.name || "Error" });
  }
}

process.once("SIGTERM", () => shutdown("SIGTERM"));
process.once("SIGINT", () => shutdown("SIGINT"));
