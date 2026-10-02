"use strict";

const { runColumnMigrations } = require("../lib/schema-migrations");

function initializeSchema(db, { releaseChannelRelease, releaseChannelBeta }) {
  const RELEASE_CHANNEL_RELEASE = releaseChannelRelease;
  const RELEASE_CHANNEL_BETA = releaseChannelBeta;

db.exec(`
CREATE TABLE IF NOT EXISTS users (
  id INTEGER PRIMARY KEY,
  user_code TEXT NOT NULL UNIQUE DEFAULT '',
  username TEXT UNIQUE NOT NULL,
  password_hash TEXT NOT NULL,
  color TEXT NOT NULL,
  avatar_url TEXT NOT NULL DEFAULT '',
  avatar_file TEXT NOT NULL DEFAULT '',
  is_admin INTEGER NOT NULL DEFAULT 0,
  session_id TEXT NOT NULL DEFAULT '',
  desktop_session_id TEXT NOT NULL DEFAULT '',
  last_login_ip TEXT NOT NULL DEFAULT '',
  account_status TEXT NOT NULL DEFAULT 'active',
  password_changed_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS devices (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  platform TEXT NOT NULL DEFAULT 'android',
  device_name TEXT NOT NULL DEFAULT '',
  manufacturer TEXT NOT NULL DEFAULT '',
  model TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'pending',
  approved_by_device_id TEXT NOT NULL DEFAULT '',
  approved_by_admin TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  approved_at INTEGER NOT NULL DEFAULT 0,
  revoked_at INTEGER NOT NULL DEFAULT 0,
  last_seen_at INTEGER NOT NULL DEFAULT 0,
  last_ip TEXT NOT NULL DEFAULT '',
  UNIQUE(user_id, device_id),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS auth_sessions (
  session_id TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  client_type TEXT NOT NULL DEFAULT 'mobile',
  refresh_token_hash TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'pending',
  created_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  access_expires_at INTEGER NOT NULL,
  refresh_expires_at INTEGER NOT NULL,
  revoked_at INTEGER NOT NULL DEFAULT 0,
  ip TEXT NOT NULL DEFAULT '',
  user_agent TEXT NOT NULL DEFAULT '',
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS device_presence (
  user_id INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  websocket_connected INTEGER NOT NULL DEFAULT 0,
  app_state TEXT NOT NULL DEFAULT 'background',
  updated_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, device_id, session_id),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS conversations (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  kind TEXT NOT NULL,
  slug TEXT NOT NULL DEFAULT '',
  group_code TEXT NOT NULL DEFAULT '',
  title TEXT NOT NULL,
  avatar_url TEXT NOT NULL DEFAULT '',
  message_ttl_ms INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS conversations_slug_idx ON conversations(slug);

CREATE TABLE IF NOT EXISTS conversation_members (
  conversation_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  role TEXT NOT NULL DEFAULT 'member',
  joined_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (conversation_id, user_id),
  FOREIGN KEY(conversation_id) REFERENCES conversations(id),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS conversation_read_states (
  conversation_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  last_read_message_id INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (conversation_id, user_id),
  FOREIGN KEY(conversation_id) REFERENCES conversations(id),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS conversation_delivery_states (
  conversation_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  last_delivered_message_id INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (conversation_id, user_id),
  FOREIGN KEY(conversation_id) REFERENCES conversations(id),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS messages (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER NOT NULL DEFAULT 0,
  ts INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  username TEXT NOT NULL,
  color TEXT NOT NULL,
  kind TEXT NOT NULL,
  payload TEXT NOT NULL,
  reply_to TEXT NOT NULL DEFAULT '',
  mentions TEXT NOT NULL DEFAULT '[]',
  e2ee INTEGER NOT NULL,
  client_message_id TEXT NOT NULL DEFAULT '',
  sender_device_id TEXT NOT NULL DEFAULT '',
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS attachment_upload_sessions (
  upload_id TEXT PRIMARY KEY,
  user_id INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  client_message_id TEXT NOT NULL,
  conversation_id INTEGER NOT NULL,
  kind TEXT NOT NULL,
  payload TEXT NOT NULL,
  reply_to TEXT NOT NULL DEFAULT '',
  mentions TEXT NOT NULL DEFAULT '[]',
  part_file TEXT NOT NULL,
  total_size INTEGER NOT NULL,
  received_size INTEGER NOT NULL DEFAULT 0,
  checksum_sha256 TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  UNIQUE(user_id, device_id, client_message_id),
  FOREIGN KEY(user_id) REFERENCES users(id),
  FOREIGN KEY(conversation_id) REFERENCES conversations(id)
);

CREATE TABLE IF NOT EXISTS push_subs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  endpoint TEXT NOT NULL UNIQUE,
  p256dh TEXT NOT NULL,
  auth TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS fcm_tokens (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  device_id TEXT NOT NULL DEFAULT '',
  token TEXT NOT NULL UNIQUE,
  platform TEXT NOT NULL DEFAULT 'android',
  locale TEXT NOT NULL DEFAULT 'en',
  manufacturer TEXT NOT NULL DEFAULT '',
  model TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  last_success_at INTEGER NOT NULL DEFAULT 0,
  last_failure_at INTEGER NOT NULL DEFAULT 0,
  failure_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT NOT NULL DEFAULT '',
  blog_notifications_enabled INTEGER NOT NULL DEFAULT 1,
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS manage_admins (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  username TEXT NOT NULL UNIQUE,
  password_hash TEXT NOT NULL,
  session_id TEXT NOT NULL DEFAULT '',
  updated_at INTEGER NOT NULL,
  totp_secret TEXT NOT NULL DEFAULT '',
  totp_pending_secret TEXT NOT NULL DEFAULT '',
  totp_enabled INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS manage_login_attempts (
  key TEXT PRIMARY KEY,
  fail_count INTEGER NOT NULL DEFAULT 0,
  cooldown_until INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS manage_totp_challenges (
  token TEXT PRIMARY KEY,
  admin_id INTEGER NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  FOREIGN KEY(admin_id) REFERENCES manage_admins(id)
);

CREATE TABLE IF NOT EXISTS registration_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  requested_username TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  request_ip TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'pending',
  review_note TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  reviewed_at INTEGER NOT NULL DEFAULT 0,
  reviewed_by TEXT NOT NULL DEFAULT '',
  request_token_hash TEXT NOT NULL DEFAULT '',
  approved_user_code TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS device_identity_keys (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  platform TEXT NOT NULL DEFAULT 'android',
  device_name TEXT NOT NULL DEFAULT '',
  key_alg TEXT NOT NULL DEFAULT '',
  public_key TEXT NOT NULL,
  fingerprint TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  last_seen_at INTEGER NOT NULL,
  UNIQUE(user_id, device_id),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS direct_prekeys (
  user_id INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  key_alg TEXT NOT NULL DEFAULT '',
  identity_ecdh_public TEXT NOT NULL DEFAULT '',
  identity_ecdh_signature TEXT NOT NULL DEFAULT '',
  signed_prekey_public TEXT NOT NULL,
  signed_prekey_signature TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (user_id, device_id),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS direct_one_time_prekeys (
  user_id INTEGER NOT NULL,
  device_id TEXT NOT NULL,
  prekey_id TEXT NOT NULL,
  key_alg TEXT NOT NULL DEFAULT '',
  public_key TEXT NOT NULL,
  signature TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  claimed_at INTEGER NOT NULL DEFAULT 0,
  claimed_by_user_id INTEGER NOT NULL DEFAULT 0,
  claimed_for_conversation_id INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (user_id, device_id, prekey_id),
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS group_key_epochs (
  conversation_id INTEGER PRIMARY KEY,
  epoch INTEGER NOT NULL DEFAULT 1,
  updated_at INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(conversation_id) REFERENCES conversations(id)
);

CREATE TABLE IF NOT EXISTS group_sender_key_envelopes (
  conversation_id INTEGER NOT NULL,
  sender_user_id INTEGER NOT NULL,
  sender_device_id TEXT NOT NULL,
  recipient_user_id INTEGER NOT NULL,
  recipient_device_id TEXT NOT NULL,
  epoch INTEGER NOT NULL,
  key_id TEXT NOT NULL,
  wrapped_key TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  PRIMARY KEY (conversation_id, sender_user_id, sender_device_id, recipient_user_id, recipient_device_id, epoch, key_id),
  FOREIGN KEY(conversation_id) REFERENCES conversations(id),
  FOREIGN KEY(sender_user_id) REFERENCES users(id),
  FOREIGN KEY(recipient_user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS contact_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  requester_id INTEGER NOT NULL,
  target_user_id INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  created_at INTEGER NOT NULL,
  reviewed_at INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(requester_id) REFERENCES users(id),
  FOREIGN KEY(target_user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS group_join_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER NOT NULL,
  requester_id INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  created_at INTEGER NOT NULL,
  reviewed_at INTEGER NOT NULL DEFAULT 0,
  reviewed_by INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(conversation_id) REFERENCES conversations(id),
  FOREIGN KEY(requester_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS group_admin_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER NOT NULL,
  requester_id INTEGER NOT NULL,
  target_user_id INTEGER NOT NULL,
  status TEXT NOT NULL DEFAULT 'pending',
  created_at INTEGER NOT NULL,
  reviewed_at INTEGER NOT NULL DEFAULT 0,
  reviewed_by INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(conversation_id) REFERENCES conversations(id),
  FOREIGN KEY(requester_id) REFERENCES users(id),
  FOREIGN KEY(target_user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS account_deletion_requests (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  user_id INTEGER NOT NULL,
  username_snapshot TEXT NOT NULL DEFAULT '',
  user_code_snapshot TEXT NOT NULL DEFAULT '',
  request_ip TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL DEFAULT 'pending',
  review_note TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  reviewed_at INTEGER NOT NULL DEFAULT 0,
  reviewed_by TEXT NOT NULL DEFAULT '',
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS app_release_state (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  version TEXT NOT NULL DEFAULT '',
  file_name TEXT NOT NULL DEFAULT '',
  original_name TEXT NOT NULL DEFAULT '',
  file_size INTEGER NOT NULL DEFAULT 0,
  sha256 TEXT NOT NULL DEFAULT '',
  uploaded_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS app_release_channels (
  channel TEXT PRIMARY KEY,
  version TEXT NOT NULL DEFAULT '',
  file_name TEXT NOT NULL DEFAULT '',
  original_name TEXT NOT NULL DEFAULT '',
  file_size INTEGER NOT NULL DEFAULT 0,
  sha256 TEXT NOT NULL DEFAULT '',
  uploaded_at INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS read_states (
  user_id INTEGER PRIMARY KEY,
  last_read_message_id INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS pending_call_invites (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  conversation_id INTEGER NOT NULL,
  caller_id INTEGER NOT NULL,
  callee_id INTEGER NOT NULL,
  caller_user_code TEXT NOT NULL DEFAULT '',
  caller_username TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  expires_at INTEGER NOT NULL,
  UNIQUE(callee_id, conversation_id)
);

CREATE TABLE IF NOT EXISTS blog_posts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  author_user_id INTEGER NOT NULL,
  body TEXT NOT NULL DEFAULT '',
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY(author_user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS blog_media (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  post_id INTEGER NOT NULL,
  media_kind TEXT NOT NULL,
  mime_type TEXT NOT NULL,
  original_name TEXT NOT NULL DEFAULT '',
  storage_name TEXT NOT NULL UNIQUE,
  file_size INTEGER NOT NULL,
  width INTEGER NOT NULL DEFAULT 0,
  height INTEGER NOT NULL DEFAULT 0,
  duration_ms INTEGER NOT NULL DEFAULT 0,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  FOREIGN KEY(post_id) REFERENCES blog_posts(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS blog_likes (
  post_id INTEGER NOT NULL,
  user_id INTEGER NOT NULL,
  created_at INTEGER NOT NULL,
  PRIMARY KEY(post_id, user_id),
  FOREIGN KEY(post_id) REFERENCES blog_posts(id) ON DELETE CASCADE,
  FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS blog_comments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  post_id INTEGER NOT NULL,
  author_user_id INTEGER NOT NULL,
  body TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  FOREIGN KEY(post_id) REFERENCES blog_posts(id) ON DELETE CASCADE,
  FOREIGN KEY(author_user_id) REFERENCES users(id) ON DELETE CASCADE
);
`);

db.exec(`
CREATE INDEX IF NOT EXISTS registration_requests_status_idx ON registration_requests(status, created_at DESC);
CREATE INDEX IF NOT EXISTS devices_user_status_idx ON devices(user_id, status, last_seen_at DESC);
CREATE INDEX IF NOT EXISTS auth_sessions_user_device_idx ON auth_sessions(user_id, device_id, status, last_seen_at DESC);
CREATE INDEX IF NOT EXISTS auth_sessions_refresh_idx ON auth_sessions(refresh_token_hash);
CREATE INDEX IF NOT EXISTS device_presence_updated_idx ON device_presence(updated_at DESC);
CREATE INDEX IF NOT EXISTS device_identity_keys_user_idx ON device_identity_keys(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS direct_prekeys_user_idx ON direct_prekeys(user_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS direct_one_time_prekeys_available_idx ON direct_one_time_prekeys(user_id, device_id, claimed_at, created_at);
CREATE INDEX IF NOT EXISTS group_sender_key_envelopes_recipient_idx ON group_sender_key_envelopes(conversation_id, recipient_user_id, recipient_device_id, epoch, updated_at DESC);
CREATE INDEX IF NOT EXISTS group_sender_key_envelopes_sender_idx ON group_sender_key_envelopes(conversation_id, sender_user_id, sender_device_id, epoch, updated_at DESC);
CREATE INDEX IF NOT EXISTS contact_requests_target_status_idx ON contact_requests(target_user_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS contact_requests_requester_status_idx ON contact_requests(requester_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS group_join_requests_conversation_status_idx ON group_join_requests(conversation_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS group_join_requests_requester_status_idx ON group_join_requests(requester_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS group_admin_requests_conversation_status_idx ON group_admin_requests(conversation_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS group_admin_requests_requester_status_idx ON group_admin_requests(requester_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS account_deletion_requests_status_idx ON account_deletion_requests(status, created_at DESC);
CREATE INDEX IF NOT EXISTS account_deletion_requests_user_status_idx ON account_deletion_requests(user_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS pending_call_invites_callee_idx ON pending_call_invites(callee_id, expires_at DESC);
CREATE INDEX IF NOT EXISTS attachment_upload_sessions_owner_idx ON attachment_upload_sessions(user_id, device_id, updated_at);
CREATE INDEX IF NOT EXISTS blog_posts_created_idx ON blog_posts(created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS blog_media_post_idx ON blog_media(post_id, sort_order, id);
CREATE INDEX IF NOT EXISTS blog_likes_post_idx ON blog_likes(post_id, created_at DESC);
CREATE INDEX IF NOT EXISTS blog_comments_post_idx ON blog_comments(post_id, created_at, id);
`);

runColumnMigrations(db, [
  { version: 1, name: "direct_prekeys_identity_ecdh_public", table: "direct_prekeys", column: "identity_ecdh_public", definition: "identity_ecdh_public TEXT NOT NULL DEFAULT ''" },
  { version: 2, name: "direct_prekeys_identity_ecdh_signature", table: "direct_prekeys", column: "identity_ecdh_signature", definition: "identity_ecdh_signature TEXT NOT NULL DEFAULT ''" },
  { version: 3, name: "messages_reply_to", table: "messages", column: "reply_to", definition: "reply_to TEXT NOT NULL DEFAULT ''" },
  { version: 4, name: "messages_mentions", table: "messages", column: "mentions", definition: "mentions TEXT NOT NULL DEFAULT '[]'" },
  { version: 5, name: "users_user_code", table: "users", column: "user_code", definition: "user_code TEXT NOT NULL DEFAULT ''" },
  { version: 6, name: "users_last_login_ip", table: "users", column: "last_login_ip", definition: "last_login_ip TEXT NOT NULL DEFAULT ''" },
  { version: 7, name: "users_desktop_session_id", table: "users", column: "desktop_session_id", definition: "desktop_session_id TEXT NOT NULL DEFAULT ''", afterSql: "UPDATE users SET desktop_session_id='' WHERE desktop_session_id IS NULL;" },
  { version: 8, name: "users_account_status", table: "users", column: "account_status", definition: "account_status TEXT NOT NULL DEFAULT 'active'" },
  { version: 9, name: "users_password_changed_at", table: "users", column: "password_changed_at", definition: "password_changed_at INTEGER NOT NULL DEFAULT 0" },
  { version: 10, name: "fcm_tokens_device_id", table: "fcm_tokens", column: "device_id", definition: "device_id TEXT NOT NULL DEFAULT ''" },
  { version: 11, name: "registration_requests_request_token_hash", table: "registration_requests", column: "request_token_hash", definition: "request_token_hash TEXT NOT NULL DEFAULT ''" },
  { version: 12, name: "registration_requests_approved_user_code", table: "registration_requests", column: "approved_user_code", definition: "approved_user_code TEXT NOT NULL DEFAULT ''" },
  { version: 13, name: "users_avatar_url", table: "users", column: "avatar_url", definition: "avatar_url TEXT NOT NULL DEFAULT ''" },
  { version: 14, name: "users_avatar_file", table: "users", column: "avatar_file", definition: "avatar_file TEXT NOT NULL DEFAULT ''" },
  { version: 15, name: "fcm_tokens_locale", table: "fcm_tokens", column: "locale", definition: "locale TEXT NOT NULL DEFAULT 'en'" },
  { version: 16, name: "fcm_tokens_manufacturer", table: "fcm_tokens", column: "manufacturer", definition: "manufacturer TEXT NOT NULL DEFAULT ''" },
  { version: 17, name: "fcm_tokens_model", table: "fcm_tokens", column: "model", definition: "model TEXT NOT NULL DEFAULT ''" },
  { version: 18, name: "fcm_tokens_last_success_at", table: "fcm_tokens", column: "last_success_at", definition: "last_success_at INTEGER NOT NULL DEFAULT 0" },
  { version: 19, name: "fcm_tokens_last_failure_at", table: "fcm_tokens", column: "last_failure_at", definition: "last_failure_at INTEGER NOT NULL DEFAULT 0" },
  { version: 20, name: "fcm_tokens_failure_count", table: "fcm_tokens", column: "failure_count", definition: "failure_count INTEGER NOT NULL DEFAULT 0" },
  { version: 21, name: "fcm_tokens_last_error", table: "fcm_tokens", column: "last_error", definition: "last_error TEXT NOT NULL DEFAULT ''" },
  { version: 22, name: "messages_conversation_id", table: "messages", column: "conversation_id", definition: "conversation_id INTEGER NOT NULL DEFAULT 0" },
  { version: 23, name: "conversations_group_code", table: "conversations", column: "group_code", definition: "group_code TEXT NOT NULL DEFAULT ''" },
  { version: 24, name: "conversations_message_ttl_ms", table: "conversations", column: "message_ttl_ms", definition: "message_ttl_ms INTEGER NOT NULL DEFAULT 0" },
  { version: 25, name: "conversation_members_role", table: "conversation_members", column: "role", definition: "role TEXT NOT NULL DEFAULT 'member'" },
  { version: 26, name: "conversation_members_joined_at", table: "conversation_members", column: "joined_at", definition: "joined_at INTEGER NOT NULL DEFAULT 0" },
  { version: 27, name: "manage_admins_totp_secret", table: "manage_admins", column: "totp_secret", definition: "totp_secret TEXT NOT NULL DEFAULT ''" },
  { version: 28, name: "manage_admins_totp_pending_secret", table: "manage_admins", column: "totp_pending_secret", definition: "totp_pending_secret TEXT NOT NULL DEFAULT ''" },
  { version: 29, name: "manage_admins_totp_enabled", table: "manage_admins", column: "totp_enabled", definition: "totp_enabled INTEGER NOT NULL DEFAULT 0" },
  { version: 30, name: "app_release_state_sha256", table: "app_release_state", column: "sha256", definition: "sha256 TEXT NOT NULL DEFAULT ''" },
  { version: 31, name: "app_release_channels_sha256", table: "app_release_channels", column: "sha256", definition: "sha256 TEXT NOT NULL DEFAULT ''" },
  { version: 32, name: "messages_client_message_id", table: "messages", column: "client_message_id", definition: "client_message_id TEXT NOT NULL DEFAULT ''" },
  { version: 33, name: "messages_sender_device_id", table: "messages", column: "sender_device_id", definition: "sender_device_id TEXT NOT NULL DEFAULT ''" },
  { version: 34, name: "fcm_tokens_blog_notifications_enabled", table: "fcm_tokens", column: "blog_notifications_enabled", definition: "blog_notifications_enabled INTEGER NOT NULL DEFAULT 1" },
]);

db.exec(
  "CREATE UNIQUE INDEX IF NOT EXISTS messages_idempotency_idx " +
  "ON messages(user_id, sender_device_id, client_message_id) WHERE client_message_id <> '';"
);

const legacyStableRelease = db
  .prepare("SELECT version, file_name, original_name, file_size, uploaded_at FROM app_release_state WHERE id=1")
  .get();
if (legacyStableRelease?.version && legacyStableRelease?.file_name) {
  db.prepare(
    `
      INSERT INTO app_release_channels (channel, version, file_name, original_name, file_size, uploaded_at)
      VALUES (?, ?, ?, ?, ?, ?)
      ON CONFLICT(channel) DO NOTHING
    `
  ).run(
    RELEASE_CHANNEL_RELEASE,
    legacyStableRelease.version,
    legacyStableRelease.file_name,
    legacyStableRelease.original_name || legacyStableRelease.file_name,
    Number(legacyStableRelease.file_size || 0),
    Number(legacyStableRelease.uploaded_at || 0)
  );
}

for (const [legacyChannel, canonicalChannel] of [
  ["stable", RELEASE_CHANNEL_RELEASE],
  ["prerelease", RELEASE_CHANNEL_BETA],
]) {
  const legacyRelease = db
    .prepare("SELECT version, file_name, original_name, file_size, uploaded_at FROM app_release_channels WHERE channel=?")
    .get(legacyChannel);
  if (legacyRelease?.version && legacyRelease?.file_name) {
    db.prepare(
      `
        INSERT INTO app_release_channels (channel, version, file_name, original_name, file_size, uploaded_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(channel) DO NOTHING
      `
    ).run(
      canonicalChannel,
      legacyRelease.version,
      legacyRelease.file_name,
      legacyRelease.original_name || legacyRelease.file_name,
      Number(legacyRelease.file_size || 0),
      Number(legacyRelease.uploaded_at || 0)
    );
  }
}

db.exec("CREATE UNIQUE INDEX IF NOT EXISTS conversations_group_code_idx ON conversations(group_code) WHERE group_code <> '';");
}

module.exports = { initializeSchema };
