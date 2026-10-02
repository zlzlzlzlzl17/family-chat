const crypto = require("crypto");

const INSECURE_JWT_VALUES = new Set([
  "CHANGE_THIS_TO_A_LONG_RANDOM_SECRET",
  "change-me",
  "secret",
]);

function envBoolean(value) {
  return ["1", "true", "yes", "on"].includes(String(value || "").trim().toLowerCase());
}

function loadRuntimeConfig(env = process.env) {
  const nodeEnv = String(env.NODE_ENV || "development").trim().toLowerCase();
  const production = nodeEnv === "production";
  let jwtSecret = String(env.JWT_SECRET || "");

  if (production && (jwtSecret.length < 32 || INSECURE_JWT_VALUES.has(jwtSecret))) {
    throw new Error("JWT_SECRET must be a unique secret of at least 32 characters in production");
  }
  if (!jwtSecret) {
    jwtSecret = crypto.randomBytes(32).toString("base64url");
    console.warn("[security] JWT_SECRET is not set; using an ephemeral development secret");
  }

  const seedDemoUsers = envBoolean(env.SEED_DEMO_USERS);
  if (production && seedDemoUsers) {
    throw new Error("SEED_DEMO_USERS must not be enabled in production");
  }

  const bootstrapManageUsername = String(env.MANAGE_BOOTSTRAP_USERNAME || "").trim();
  const bootstrapManagePassword = String(env.MANAGE_BOOTSTRAP_PASSWORD || "");
  if ((bootstrapManageUsername && !bootstrapManagePassword) || (!bootstrapManageUsername && bootstrapManagePassword)) {
    throw new Error("MANAGE_BOOTSTRAP_USERNAME and MANAGE_BOOTSTRAP_PASSWORD must be set together");
  }
  if (bootstrapManageUsername && bootstrapManagePassword.length < 12) {
    throw new Error("MANAGE_BOOTSTRAP_PASSWORD must contain at least 12 characters");
  }

  const trustProxyHops = Number.parseInt(String(env.TRUST_PROXY_HOPS || "1"), 10);
  if (!Number.isInteger(trustProxyHops) || trustProxyHops < 0 || trustProxyHops > 10) {
    throw new Error("TRUST_PROXY_HOPS must be an integer between 0 and 10");
  }

  return Object.freeze({
    nodeEnv,
    production,
    jwtSecret,
    host: String(env.HOST || "127.0.0.1").trim() || "127.0.0.1",
    trustProxyHops,
    seedDemoUsers,
    bootstrapManageUsername,
    bootstrapManagePassword,
  });
}

module.exports = { loadRuntimeConfig };
