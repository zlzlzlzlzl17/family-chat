"use strict";

function createAuthSessionService(options) {
  const {
    db, crypto, jwt, jwtSecret, accessTokenTtlSeconds, refreshTokenTtlMs,
    deviceStatusTrusted, deviceStatusRevoked, sessionStatusActive, sessionStatusPending,
    sessionStatusRevoked, manageCookieName, manageLoginMaxFailures, manageLoginCooldownMs,
    appLoginMaxFailures, appLoginCooldownMs, manageTotpDigits, manageTotpPeriod,
    manageTotpSecretBytes, manageTotpChallengeTtlMs, manageTotpChallengeMaxAttempts,
    getClientIp, getClientType,
  } = options;
  const JWT_SECRET = jwtSecret;
  const ACCESS_TOKEN_TTL_SECONDS = accessTokenTtlSeconds;
  const REFRESH_TOKEN_TTL_MS = refreshTokenTtlMs;
  const DEVICE_STATUS_TRUSTED = deviceStatusTrusted;
  const DEVICE_STATUS_REVOKED = deviceStatusRevoked;
  const SESSION_STATUS_ACTIVE = sessionStatusActive;
  const SESSION_STATUS_PENDING = sessionStatusPending;
  const SESSION_STATUS_REVOKED = sessionStatusRevoked;
  const MANAGE_COOKIE_NAME = manageCookieName;
  const MANAGE_LOGIN_MAX_FAILURES = manageLoginMaxFailures;
  const MANAGE_LOGIN_COOLDOWN_MS = manageLoginCooldownMs;
  const APP_LOGIN_MAX_FAILURES = appLoginMaxFailures;
  const APP_LOGIN_COOLDOWN_MS = appLoginCooldownMs;
  const MANAGE_TOTP_DIGITS = manageTotpDigits;
  const MANAGE_TOTP_PERIOD = manageTotpPeriod;
  const MANAGE_TOTP_SECRET_BYTES = manageTotpSecretBytes;
  const MANAGE_TOTP_CHALLENGE_TTL_MS = manageTotpChallengeTtlMs;
  const MANAGE_TOTP_CHALLENGE_MAX_ATTEMPTS = manageTotpChallengeMaxAttempts;

function hashRefreshToken(token) {
  return crypto.createHash("sha256").update(String(token || ""), "utf8").digest("hex");
}

function newOpaqueToken(bytes = 32) {
  return crypto.randomBytes(bytes).toString("base64url");
}

function signToken(user, session) {
  return jwt.sign(
    {
      uid: user.id || user.user_id,
      user_code: user.user_code || "",
      username: user.username,
      color: user.color,
      is_admin: !!user.is_admin,
      sid: session.session_id,
      did: session.device_id,
      client_type: session.client_type || "mobile",
    },
    JWT_SECRET,
    { expiresIn: ACCESS_TOKEN_TTL_SECONDS }
  );
}

function createAuthSession(user, device, clientType, req) {
  const now = Date.now();
  const sessionId = newOpaqueToken(24);
  const refreshToken = newOpaqueToken(48);
  const status = device.status === DEVICE_STATUS_TRUSTED ? SESSION_STATUS_ACTIVE : SESSION_STATUS_PENDING;
  db.prepare(
    `
      INSERT INTO auth_sessions (
        session_id, user_id, device_id, client_type, refresh_token_hash, status,
        created_at, last_seen_at, access_expires_at, refresh_expires_at,
        revoked_at, ip, user_agent
      )
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
    `
  ).run(
    sessionId,
    user.id,
    device.device_id,
    clientType,
    hashRefreshToken(refreshToken),
    status,
    now,
    now,
    now + ACCESS_TOKEN_TTL_SECONDS * 1000,
    now + REFRESH_TOKEN_TTL_MS,
    getClientIp(req),
    String(req.headers["user-agent"] || "").slice(0, 240)
  );
  return {
    session: db.prepare("SELECT * FROM auth_sessions WHERE session_id=?").get(sessionId),
    refreshToken,
  };
}

function issueSessionResponse(user, session, refreshToken = "") {
  return {
    token: signToken(user, session),
    refresh_token: refreshToken,
    session_id: session.session_id,
    device_id: session.device_id,
    device_status: session.device_status || session.device_status_value || "",
    user_code: user.user_code || "",
    username: user.username,
    color: user.color,
    avatar_url: user.avatar_url || "",
    is_admin: !!user.is_admin,
  };
}

function signManageToken(admin) {
  return jwt.sign(
    {
      mid: admin.id,
      username: admin.username,
      sid: admin.session_id,
      type: "manage",
    },
    JWT_SECRET,
    { expiresIn: "12h" }
  );
}

function verifyToken(token) {
  return jwt.verify(token, JWT_SECRET);
}

function authFromReq(req) {
  const hdr = req.headers.authorization || "";
  const token = hdr.startsWith("Bearer ") ? hdr.slice(7) : null;
  if (!token) return null;
  try {
    return verifyToken(token);
  } catch {
    return null;
  }
}

function authenticateAppRequest(req, res, next, requireTrusted) {
  const auth = authFromReq(req);
  if (!auth) return res.status(401).json({ error: "unauthorized" });

  const clientType = getClientType(req);
  if ((auth.client_type || "mobile") !== clientType) {
    return res.status(401).json({ error: "session_expired" });
  }

  const session = db
    .prepare(
      `
        SELECT s.*, d.status AS device_status, d.device_name, d.platform,
               u.user_code, u.username, u.color, u.avatar_url, u.is_admin, u.account_status
        FROM auth_sessions s
        JOIN devices d ON d.user_id=s.user_id AND d.device_id=s.device_id
        JOIN users u ON u.id=s.user_id
        WHERE s.session_id=? AND s.user_id=?
      `
    )
    .get(auth.sid, auth.uid);
  if (
    !session ||
    session.client_type !== clientType ||
    session.status === SESSION_STATUS_REVOKED ||
    Number(session.refresh_expires_at || 0) <= Date.now() ||
    (auth.did && auth.did !== session.device_id) ||
    session.account_status === "disabled" ||
    session.account_status === "deleted" ||
    session.account_status === "system"
  ) {
    return res.status(401).json({ error: "session_expired" });
  }

  if (session.device_status === DEVICE_STATUS_REVOKED) {
    return res.status(401).json({ error: "device_revoked" });
  }
  if (
    requireTrusted &&
    (session.device_status !== DEVICE_STATUS_TRUSTED || session.status !== SESSION_STATUS_ACTIVE)
  ) {
    return res.status(403).json({ error: "device_pending" });
  }

  const now = Date.now();
  if (now - Number(session.last_seen_at || 0) > 30_000) {
    db.prepare("UPDATE auth_sessions SET last_seen_at=?, access_expires_at=?, ip=? WHERE session_id=?").run(
      now,
      now + ACCESS_TOKEN_TTL_SECONDS * 1000,
      getClientIp(req),
      session.session_id
    );
    db.prepare("UPDATE devices SET last_seen_at=?, last_ip=? WHERE user_id=? AND device_id=?").run(
      now,
      getClientIp(req),
      session.user_id,
      session.device_id
    );
  }

  req.auth = {
    ...auth,
    uid: session.user_id,
    sid: session.session_id,
    device_id: session.device_id,
    device_status: session.device_status,
    user_code: session.user_code || "",
    username: session.username,
    color: session.color,
    is_admin: !!session.is_admin,
  };
  req.authSession = session;
  next();
}

function pendingAuthMiddleware(req, res, next) {
  return authenticateAppRequest(req, res, next, false);
}

function authMiddleware(req, res, next) {
  return authenticateAppRequest(req, res, next, true);
}

function manageAuthFromReq(req) {
  const token = req.cookies?.[MANAGE_COOKIE_NAME];
  if (!token) return null;
  try {
    const decoded = verifyToken(token);
    if (decoded.type !== "manage") return null;
    return decoded;
  } catch {
    return null;
  }
}

function manageCookieOptions(req) {
  const proto = String(req.headers["x-forwarded-proto"] || req.protocol || "").split(",")[0].trim();
  return {
    httpOnly: true,
    sameSite: "lax",
    secure: proto === "https",
    path: "/",
  };
}

function manageAuthMiddleware(req, res, next) {
  const auth = manageAuthFromReq(req);
  if (!auth) return res.status(401).json({ error: "unauthorized" });

  const admin = db
    .prepare(
      "SELECT id, username, password_hash, session_id, updated_at, totp_secret, totp_pending_secret, totp_enabled FROM manage_admins WHERE id=?"
    )
    .get(auth.mid);
  if (!admin || admin.session_id !== auth.sid) {
    res.clearCookie(MANAGE_COOKIE_NAME, { path: "/" });
    return res.status(401).json({ error: "session_expired" });
  }

  req.manageAuth = auth;
  req.manageAdmin = admin;
  next();
}

function getManageLoginAttemptKey(req, username) {
  const normalizedUsername = String(username || "").trim().toLowerCase();
  const ip = String(getClientIp(req) || "unknown").toLowerCase();
  return `manage:${normalizedUsername}|${ip}`;
}

function getAppLoginAttemptKey(req, identifier) {
  const normalizedIdentifier = String(identifier || "").trim().toLowerCase();
  const ip = String(getClientIp(req) || "unknown").toLowerCase();
  return `app:${normalizedIdentifier}|${ip}`;
}

function getManageLoginAttempt(key) {
  return db
    .prepare("SELECT key, fail_count, cooldown_until, updated_at FROM manage_login_attempts WHERE key=?")
    .get(key);
}

function resetManageLoginAttempt(key) {
  db.prepare("DELETE FROM manage_login_attempts WHERE key=?").run(key);
}

function registerFailedLogin(key, maxFailures, cooldownMs) {
  const now = Date.now();
  const existing = getManageLoginAttempt(key);
  const previousCooldownUntil = Number(existing?.cooldown_until || 0);
  const previousUpdatedAt = Number(existing?.updated_at || 0);
  const isStillCooling = previousCooldownUntil > now;
  const isExpiredWindow =
    (!isStillCooling && previousCooldownUntil > 0) ||
    (previousUpdatedAt > 0 && now - previousUpdatedAt >= cooldownMs);
  const baseFailCount = isStillCooling ? Number(existing?.fail_count || 0) : isExpiredWindow ? 0 : Number(existing?.fail_count || 0);
  const failCount = baseFailCount + 1;
  const cooldownUntil =
    failCount >= maxFailures
      ? now + cooldownMs
      : 0;

  db.prepare(
    `
    INSERT INTO manage_login_attempts (key, fail_count, cooldown_until, updated_at)
    VALUES (?, ?, ?, ?)
    ON CONFLICT(key) DO UPDATE SET
      fail_count = excluded.fail_count,
      cooldown_until = excluded.cooldown_until,
      updated_at = excluded.updated_at
  `
  ).run(key, failCount, cooldownUntil, now);

  return {
    fail_count: failCount,
    cooldown_until: cooldownUntil,
    retry_after_ms: Math.max(cooldownUntil - now, 0),
  };
}

function registerFailedManageLogin(key) {
  return registerFailedLogin(key, MANAGE_LOGIN_MAX_FAILURES, MANAGE_LOGIN_COOLDOWN_MS);
}

function registerFailedAppLogin(key) {
  return registerFailedLogin(key, APP_LOGIN_MAX_FAILURES, APP_LOGIN_COOLDOWN_MS);
}

const BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

function encodeBase32(buffer) {
  let bits = 0;
  let value = 0;
  let output = "";
  for (const byte of buffer) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      output += BASE32_ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) {
    output += BASE32_ALPHABET[(value << (5 - bits)) & 31];
  }
  return output;
}

function decodeBase32(input) {
  const normalized = String(input || "").toUpperCase().replace(/[^A-Z2-7]/g, "");
  let bits = 0;
  let value = 0;
  const output = [];
  for (const char of normalized) {
    const idx = BASE32_ALPHABET.indexOf(char);
    if (idx < 0) continue;
    value = (value << 5) | idx;
    bits += 5;
    if (bits >= 8) {
      output.push((value >>> (bits - 8)) & 255);
      bits -= 8;
    }
  }
  return Buffer.from(output);
}

function generateManageTotpSecret() {
  return encodeBase32(crypto.randomBytes(MANAGE_TOTP_SECRET_BYTES));
}

function hotp(secret, counter) {
  const key = decodeBase32(secret);
  const message = Buffer.alloc(8);
  const bigCounter = BigInt(counter);
  message.writeUInt32BE(Number((bigCounter >> 32n) & 0xffffffffn), 0);
  message.writeUInt32BE(Number(bigCounter & 0xffffffffn), 4);
  const digest = crypto.createHmac("sha1", key).update(message).digest();
  const offset = digest[digest.length - 1] & 0x0f;
  const code =
    ((digest[offset] & 0x7f) << 24) |
    ((digest[offset + 1] & 0xff) << 16) |
    ((digest[offset + 2] & 0xff) << 8) |
    (digest[offset + 3] & 0xff);
  return String(code % 10 ** MANAGE_TOTP_DIGITS).padStart(MANAGE_TOTP_DIGITS, "0");
}

function verifyTotpCode(secret, code) {
  const normalizedCode = String(code || "").replace(/\s+/g, "");
  if (!/^\d{6}$/.test(normalizedCode) || !secret) return false;
  const nowCounter = Math.floor(Date.now() / 1000 / MANAGE_TOTP_PERIOD);
  for (let offset = -1; offset <= 1; offset += 1) {
    if (hotp(secret, nowCounter + offset) === normalizedCode) return true;
  }
  return false;
}

function buildManageTotpLabel(username) {
  return `Family Chat (${username})`;
}

function buildManageTotpUri(username, secret) {
  const label = encodeURIComponent(buildManageTotpLabel(username));
  const issuer = encodeURIComponent("Family Chat");
  return `otpauth://totp/${label}?secret=${encodeURIComponent(secret)}&issuer=${issuer}&algorithm=SHA1&digits=${MANAGE_TOTP_DIGITS}&period=${MANAGE_TOTP_PERIOD}`;
}

function createManageTotpChallenge(adminId) {
  const token = crypto.randomBytes(24).toString("hex");
  const now = Date.now();
  db.prepare("DELETE FROM manage_totp_challenges WHERE admin_id=?").run(adminId);
  db.prepare(
    `
    INSERT INTO manage_totp_challenges (token, admin_id, attempts, created_at, expires_at)
    VALUES (?, ?, 0, ?, ?)
  `
  ).run(token, adminId, now, now + MANAGE_TOTP_CHALLENGE_TTL_MS);
  return token;
}

function getManageTotpChallenge(token) {
  return db
    .prepare("SELECT token, admin_id, attempts, created_at, expires_at FROM manage_totp_challenges WHERE token=?")
    .get(token);
}

function consumeManageTotpChallenge(token) {
  db.prepare("DELETE FROM manage_totp_challenges WHERE token=?").run(token);
}

function registerFailedManageTotpChallenge(token) {
  const challenge = getManageTotpChallenge(token);
  if (!challenge) return { attempts: 0, locked: true };
  const attempts = Number(challenge.attempts || 0) + 1;
  if (attempts >= MANAGE_TOTP_CHALLENGE_MAX_ATTEMPTS) {
    consumeManageTotpChallenge(token);
    return { attempts, locked: true };
  }
  db.prepare("UPDATE manage_totp_challenges SET attempts=? WHERE token=?").run(attempts, token);
  return { attempts, locked: false };
}

  return {
    hashRefreshToken, newOpaqueToken, createAuthSession, issueSessionResponse, signManageToken,
    verifyToken, pendingAuthMiddleware, authMiddleware, manageCookieOptions, manageAuthMiddleware,
    getManageLoginAttemptKey, getAppLoginAttemptKey, getManageLoginAttempt, resetManageLoginAttempt,
    registerFailedManageLogin, registerFailedAppLogin, generateManageTotpSecret, verifyTotpCode,
    buildManageTotpLabel, buildManageTotpUri, createManageTotpChallenge, getManageTotpChallenge,
    consumeManageTotpChallenge, registerFailedManageTotpChallenge,
  };
}

module.exports = { createAuthSessionService };
