"use strict";

const SECRET_KEY_PATTERN = /(authorization|cookie|credential|password|private|refresh|secret|token)/i;
const LARGE_PAYLOAD_KEY_PATTERN = /(candidate|cipher|description|payload|sdp)/i;

function sanitizeValue(key, value, depth = 0) {
  if (SECRET_KEY_PATTERN.test(key)) return "[redacted]";
  if (value === null || value === undefined) return value;
  if (depth >= 3) return "[truncated]";
  if (Array.isArray(value)) return value.slice(0, 20).map((item) => sanitizeValue("item", item, depth + 1));
  if (typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value)
        .slice(0, 40)
        .map(([childKey, childValue]) => [childKey, sanitizeValue(childKey, childValue, depth + 1)])
    );
  }
  const text = typeof value === "string" ? value : null;
  if (text === null) return value;
  if (LARGE_PAYLOAD_KEY_PATTERN.test(key)) return `[omitted:${Buffer.byteLength(text, "utf8")}]`;
  return text.replace(/[\r\n]+/g, " ").slice(0, 500);
}

function sanitizeDetails(details = {}) {
  return Object.fromEntries(
    Object.entries(details).map(([key, value]) => [key, sanitizeValue(key, value)])
  );
}

function createLogger(scope, sink = console) {
  function write(level, event, details = {}) {
    const record = {
      ts: new Date().toISOString(),
      level,
      scope,
      event,
      ...sanitizeDetails(details),
    };
    const method = level === "error" ? "error" : level === "warn" ? "warn" : "log";
    sink[method](JSON.stringify(record));
  }

  return {
    info: (event, details) => write("info", event, details),
    log: (event, details) => write("info", event, details),
    warn: (event, details) => write("warn", event, details),
    error: (event, details) => write("error", event, details),
  };
}

module.exports = { createLogger, sanitizeDetails };
