"use strict";

// Syntax-checks every server-side JavaScript file, not just the entry point.
//
// `node --check server.js` parses server.js alone - it never opens lib/,
// routes/, services/ and the rest. A broken module therefore passed the deploy
// gate cleanly and only surfaced at `systemctl restart`. This script closes
// that gap, and additionally fails when a required module directory is missing
// entirely, which is exactly what an incomplete deploy looks like.

const { execFileSync } = require("child_process");
const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");

// Every directory the modular server requires at startup.
const REQUIRED_MODULE_DIRS = [
  "lib",
  "database",
  "repositories",
  "routes",
  "services",
  "websocket",
];

const ENTRY_FILES = ["server.js"];

function collectJsFiles(dir) {
  const found = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      found.push(...collectJsFiles(full));
    } else if (entry.isFile() && entry.name.endsWith(".js")) {
      found.push(full);
    }
  }
  return found;
}

const files = [];
const missing = [];

for (const name of ENTRY_FILES) {
  const full = path.join(root, name);
  if (!fs.existsSync(full)) {
    missing.push(name);
  } else {
    files.push(full);
  }
}

for (const name of REQUIRED_MODULE_DIRS) {
  const full = path.join(root, name);
  if (!fs.existsSync(full) || !fs.statSync(full).isDirectory()) {
    missing.push(`${name}/`);
    continue;
  }
  files.push(...collectJsFiles(full));
}

if (missing.length > 0) {
  console.error("incomplete server tree, missing required paths:");
  for (const name of missing) {
    console.error(`  - ${name}`);
  }
  console.error("the modular server cannot start without these");
  process.exit(1);
}

let failures = 0;
for (const file of files) {
  try {
    execFileSync(process.execPath, ["--check", file], { stdio: "pipe" });
  } catch (error) {
    failures += 1;
    console.error(`syntax error: ${path.relative(root, file).replace(/\\/g, "/")}`);
    console.error(String(error.stderr || error.message).trim());
  }
}

if (failures > 0) {
  console.error(`syntax check failed for ${failures} file(s)`);
  process.exit(1);
}

console.log(`syntax ok: ${files.length} server files checked`);
