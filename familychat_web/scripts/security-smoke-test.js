const assert = require("assert");
const Database = require("better-sqlite3");
const { loadRuntimeConfig } = require("../lib/runtime-config");
const { configureDatabase } = require("../lib/database");
const { runColumnMigrations } = require("../lib/schema-migrations");

function expectFailure(action, pattern) {
  assert.throws(action, pattern);
}

function testRuntimeConfig() {
  expectFailure(
    () => loadRuntimeConfig({ NODE_ENV: "production" }),
    /JWT_SECRET/
  );
  expectFailure(
    () => loadRuntimeConfig({ NODE_ENV: "production", JWT_SECRET: "x".repeat(64), SEED_DEMO_USERS: "true" }),
    /SEED_DEMO_USERS/
  );
  expectFailure(
    () => loadRuntimeConfig({ JWT_SECRET: "test", MANAGE_BOOTSTRAP_USERNAME: "admin" }),
    /must be set together/
  );
  expectFailure(
    () => loadRuntimeConfig({ JWT_SECRET: "test", MANAGE_BOOTSTRAP_USERNAME: "admin", MANAGE_BOOTSTRAP_PASSWORD: "short" }),
    /at least 12/
  );

  const production = loadRuntimeConfig({
    NODE_ENV: "production",
    JWT_SECRET: "production-test-secret-that-is-long-enough",
    TRUST_PROXY_HOPS: "1",
  });
  assert.equal(production.production, true);
  assert.equal(production.host, "127.0.0.1");
  assert.equal(production.trustProxyHops, 1);
}

function testDatabaseConfigurationAndMigrations() {
  const db = new Database(":memory:");
  try {
    configureDatabase(db);
    assert.equal(db.pragma("foreign_keys", { simple: true }), 1);
    assert.equal(db.pragma("busy_timeout", { simple: true }), 5000);
    assert.equal(db.pragma("synchronous", { simple: true }), 1);
    assert.equal(db.pragma("wal_autocheckpoint", { simple: true }), 1000);

    db.exec("CREATE TABLE test_records (id INTEGER PRIMARY KEY)");
    const migrations = [
      {
        version: 1,
        name: "test_records_label",
        table: "test_records",
        column: "label",
        definition: "label TEXT NOT NULL DEFAULT ''",
      },
    ];
    runColumnMigrations(db, migrations);
    runColumnMigrations(db, migrations);

    const columns = db.pragma("table_info(test_records)").map((row) => row.name);
    assert.ok(columns.includes("label"));
    assert.equal(db.prepare("SELECT COUNT(*) AS count FROM schema_migrations").get().count, 1);
  } finally {
    db.close();
  }
}

testRuntimeConfig();
testDatabaseConfigurationAndMigrations();
console.log("security smoke test passed");
