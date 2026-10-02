"use strict";

function registerBlogRoutes({
  app,
  authMiddleware,
  blogEnabled,
  blogUpload,
  repository,
  service,
  broadcast,
  sendBlogCommentToUser,
  logger,
}) {
  const router = require("express").Router();

  router.use(authMiddleware);
  router.use((_req, res, next) => {
    if (!blogEnabled) return res.status(503).json({ error: "blog_disabled" });
    res.setHeader("Cache-Control", "private, no-store");
    next();
  });

  function positiveId(value) {
    const parsed = Number.parseInt(String(value || ""), 10);
    return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null;
  }

  function postOr404(postId, userId, res) {
    const post = repository.getPost(postId, userId);
    if (!post) {
      res.status(404).json({ error: "blog_post_not_found" });
      return null;
    }
    return post;
  }

  router.get("/status", (_req, res) => res.json({ enabled: true }));

  router.get("/posts", (req, res) => {
    const limit = service.clampLimit(req.query.limit, 20, 50);
    const cursor = service.parseCursor(req.query.cursor);
    const rows = repository.listPosts(req.auth.uid, cursor, limit);
    const items = rows.map(service.postToWire);
    res.json({ items, next_cursor: rows.length === limit ? String(rows[rows.length - 1].id) : "" });
  });

  router.get("/posts/:id", (req, res) => {
    const postId = positiveId(req.params.id);
    if (!postId) return res.status(400).json({ error: "bad_blog_post_id" });
    const post = postOr404(postId, req.auth.uid, res);
    if (post) res.json({ item: service.postToWire(post) });
  });

  router.post("/posts", (req, res, next) => {
    blogUpload.array("media", 9)(req, res, (error) => {
      if (error) return next(error);
      const files = Array.isArray(req.files) ? req.files : [];
      try {
        const body = String(req.body?.body || "").trim();
        if (body.length > 5000) throw new Error("blog_body_too_long");
        if (!body && files.length === 0) throw new Error("empty_blog_post");
        const media = service.normalizeMedia(files, req.body?.media_metadata);
        service.encryptUploadedMedia(media);
        const postId = repository.createPost(req.auth.uid, body, media);
        const item = service.postToWire(repository.getPost(postId, req.auth.uid));
        broadcast({ type: "blog_post_created", item });
        logger.info("post_created", { postId, userId: req.auth.uid, mediaCount: media.length });
        res.status(201).json({ item });
      } catch (postError) {
        service.deleteFiles(files);
        const code = String(postError?.message || "blog_post_failed");
        const status = ["empty_blog_post", "blog_body_too_long", "unsupported_blog_media", "too_many_blog_videos", "blog_media_total_too_large"].includes(code)
          ? 400
          : 500;
        res.status(status).json({ error: code });
      }
    });
  });

  router.delete("/posts/:id", (req, res) => {
    const postId = positiveId(req.params.id);
    if (!postId) return res.status(400).json({ error: "bad_blog_post_id" });
    const owner = repository.getPostOwner(postId);
    if (!owner) return res.status(404).json({ error: "blog_post_not_found" });
    if (owner.author_user_id !== req.auth.uid && !req.auth.is_admin) {
      return res.status(403).json({ error: "blog_post_forbidden" });
    }
    const files = repository.getPostMediaFiles(postId);
    repository.deletePost(postId);
    service.deleteFiles(files);
    broadcast({ type: "blog_post_deleted", post_id: postId });
    logger.info("post_deleted", { postId, userId: req.auth.uid });
    res.json({ ok: true });
  });

  function setLike(liked) {
    return (req, res) => {
      const postId = positiveId(req.params.id);
      if (!postId) return res.status(400).json({ error: "bad_blog_post_id" });
      if (!postOr404(postId, req.auth.uid, res)) return;
      const likeCount = repository.setLiked(postId, req.auth.uid, liked);
      broadcast({ type: "blog_like_changed", post_id: postId, like_count: likeCount });
      res.json({ liked, like_count: likeCount });
    };
  }

  router.put("/posts/:id/like", setLike(true));
  router.delete("/posts/:id/like", setLike(false));

  router.get("/posts/:id/comments", (req, res) => {
    const postId = positiveId(req.params.id);
    if (!postId) return res.status(400).json({ error: "bad_blog_post_id" });
    if (!postOr404(postId, req.auth.uid, res)) return;
    const limit = service.clampLimit(req.query.limit, 50, 100);
    const cursor = service.parseCursor(req.query.cursor);
    const rows = repository.listComments(postId, cursor, limit);
    res.json({
      items: rows.map(service.commentToWire),
      next_cursor: rows.length === limit ? String(rows[rows.length - 1].id) : "",
    });
  });

  router.post("/posts/:id/comments", (req, res) => {
    const postId = positiveId(req.params.id);
    if (!postId) return res.status(400).json({ error: "bad_blog_post_id" });
    const postBeforeComment = postOr404(postId, req.auth.uid, res);
    if (!postBeforeComment) return;
    const body = String(req.body?.body || "").trim();
    if (!body || body.length > 1000) return res.status(400).json({ error: "bad_blog_comment" });
    const item = service.commentToWire(repository.createComment(postId, req.auth.uid, body));
    const post = repository.getPost(postId, req.auth.uid);
    broadcast({ type: "blog_comment_created", item, comment_count: Number(post.comment_count || 0) });
    if (postBeforeComment.author_user_id !== req.auth.uid && typeof sendBlogCommentToUser === "function") {
      Promise.resolve(sendBlogCommentToUser(postBeforeComment.author_user_id, postId)).catch((error) => {
        logger.warn("comment_notification_failed", {
          postId,
          recipientUserId: postBeforeComment.author_user_id,
          error: String(error?.message || error),
        });
      });
    }
    res.status(201).json({ item, comment_count: Number(post.comment_count || 0) });
  });

  router.delete("/comments/:id", (req, res) => {
    const commentId = positiveId(req.params.id);
    if (!commentId) return res.status(400).json({ error: "bad_blog_comment_id" });
    const comment = repository.getCommentOwner(commentId);
    if (!comment) return res.status(404).json({ error: "blog_comment_not_found" });
    const postOwner = repository.getPostOwner(comment.post_id);
    if (
      comment.author_user_id !== req.auth.uid &&
      postOwner?.author_user_id !== req.auth.uid &&
      !req.auth.is_admin
    ) {
      return res.status(403).json({ error: "blog_comment_forbidden" });
    }
    repository.deleteComment(commentId);
    const post = repository.getPost(comment.post_id, req.auth.uid);
    const commentCount = Number(post?.comment_count || 0);
    broadcast({ type: "blog_comment_deleted", comment_id: commentId, post_id: comment.post_id, comment_count: commentCount });
    res.json({ ok: true, comment_count: commentCount });
  });

  router.get("/media/:id", async (req, res) => {
    const mediaId = positiveId(req.params.id);
    if (!mediaId) return res.status(400).json({ error: "bad_blog_media_id" });
    const media = repository.getMedia(mediaId);
    if (!media) return res.status(404).json({ error: "blog_media_not_found" });
    try {
      await service.sendMedia(req, res, media);
    } catch (error) {
      logger.warn("media_read_failed", { mediaId, error: String(error?.message || error) });
      if (res.headersSent) return res.destroy(error);
      const code = String(error?.message || "blog_media_read_failed");
      return res.status(code === "blog_media_missing" ? 404 : 500).json({ error: code });
    }
  });

  router.use((error, req, res, _next) => {
    service.deleteFiles(Array.isArray(req.files) ? req.files : []);
    const code = error?.code === "LIMIT_FILE_SIZE" ? "blog_media_too_large" :
      error?.code === "LIMIT_UNEXPECTED_FILE" ? "too_many_blog_media" : "blog_upload_failed";
    res.status(error?.code?.startsWith("LIMIT_") ? 413 : 400).json({ error: code });
  });

  app.use("/api/blog", router);
}

module.exports = { registerBlogRoutes };
