"use strict";

const crypto = require("crypto");

const TEXT_PREFIX = "fcblog:v1:";
const MEDIA_MAGIC = Buffer.from("FCBLOGM1", "ascii");
const MEDIA_HEADER_SIZE = 32;
const MEDIA_TAG_SIZE = 16;
const DEFAULT_CHUNK_SIZE = 1024 * 1024;

function parseKey(keyText) {
  const value = String(keyText || "").trim();
  if (!value) throw new Error("BLOG_AT_REST_KEY is required when Blog is enabled");
  const decoded = /^[0-9a-fA-F]{64}$/.test(value)
    ? Buffer.from(value, "hex")
    : Buffer.from(value, "base64");
  if (decoded.length !== 32) {
    throw new Error("BLOG_AT_REST_KEY must be exactly 32 bytes (64 hex characters or base64)");
  }
  return decoded;
}

function parseRange(rangeHeader, total) {
  const value = String(rangeHeader || "").trim();
  if (!value) return null;
  const match = /^bytes=(\d*)-(\d*)$/.exec(value);
  if (!match || (!match[1] && !match[2]) || total <= 0) return false;
  if (!match[1]) {
    const suffixLength = Number.parseInt(match[2], 10);
    if (!Number.isSafeInteger(suffixLength) || suffixLength <= 0) return false;
    return { start: Math.max(0, total - suffixLength), end: total - 1 };
  }
  const start = Number.parseInt(match[1], 10);
  const requestedEnd = match[2] ? Number.parseInt(match[2], 10) : total - 1;
  if (!Number.isSafeInteger(start) || !Number.isSafeInteger(requestedEnd) || start < 0 || start >= total) {
    return false;
  }
  const end = Math.min(requestedEnd, total - 1);
  return end < start ? false : { start, end };
}

function createBlogAtRestService({ keyText, fs, path, chunkSize = DEFAULT_CHUNK_SIZE }) {
  const key = parseKey(keyText);
  const safeChunkSize = Math.max(64 * 1024, Math.min(Number(chunkSize) || DEFAULT_CHUNK_SIZE, 4 * 1024 * 1024));

  function encryptText(plaintext, aad) {
    const value = String(plaintext || "");
    if (!value) return "";
    const nonce = crypto.randomBytes(12);
    const cipher = crypto.createCipheriv("aes-256-gcm", key, nonce);
    cipher.setAAD(Buffer.from(String(aad), "utf8"));
    const ciphertext = Buffer.concat([cipher.update(value, "utf8"), cipher.final()]);
    return `${TEXT_PREFIX}${Buffer.concat([nonce, cipher.getAuthTag(), ciphertext]).toString("base64url")}`;
  }

  function isEncryptedText(value) {
    return String(value || "").startsWith(TEXT_PREFIX);
  }

  function decryptText(value, aad) {
    const stored = String(value || "");
    if (!stored) return "";
    if (!isEncryptedText(stored)) return stored;
    const payload = Buffer.from(stored.slice(TEXT_PREFIX.length), "base64url");
    if (payload.length < 29) throw new Error("blog_text_ciphertext_invalid");
    const nonce = payload.subarray(0, 12);
    const tag = payload.subarray(12, 28);
    const decipher = crypto.createDecipheriv("aes-256-gcm", key, nonce);
    decipher.setAAD(Buffer.from(String(aad), "utf8"));
    decipher.setAuthTag(tag);
    return Buffer.concat([decipher.update(payload.subarray(28)), decipher.final()]).toString("utf8");
  }

  function mediaAad(storageName, originalSize, chunkIndex) {
    return Buffer.from(`familychat-blog-media:v1:${storageName}:${originalSize}:${chunkIndex}`, "utf8");
  }

  function buildNonce(prefix, chunkIndex) {
    const nonce = Buffer.alloc(12);
    prefix.copy(nonce, 0);
    nonce.writeUInt32BE(chunkIndex, 8);
    return nonce;
  }

  function readHeader(filePath) {
    const fd = fs.openSync(filePath, "r");
    try {
      const header = Buffer.alloc(MEDIA_HEADER_SIZE);
      const bytesRead = fs.readSync(fd, header, 0, header.length, 0);
      if (bytesRead < MEDIA_MAGIC.length || !header.subarray(0, MEDIA_MAGIC.length).equals(MEDIA_MAGIC)) {
        return null;
      }
      if (bytesRead !== MEDIA_HEADER_SIZE) throw new Error("blog_media_header_invalid");
      const storedChunkSize = header.readUInt32BE(8);
      const originalSize = Number(header.readBigUInt64BE(12));
      if (!Number.isSafeInteger(originalSize) || originalSize < 0 || storedChunkSize < 64 * 1024) {
        throw new Error("blog_media_header_invalid");
      }
      return { chunkSize: storedChunkSize, originalSize, noncePrefix: Buffer.from(header.subarray(20, 28)) };
    } finally {
      fs.closeSync(fd);
    }
  }

  function isEncryptedFile(filePath) {
    try {
      return !!readHeader(filePath);
    } catch {
      return false;
    }
  }

  function encryptFileInPlace(filePath, storageName) {
    if (readHeader(filePath)) return false;
    const stat = fs.statSync(filePath);
    if (!stat.isFile()) throw new Error("blog_media_missing");
    const originalSize = stat.size;
    const noncePrefix = crypto.randomBytes(8);
    const header = Buffer.alloc(MEDIA_HEADER_SIZE);
    MEDIA_MAGIC.copy(header, 0);
    header.writeUInt32BE(safeChunkSize, 8);
    header.writeBigUInt64BE(BigInt(originalSize), 12);
    noncePrefix.copy(header, 20);
    const temporaryPath = `${filePath}.${process.pid}.${Date.now()}.encrypting`;
    const backupPath = `${filePath}.${process.pid}.${Date.now()}.plaintext-backup`;
    let sourceFd;
    let targetFd;
    try {
      sourceFd = fs.openSync(filePath, "r");
      targetFd = fs.openSync(temporaryPath, "wx", stat.mode);
      fs.writeSync(targetFd, header);
      const buffer = Buffer.alloc(safeChunkSize);
      let sourcePosition = 0;
      let chunkIndex = 0;
      while (sourcePosition < originalSize) {
        const length = Math.min(safeChunkSize, originalSize - sourcePosition);
        const bytesRead = fs.readSync(sourceFd, buffer, 0, length, sourcePosition);
        if (bytesRead !== length) throw new Error("blog_media_read_failed");
        const cipher = crypto.createCipheriv("aes-256-gcm", key, buildNonce(noncePrefix, chunkIndex));
        cipher.setAAD(mediaAad(storageName, originalSize, chunkIndex));
        const ciphertext = Buffer.concat([cipher.update(buffer.subarray(0, length)), cipher.final()]);
        fs.writeSync(targetFd, ciphertext);
        fs.writeSync(targetFd, cipher.getAuthTag());
        sourcePosition += length;
        chunkIndex += 1;
      }
      fs.fsyncSync(targetFd);
      fs.closeSync(sourceFd);
      sourceFd = undefined;
      fs.closeSync(targetFd);
      targetFd = undefined;
      fs.renameSync(filePath, backupPath);
      try {
        fs.renameSync(temporaryPath, filePath);
      } catch (error) {
        fs.renameSync(backupPath, filePath);
        throw error;
      }
      fs.unlinkSync(backupPath);
      return true;
    } finally {
      if (sourceFd !== undefined) fs.closeSync(sourceFd);
      if (targetFd !== undefined) fs.closeSync(targetFd);
      for (const leftover of [temporaryPath, backupPath]) {
        try {
          if (fs.existsSync(leftover)) fs.unlinkSync(leftover);
        } catch {}
      }
    }
  }

  async function readExactly(handle, length, position) {
    const buffer = Buffer.alloc(length);
    let offset = 0;
    while (offset < length) {
      const result = await handle.read(buffer, offset, length - offset, position + offset);
      if (!result.bytesRead) throw new Error("blog_media_read_failed");
      offset += result.bytesRead;
    }
    return buffer;
  }

  async function sendEncryptedMedia(req, res, filePath, media) {
    const parsedHeader = readHeader(filePath);
    if (!parsedHeader) throw new Error("blog_media_not_encrypted");
    const { chunkSize: storedChunkSize, originalSize, noncePrefix } = parsedHeader;
    const requestedRange = parseRange(req.headers.range, originalSize);
    if (requestedRange === false) {
      res.status(416);
      res.setHeader("Content-Range", `bytes */${originalSize}`);
      res.end();
      return;
    }
    const range = requestedRange || { start: 0, end: Math.max(0, originalSize - 1) };
    const responseLength = originalSize === 0 ? 0 : range.end - range.start + 1;
    res.status(requestedRange ? 206 : 200);
    res.setHeader("Content-Type", String(media.mime_type || "application/octet-stream"));
    res.setHeader("X-Content-Type-Options", "nosniff");
    res.setHeader("Accept-Ranges", "bytes");
    res.setHeader("Content-Disposition", "inline");
    res.setHeader("Content-Length", responseLength);
    if (requestedRange) res.setHeader("Content-Range", `bytes ${range.start}-${range.end}/${originalSize}`);
    if (originalSize === 0) {
      res.end();
      return;
    }

    const handle = await fs.promises.open(filePath, "r");
    try {
      const firstChunk = Math.floor(range.start / storedChunkSize);
      const lastChunk = Math.floor(range.end / storedChunkSize);
      for (let chunkIndex = firstChunk; chunkIndex <= lastChunk; chunkIndex += 1) {
        const plainStart = chunkIndex * storedChunkSize;
        const plainLength = Math.min(storedChunkSize, originalSize - plainStart);
        const encryptedOffset = MEDIA_HEADER_SIZE + chunkIndex * (storedChunkSize + MEDIA_TAG_SIZE);
        const payload = await readExactly(handle, plainLength + MEDIA_TAG_SIZE, encryptedOffset);
        const decipher = crypto.createDecipheriv("aes-256-gcm", key, buildNonce(noncePrefix, chunkIndex));
        decipher.setAAD(mediaAad(path.basename(String(media.storage_name)), originalSize, chunkIndex));
        decipher.setAuthTag(payload.subarray(plainLength));
        const plaintext = Buffer.concat([decipher.update(payload.subarray(0, plainLength)), decipher.final()]);
        const sliceStart = Math.max(range.start, plainStart) - plainStart;
        const sliceEnd = Math.min(range.end + 1, plainStart + plainLength) - plainStart;
        if (!res.write(plaintext.subarray(sliceStart, sliceEnd))) {
          await new Promise((resolve) => res.once("drain", resolve));
        }
      }
      res.end();
    } finally {
      await handle.close();
    }
  }

  function migrateMediaFiles(rows, blogUploadDir) {
    let encrypted = 0;
    let missing = 0;
    for (const row of rows) {
      const storageName = path.basename(String(row.storage_name || ""));
      if (!storageName) continue;
      const filePath = path.join(blogUploadDir, storageName);
      if (!fs.existsSync(filePath)) {
        missing += 1;
        continue;
      }
      if (encryptFileInPlace(filePath, storageName)) encrypted += 1;
    }
    return { encrypted, missing };
  }

  return {
    encryptText,
    decryptText,
    isEncryptedText,
    isEncryptedFile,
    encryptFileInPlace,
    migrateMediaFiles,
    sendEncryptedMedia,
  };
}

module.exports = {
  createBlogAtRestService,
  TEXT_PREFIX,
  MEDIA_MAGIC,
};
