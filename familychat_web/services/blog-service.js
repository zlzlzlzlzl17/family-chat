"use strict";

function createBlogService({ repository, fs, path, blogUploadDir, atRest, logger }) {
  const supportedImageTypes = new Set([
    "image/jpeg",
    "image/png",
    "image/webp",
    "image/gif",
    "image/heic",
    "image/heif",
  ]);
  const supportedVideoTypes = new Set([
    "video/mp4",
    "video/webm",
    "video/quicktime",
    "video/3gpp",
  ]);
  function clampLimit(value, fallback, max) {
    const parsed = Number.parseInt(String(value || ""), 10);
    return Number.isFinite(parsed) ? Math.min(Math.max(parsed, 1), max) : fallback;
  }

  function parseCursor(value) {
    const parsed = Number.parseInt(String(value || ""), 10);
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
  }

  function postToWire(post, mediaUrlPrefix = "/api/blog/media") {
    return {
      id: Number(post.id),
      body: String(post.body || ""),
      created_at: Number(post.created_at),
      updated_at: Number(post.updated_at),
      author: {
        user_code: String(post.author_user_code || ""),
        username: String(post.author_username || ""),
        color: String(post.author_color || ""),
        avatar_url: String(post.author_avatar_url || ""),
      },
      // Carried on the wire rather than inferred from the author's display
      // name, which is editable and would make the badge a string match.
      is_announcement: post.author_status === "system",
      like_count: Number(post.like_count || 0),
      comment_count: Number(post.comment_count || 0),
      liked_by_me: !!post.liked_by_me,
      media: (post.media || []).map((item) => ({
        id: Number(item.id),
        kind: String(item.media_kind),
        mime_type: String(item.mime_type),
        original_name: String(item.original_name || ""),
        file_size: Number(item.file_size || 0),
        width: Number(item.width || 0),
        height: Number(item.height || 0),
        duration_ms: Number(item.duration_ms || 0),
        url: `${mediaUrlPrefix}/${item.id}`,
      })),
    };
  }

  function commentToWire(comment) {
    return {
      id: Number(comment.id),
      post_id: Number(comment.post_id),
      body: String(comment.body || ""),
      created_at: Number(comment.created_at),
      updated_at: Number(comment.updated_at),
      author: {
        user_code: String(comment.author_user_code || ""),
        username: String(comment.author_username || ""),
        color: String(comment.author_color || ""),
        avatar_url: String(comment.author_avatar_url || ""),
      },
    };
  }

  function normalizeMedia(files, metadataText) {
    const metadata = (() => {
      try {
        const value = JSON.parse(String(metadataText || "[]"));
        return Array.isArray(value) ? value : [];
      } catch {
        return [];
      }
    })();
    let videoCount = 0;
    const totalBytes = files.reduce((sum, file) => sum + Number(file.size || 0), 0);
    if (totalBytes > 120 * 1024 * 1024) throw new Error("blog_media_total_too_large");
    return files.map((file, index) => {
      const mimeType = String(file.mimetype || "application/octet-stream").toLowerCase();
      const mediaKind = supportedVideoTypes.has(mimeType)
        ? "video"
        : supportedImageTypes.has(mimeType) ? "image" : "";
      if (!mediaKind) throw new Error("unsupported_blog_media");
      if (mediaKind === "video" && ++videoCount > 1) throw new Error("too_many_blog_videos");
      const item = metadata[index] || {};
      return {
        mediaKind,
        mimeType,
        originalName: String(file.originalname || "media").slice(0, 180),
        storageName: file.filename,
        fileSize: Number(file.size || 0),
        width: Math.max(0, Number.parseInt(item.width, 10) || 0),
        height: Math.max(0, Number.parseInt(item.height, 10) || 0),
        durationMs: Math.max(0, Number.parseInt(item.duration_ms, 10) || 0),
      };
    });
  }

  function encryptUploadedMedia(mediaItems) {
    if (!atRest) throw new Error("blog_encryption_unavailable");
    for (const item of mediaItems) {
      const storageName = path.basename(String(item.storageName || ""));
      if (!storageName) throw new Error("blog_media_missing");
      atRest.encryptFileInPlace(path.join(blogUploadDir, storageName), storageName);
    }
    return mediaItems;
  }

  function deleteFiles(files) {
    for (const item of files) {
      const storageName = path.basename(String(item.storage_name || item.filename || ""));
      if (!storageName) continue;
      try {
        fs.unlinkSync(path.join(blogUploadDir, storageName));
      } catch {}
    }
  }

  function cleanupOrphanFiles() {
    const referenced = repository.listStorageNames();
    let removed = 0;
    for (const name of fs.readdirSync(blogUploadDir)) {
      if (referenced.has(name)) continue;
      const target = path.join(blogUploadDir, name);
      let stat;
      try {
        stat = fs.statSync(target);
      } catch {
        continue;
      }
      if (!stat.isFile() || Date.now() - stat.mtimeMs < 60 * 60 * 1000) continue;
      try {
        fs.unlinkSync(target);
        removed += 1;
      } catch {}
    }
    return removed;
  }

  function migrateAtRest() {
    if (!atRest) throw new Error("blog_encryption_unavailable");
    const text = repository.migrateTextEncryption();
    const media = atRest.migrateMediaFiles(repository.listMediaRecords(), blogUploadDir);
    logger?.info?.("at_rest_migration_complete", {
      postsEncrypted: text.postsEncrypted,
      commentsEncrypted: text.commentsEncrypted,
      mediaEncrypted: media.encrypted,
      mediaMissing: media.missing,
    });
    return { ...text, ...media };
  }

  async function sendMedia(req, res, media) {
    if (!atRest) throw new Error("blog_encryption_unavailable");
    const storageName = path.basename(String(media.storage_name || ""));
    if (!storageName) throw new Error("blog_media_missing");
    const filePath = path.join(blogUploadDir, storageName);
    if (!fs.existsSync(filePath)) throw new Error("blog_media_missing");
    await atRest.sendEncryptedMedia(req, res, filePath, media);
  }

  return {
    clampLimit,
    parseCursor,
    postToWire,
    commentToWire,
    normalizeMedia,
    encryptUploadedMedia,
    deleteFiles,
    cleanupOrphanFiles,
    migrateAtRest,
    sendMedia,
  };
}

module.exports = { createBlogService };
