function configureDatabase(db) {
  db.pragma("journal_mode = WAL");
  db.pragma("synchronous = NORMAL");
  db.pragma("busy_timeout = 5000");
  db.pragma("wal_autocheckpoint = 1000");
  db.pragma("foreign_keys = ON");

  if (db.pragma("foreign_keys", { simple: true }) !== 1) {
    throw new Error("SQLite foreign key enforcement could not be enabled");
  }
}

function reportForeignKeyViolations(db, logger = console) {
  const violations = db.pragma("foreign_key_check");
  if (violations.length === 0) return;
  logger.error("[database] existing foreign key violations detected", {
    count: violations.length,
    sample: violations.slice(0, 10),
  });
}

module.exports = { configureDatabase, reportForeignKeyViolations };
