"use strict";

const assert = require("assert");
const Database = require("better-sqlite3");
const { createFcmTokenRepository } = require("../repositories/fcm-token-repository");
const { createConversationRepository } = require("../repositories/conversation-repository");
const { createFcmService } = require("../services/fcm-service");
const { registerSystemRoutes } = require("../routes/system-routes");
const { registerManageRoutes } = require("../routes/manage-routes");
const { registerPublicAuthRoutes, registerAuthDeviceRoutes } = require("../routes/auth-device-routes");
const { registerConversationRoutes } = require("../routes/conversation-routes");
const { registerRelationshipRoutes } = require("../routes/relationship-routes");
const { registerMessageAttachmentRoutes } = require("../routes/message-attachment-routes");
const { createSocketHub } = require("../websocket/socket-hub");

function testFcmRepository() {
  const db = new Database(":memory:");
  db.exec(`
    CREATE TABLE messages (
      id INTEGER PRIMARY KEY,
      conversation_id INTEGER NOT NULL,
      user_id INTEGER NOT NULL,
      kind TEXT NOT NULL
    );
    CREATE TABLE conversation_members (conversation_id INTEGER NOT NULL, user_id INTEGER NOT NULL);
    CREATE TABLE conversation_read_states (
      conversation_id INTEGER NOT NULL,
      user_id INTEGER NOT NULL,
      last_read_message_id INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE devices (
      user_id INTEGER NOT NULL,
      device_id TEXT NOT NULL,
      status TEXT NOT NULL
    );
    CREATE TABLE fcm_tokens (
      token TEXT PRIMARY KEY,
      user_id INTEGER NOT NULL,
      device_id TEXT NOT NULL,
      locale TEXT NOT NULL,
      last_success_at INTEGER NOT NULL DEFAULT 0,
      last_failure_at INTEGER NOT NULL DEFAULT 0,
      failure_count INTEGER NOT NULL DEFAULT 0,
      last_error TEXT NOT NULL DEFAULT ''
    );
  `);
  db.prepare("INSERT INTO conversation_members VALUES (?, ?)").run(7, 2);
  db.prepare("INSERT INTO devices VALUES (?, ?, ?)").run(2, "trusted-device", "trusted");
  db.prepare("INSERT INTO devices VALUES (?, ?, ?)").run(2, "pending-device", "pending");
  db.prepare("INSERT INTO fcm_tokens (token, user_id, device_id, locale) VALUES (?, ?, ?, ?)")
    .run("trusted-token", 2, "trusted-device", "en-US");
  db.prepare("INSERT INTO fcm_tokens (token, user_id, device_id, locale) VALUES (?, ?, ?, ?)")
    .run("pending-token", 2, "pending-device", "en-US");
  db.prepare("INSERT INTO messages VALUES (?, ?, ?, ?)").run(10, 7, 1, "text");

  const repository = createFcmTokenRepository(db);
  assert.deepStrictEqual(repository.listConversationTokens(7, 1).map((row) => row.token), ["trusted-token"]);
  assert.strictEqual(repository.countUnreadMessagesForUser(2), 1);
  repository.markFailure("trusted-token", "temporary");
  assert.strictEqual(db.prepare("SELECT failure_count FROM fcm_tokens WHERE token=?").get("trusted-token").failure_count, 1);
  repository.markSuccess("trusted-token");
  assert.strictEqual(db.prepare("SELECT failure_count FROM fcm_tokens WHERE token=?").get("trusted-token").failure_count, 0);
  db.close();
}

function testConversationReadReceipts() {
  const db = new Database(":memory:");
  db.exec(`
    CREATE TABLE conversations (
      id INTEGER PRIMARY KEY,
      slug TEXT NOT NULL UNIQUE
    );
    CREATE TABLE conversation_read_states (
      conversation_id INTEGER NOT NULL,
      user_id INTEGER NOT NULL,
      last_read_message_id INTEGER NOT NULL DEFAULT 0,
      updated_at INTEGER NOT NULL,
      PRIMARY KEY (conversation_id, user_id)
    );
    CREATE TABLE read_states (
      user_id INTEGER PRIMARY KEY,
      last_read_message_id INTEGER NOT NULL DEFAULT 0,
      updated_at INTEGER NOT NULL
    );
  `);
  db.prepare("INSERT INTO conversations (id, slug) VALUES (?, ?)").run(1, "familychat");
  db.prepare("INSERT INTO conversations (id, slug) VALUES (?, ?)").run(16, "direct:1:5");

  const repository = createConversationRepository(db);
  repository.upsertConversationReadState(16, 5, 1054);
  assert.strictEqual(
    db.prepare("SELECT last_read_message_id FROM conversation_read_states WHERE conversation_id=? AND user_id=?")
      .get(16, 5).last_read_message_id,
    1054,
  );
  assert.strictEqual(db.prepare("SELECT COUNT(*) AS count FROM read_states").get().count, 0);

  repository.upsertConversationReadState(1, 5, 1055);
  assert.strictEqual(db.prepare("SELECT last_read_message_id FROM read_states WHERE user_id=?").get(5).last_read_message_id, 1055);
  repository.upsertConversationReadState(1, 5, 1000);
  assert.strictEqual(db.prepare("SELECT last_read_message_id FROM read_states WHERE user_id=?").get(5).last_read_message_id, 1055);
  db.close();
}

async function testFcmServiceWithoutCredentials() {
  const repository = {
    listUserTokens: () => [],
    listConversationTokens: () => [],
    countUnreadMessagesForUser: () => 0,
    markSuccess() {},
    markFailure() {},
    remove() {},
  };
  const service = createFcmService({
    projectId: "",
    clientEmail: "",
    privateKey: "",
    tokenRepository: repository,
    logger: { info() {}, warn() {}, error() {} },
  });
  assert.strictEqual(service.hasConfig(), false);
  const result = await service.sendIncomingCallToUser(2, { conversationId: 7 });
  assert.deepStrictEqual(result, { delivered: false, reason: "missing_fcm_config" });
}

function testSocketHub() {
  const WebSocket = { OPEN: 1 };
  const hub = createSocketHub({
    WebSocket,
    isConversationMember: (userId, conversationId) => userId === 2 && conversationId === 7,
    logger: { log() {}, warn() {} },
  });
  const sent = [];
  hub.clients.add({ readyState: 1, auth: { uid: 2 }, send: (value) => sent.push(JSON.parse(value)) });
  hub.clients.add({ readyState: 1, auth: { uid: 3 }, send: (value) => sent.push(JSON.parse(value)) });
  assert.strictEqual(hub.broadcastToConversation(7, { type: "chat" }), 1);
  assert.deepStrictEqual(sent, [{ type: "chat", conversation_id: 7 }]);
}

function testSystemRoutes() {
  const routes = new Map();
  const app = {
    get(path, ...handlers) {
      routes.set(path, handlers);
    },
    post(path, ...handlers) {
      routes.set(path, handlers);
    },
  };
  const authMiddleware = (_req, _res, next) => next();
  registerSystemRoutes({
    app,
    authMiddleware,
    vapidPublicKey: "test-key",
    defaultReleaseChannel: "release",
    normalizeReleaseChannel: (value, fallback) => value || fallback,
    getAppReleaseState: (channel) => ({ channel }),
    getCallIceServers: () => [{ urls: ["stun:test"] }],
    sendPushHealthCheck: async () => ({ delivered: true, tokenCount: 1 }),
    logger: { info() {}, warn() {} },
  });
  assert.deepStrictEqual(
    [...routes.keys()],
    ["/api/push_public_key", "/api/app_release", "/api/call_config", "/api/push_self_test"],
  );
  const response = { body: null, json(value) { this.body = value; } };
  routes.get("/api/push_public_key").at(-1)({}, response);
  assert.deepStrictEqual(response.body, { publicKey: "test-key" });
}

function testRouteModuleExports() {
  const registrars = [
    registerManageRoutes,
    registerPublicAuthRoutes,
    registerAuthDeviceRoutes,
    registerConversationRoutes,
    registerRelationshipRoutes,
    registerMessageAttachmentRoutes,
  ];
  assert.ok(registrars.every((registrar) => typeof registrar === "function"));
}

async function main() {
  testFcmRepository();
  testConversationReadReceipts();
  await testFcmServiceWithoutCredentials();
  testSocketHub();
  testSystemRoutes();
  testRouteModuleExports();
  console.log("architecture smoke test passed");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
