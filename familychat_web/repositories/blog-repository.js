"use strict";

function createBlogRepository(db, contentCrypto = null) {
  const postBaseSelect = `
    SELECT p.id, p.author_user_id, p.body, p.created_at, p.updated_at,
           u.user_code AS author_user_code, u.username AS author_username,
           u.color AS author_color, u.avatar_url AS author_avatar_url,
           u.account_status AS author_status,
           (SELECT COUNT(*) FROM blog_likes l WHERE l.post_id=p.id) AS like_count,
           (SELECT COUNT(*) FROM blog_comments c WHERE c.post_id=p.id) AS comment_count,
           EXISTS(SELECT 1 FROM blog_likes mine WHERE mine.post_id=p.id AND mine.user_id=?) AS liked_by_me
    FROM blog_posts p
    JOIN users u ON u.id=p.author_user_id
  `;

  const mediaForPost = db.prepare(
    `SELECT id, post_id, media_kind, mime_type, original_name, file_size,
            width, height, duration_ms, sort_order, created_at
     FROM blog_media WHERE post_id=? ORDER BY sort_order, id`
  );

  function requireCrypto() {
    if (!contentCrypto) throw new Error("blog_encryption_unavailable");
    return contentCrypto;
  }

  function postAad(postId) {
    return `familychat-blog-post:v1:${postId}`;
  }

  function commentAad(commentId) {
    return `familychat-blog-comment:v1:${commentId}`;
  }

  function hydratePosts(rows) {
    return rows.map((row) => ({
      ...row,
      body: requireCrypto().decryptText(row.body, postAad(row.id)),
      media: mediaForPost.all(row.id),
    }));
  }

  function hydrateComment(row) {
    return row ? {
      ...row,
      body: requireCrypto().decryptText(row.body, commentAad(row.id)),
    } : null;
  }

  function listPosts(userId, cursor, limit) {
    const hasCursor = Number.isSafeInteger(cursor) && cursor > 0;
    const sql = `${postBaseSelect}
      ${hasCursor ? "WHERE p.id < ?" : ""}
      ORDER BY p.id DESC
      LIMIT ?`;
    const rows = hasCursor
      ? db.prepare(sql).all(userId, cursor, limit)
      : db.prepare(sql).all(userId, limit);
    return hydratePosts(rows);
  }

  function getPost(postId, userId) {
    const row = db.prepare(`${postBaseSelect} WHERE p.id=?`).get(userId, postId);
    return row ? hydratePosts([row])[0] : null;
  }

  function createPost(authorUserId, body, mediaItems) {
    const now = Date.now();
    return db.transaction(() => {
      const postId = Number(
        db.prepare(
          "INSERT INTO blog_posts (author_user_id, body, created_at, updated_at) VALUES (?, '', ?, ?)"
        ).run(authorUserId, now, now).lastInsertRowid
      );
      const encryptedBody = requireCrypto().encryptText(body, postAad(postId));
      db.prepare("UPDATE blog_posts SET body=? WHERE id=?").run(encryptedBody, postId);
      const insertMedia = db.prepare(
        `INSERT INTO blog_media (
           post_id, media_kind, mime_type, original_name, storage_name, file_size,
           width, height, duration_ms, sort_order, created_at
         ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`
      );
      mediaItems.forEach((item, index) => {
        insertMedia.run(
          postId,
          item.mediaKind,
          item.mimeType,
          item.originalName,
          item.storageName,
          item.fileSize,
          item.width || 0,
          item.height || 0,
          item.durationMs || 0,
          index,
          now
        );
      });
      return postId;
    })();
  }

  function getPostOwner(postId) {
    return db.prepare("SELECT author_user_id FROM blog_posts WHERE id=?").get(postId) || null;
  }

  /**
   * Replace a post's body, re-encrypting under the same AAD.
   *
   * The AAD binds the ciphertext to the post id, so an edit must reuse
   * postAad(postId) rather than mint a new one - otherwise the row decrypts to
   * nothing on the next read.
   */
  function updatePostBody(postId, body) {
    const encrypted = requireCrypto().encryptText(body, postAad(postId));
    return (
      db
        .prepare("UPDATE blog_posts SET body=?, updated_at=? WHERE id=?")
        .run(encrypted, Date.now(), postId).changes > 0
    );
  }

  function getPostMediaFiles(postId) {
    return db.prepare("SELECT id, storage_name FROM blog_media WHERE post_id=?").all(postId);
  }

  function deletePost(postId) {
    return db.prepare("DELETE FROM blog_posts WHERE id=?").run(postId).changes > 0;
  }

  /**
   * Delete every post at once.
   *
   * Comments, likes and media rows go with them through ON DELETE CASCADE, but
   * the media *files* do not - the caller gets the storage names back and is
   * responsible for unlinking them. They are collected before the delete
   * because afterwards there is nothing left to look them up from.
   */
  function deleteAllPosts() {
    const files = db.prepare("SELECT id, storage_name FROM blog_media").all();
    const counts = db
      .prepare(
        `SELECT (SELECT COUNT(*) FROM blog_posts) AS posts,
                (SELECT COUNT(*) FROM blog_comments) AS comments`
      )
      .get();
    db.prepare("DELETE FROM blog_posts").run();
    return {
      files,
      deletedPosts: Number(counts?.posts || 0),
      deletedComments: Number(counts?.comments || 0),
    };
  }

  function setLiked(postId, userId, liked) {
    if (liked) {
      db.prepare(
        "INSERT INTO blog_likes (post_id, user_id, created_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
      ).run(postId, userId, Date.now());
    } else {
      db.prepare("DELETE FROM blog_likes WHERE post_id=? AND user_id=?").run(postId, userId);
    }
    return Number(db.prepare("SELECT COUNT(*) AS count FROM blog_likes WHERE post_id=?").get(postId).count);
  }

  function listComments(postId, cursor, limit) {
    const hasCursor = Number.isSafeInteger(cursor) && cursor > 0;
    const sql = `
      SELECT c.id, c.post_id, c.author_user_id, c.body, c.created_at, c.updated_at,
             u.user_code AS author_user_code, u.username AS author_username,
             u.color AS author_color, u.avatar_url AS author_avatar_url
      FROM blog_comments c
      JOIN users u ON u.id=c.author_user_id
      WHERE c.post_id=? ${hasCursor ? "AND c.id < ?" : ""}
      ORDER BY c.id DESC LIMIT ?`;
    const rows = hasCursor
      ? db.prepare(sql).all(postId, cursor, limit)
      : db.prepare(sql).all(postId, limit);
    return rows.map(hydrateComment);
  }

  function createComment(postId, authorUserId, body) {
    const now = Date.now();
    return db.transaction(() => {
      const id = Number(
        db.prepare(
          "INSERT INTO blog_comments (post_id, author_user_id, body, created_at, updated_at) VALUES (?, ?, '', ?, ?)"
        ).run(postId, authorUserId, now, now).lastInsertRowid
      );
      const encryptedBody = requireCrypto().encryptText(body, commentAad(id));
      db.prepare("UPDATE blog_comments SET body=? WHERE id=?").run(encryptedBody, id);
      return hydrateComment(db.prepare(
        `SELECT c.id, c.post_id, c.author_user_id, c.body, c.created_at, c.updated_at,
                u.user_code AS author_user_code, u.username AS author_username,
                u.color AS author_color, u.avatar_url AS author_avatar_url
         FROM blog_comments c JOIN users u ON u.id=c.author_user_id WHERE c.id=?`
      ).get(id));
    })();
  }

  function getCommentOwner(commentId) {
    return db.prepare("SELECT id, post_id, author_user_id FROM blog_comments WHERE id=?").get(commentId) || null;
  }

  function deleteComment(commentId) {
    return db.prepare("DELETE FROM blog_comments WHERE id=?").run(commentId).changes > 0;
  }

  function getMedia(mediaId) {
    return db.prepare(
      `SELECT id, post_id, media_kind, mime_type, original_name, storage_name, file_size,
              width, height, duration_ms, sort_order, created_at
       FROM blog_media WHERE id=?`
    ).get(mediaId) || null;
  }

  function listMediaRecords() {
    return db.prepare("SELECT id, post_id, storage_name, file_size FROM blog_media ORDER BY id").all();
  }

  function listStorageNames() {
    return new Set(db.prepare("SELECT storage_name FROM blog_media").all().map((row) => row.storage_name));
  }

  function getStats() {
    const row = db.prepare(
      `SELECT
         (SELECT COUNT(*) FROM blog_posts) AS post_count,
         (SELECT COUNT(*) FROM blog_comments) AS comment_count,
         (SELECT COUNT(*) FROM blog_media) AS media_count,
         COALESCE((SELECT SUM(file_size) FROM blog_media), 0) AS media_bytes`
    ).get();
    return {
      post_count: Number(row.post_count || 0),
      comment_count: Number(row.comment_count || 0),
      media_count: Number(row.media_count || 0),
      media_bytes: Number(row.media_bytes || 0),
    };
  }

  function migrateTextEncryption() {
    const cryptoService = requireCrypto();
    return db.transaction(() => {
      let postsEncrypted = 0;
      let commentsEncrypted = 0;
      for (const row of db.prepare("SELECT id, body FROM blog_posts ORDER BY id").all()) {
        if (!row.body || cryptoService.isEncryptedText(row.body)) continue;
        db.prepare("UPDATE blog_posts SET body=? WHERE id=?").run(
          cryptoService.encryptText(row.body, postAad(row.id)),
          row.id
        );
        postsEncrypted += 1;
      }
      for (const row of db.prepare("SELECT id, body FROM blog_comments ORDER BY id").all()) {
        if (!row.body || cryptoService.isEncryptedText(row.body)) continue;
        db.prepare("UPDATE blog_comments SET body=? WHERE id=?").run(
          cryptoService.encryptText(row.body, commentAad(row.id)),
          row.id
        );
        commentsEncrypted += 1;
      }
      return { postsEncrypted, commentsEncrypted };
    })();
  }

  return {
    listPosts,
    getPost,
    createPost,
    getPostOwner,
    updatePostBody,
    getPostMediaFiles,
    deletePost,
    deleteAllPosts,
    setLiked,
    listComments,
    createComment,
    getCommentOwner,
    deleteComment,
    getMedia,
    listMediaRecords,
    listStorageNames,
    getStats,
    migrateTextEncryption,
  };
}

module.exports = { createBlogRepository };
