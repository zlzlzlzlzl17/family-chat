"use strict";

function createReleaseService({ db, fs, path, crypto, appReleaseDir, releaseChannelRelease, releaseChannelBeta, releaseChannelAliases, releaseChannelValues }) {
  const APP_RELEASE_DIR = appReleaseDir;
  const RELEASE_CHANNEL_RELEASE = releaseChannelRelease;
  const RELEASE_CHANNEL_BETA = releaseChannelBeta;
  const RELEASE_CHANNEL_ALIASES = releaseChannelAliases;
  const RELEASE_CHANNEL_VALUES = releaseChannelValues;

function isValidReleaseVersion(version) {
  return /^v\d+\.\d+\.\d+$/.test(String(version || "").trim());
}

function extractReleaseVersion(value) {
  const match = String(value || "").match(/v\d+\.\d+\.\d+/i);
  return match ? match[0].toLowerCase().replace(/^v/, "v") : "";
}

function normalizeReleaseChannel(value, fallback = RELEASE_CHANNEL_RELEASE) {
  const raw = String(value || "").trim().toLowerCase();
  if (RELEASE_CHANNEL_ALIASES.has(raw)) return RELEASE_CHANNEL_ALIASES.get(raw);
  if (RELEASE_CHANNEL_VALUES.has(raw)) return raw;
  return RELEASE_CHANNEL_ALIASES.get(String(fallback || "").trim().toLowerCase()) || fallback || RELEASE_CHANNEL_RELEASE;
}

function inferReleaseChannel(value) {
  return /(?:^|[^a-z])pre(?:-|\s*)?release(?:[^a-z]|$)|(?:^|[^a-z])prerelease(?:[^a-z]|$)|(?:^|[^a-z])beta(?:[^a-z]|$)|[\(（](?:pre|beta)[\)）]/i.test(String(value || ""))
    ? RELEASE_CHANNEL_BETA
    : RELEASE_CHANNEL_RELEASE;
}

function releaseVersionLabel(channel, version) {
  if (!version) return "";
  const releaseChannel = normalizeReleaseChannel(channel);
  return releaseChannel === RELEASE_CHANNEL_BETA && !/\(beta\)$/i.test(version) ? `${version}(beta)` : version;
}

function buildReleaseFileName(channel, version) {
  return normalizeReleaseChannel(channel) === RELEASE_CHANNEL_BETA
    ? `familychat_${version}(beta).apk`
    : `familychat_${version}.apk`;
}

function releaseFileSha256(fileName) {
  if (!fileName) return "";
  const filePath = path.join(APP_RELEASE_DIR, fileName);
  if (!fs.existsSync(filePath)) return "";
  return crypto.createHash("sha256").update(fs.readFileSync(filePath)).digest("hex");
}

function getAppReleaseState(channel = RELEASE_CHANNEL_RELEASE) {
  const releaseChannel = normalizeReleaseChannel(channel);
  let row = db
    .prepare("SELECT version, file_name, original_name, file_size, sha256, uploaded_at FROM app_release_channels WHERE channel=?")
    .get(releaseChannel);
  if (!row || !row.version || !row.file_name) return null;
  if (!row.sha256) {
    const sha256 = releaseFileSha256(row.file_name);
    if (sha256) {
      db.prepare("UPDATE app_release_channels SET sha256=? WHERE channel=?").run(sha256, releaseChannel);
      if (releaseChannel === RELEASE_CHANNEL_RELEASE) {
        db.prepare("UPDATE app_release_state SET sha256=? WHERE id=1").run(sha256);
      }
      row = { ...row, sha256 };
    }
  }
  return {
    channel: releaseChannel,
    version: row.version,
    version_label: releaseVersionLabel(releaseChannel, row.version),
    file_name: row.file_name,
    original_name: row.original_name || row.file_name,
    file_size: Number(row.file_size || 0),
    sha256: String(row.sha256 || ""),
    uploaded_at: Number(row.uploaded_at || 0),
    download_url: `/downloads/${encodeURIComponent(row.file_name)}`,
  };
}

function getAllAppReleaseStates() {
  const release = getAppReleaseState(RELEASE_CHANNEL_RELEASE);
  const beta = getAppReleaseState(RELEASE_CHANNEL_BETA);
  return {
    release,
    beta,
    stable: release,
    prerelease: beta,
  };
}

function manageAdminToPublic(admin) {
  return {
    username: admin.username,
    totp_enabled: !!admin.totp_enabled,
  };
}

function clearAppReleaseFile(fileName) {
  if (!fileName) return;
  try {
    fs.unlinkSync(path.join(APP_RELEASE_DIR, fileName));
  } catch {}
}

function storeAppRelease(channel, version, originalName, fileBuffer) {
  const releaseChannel = normalizeReleaseChannel(channel);
  const previous = getAppReleaseState(releaseChannel);
  const fileName = buildReleaseFileName(releaseChannel, version);
  const targetPath = path.join(APP_RELEASE_DIR, fileName);
  const uploadedAt = Date.now();
  const sha256 = crypto.createHash("sha256").update(fileBuffer).digest("hex");
  fs.writeFileSync(targetPath, fileBuffer);
  db.prepare(
    `
      INSERT INTO app_release_channels (channel, version, file_name, original_name, file_size, sha256, uploaded_at)
      VALUES (?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT(channel) DO UPDATE SET
        version = excluded.version,
        file_name = excluded.file_name,
        original_name = excluded.original_name,
        file_size = excluded.file_size,
        sha256 = excluded.sha256,
        uploaded_at = excluded.uploaded_at
    `
  ).run(releaseChannel, version, fileName, originalName || fileName, fileBuffer.length, sha256, uploadedAt);
  if (releaseChannel === RELEASE_CHANNEL_RELEASE) {
    db.prepare(
      `
        INSERT INTO app_release_state (id, version, file_name, original_name, file_size, sha256, uploaded_at)
        VALUES (1, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET
          version = excluded.version,
          file_name = excluded.file_name,
          original_name = excluded.original_name,
          file_size = excluded.file_size,
          sha256 = excluded.sha256,
          uploaded_at = excluded.uploaded_at
      `
    ).run(version, fileName, originalName || fileName, fileBuffer.length, sha256, uploadedAt);
  }
  if (previous?.file_name && previous.file_name !== fileName) {
    clearAppReleaseFile(previous.file_name);
  }
  return getAppReleaseState(releaseChannel);
}

  return {
    isValidReleaseVersion, extractReleaseVersion, normalizeReleaseChannel, inferReleaseChannel,
    getAppReleaseState, getAllAppReleaseStates, manageAdminToPublic, storeAppRelease,
  };
}

module.exports = { createReleaseService };
