function assertIdentifier(value, label) {
  if (!/^[a-z][a-z0-9_]*$/i.test(value)) {
    throw new Error(`Invalid ${label}: ${value}`);
  }
}

function tableColumns(db, table) {
  assertIdentifier(table, "table name");
  return new Set(db.pragma(`table_info(${table})`).map((row) => String(row.name)));
}

function ensureMigrationTable(db) {
  db.exec(`
    CREATE TABLE IF NOT EXISTS schema_migrations (
      version INTEGER PRIMARY KEY,
      name TEXT NOT NULL UNIQUE,
      applied_at INTEGER NOT NULL
    )
  `);
}

function runColumnMigrations(db, migrations) {
  ensureMigrationTable(db);
  const applied = new Set(
    db.prepare("SELECT version FROM schema_migrations").all().map((row) => Number(row.version))
  );
  const insert = db.prepare(
    "INSERT INTO schema_migrations (version, name, applied_at) VALUES (?, ?, ?)"
  );

  for (const migration of migrations) {
    const { version, name, table, column, definition, afterSql = "" } = migration;
    if (!Number.isInteger(version) || version <= 0) throw new Error(`Invalid migration version: ${version}`);
    assertIdentifier(table, "table name");
    assertIdentifier(column, "column name");
    if (!String(definition).trim().toUpperCase().startsWith(column.toUpperCase())) {
      throw new Error(`Migration ${version} definition must start with ${column}`);
    }

    const columns = tableColumns(db, table);
    if (applied.has(version)) {
      if (!columns.has(column)) throw new Error(`Applied migration ${version} is missing ${table}.${column}`);
      continue;
    }

    db.transaction(() => {
      if (!columns.has(column)) {
        db.exec(`ALTER TABLE ${table} ADD COLUMN ${definition};`);
      }
      if (afterSql) db.exec(afterSql);
      insert.run(version, name, Date.now());
    })();
    applied.add(version);
  }
}

module.exports = { runColumnMigrations };
