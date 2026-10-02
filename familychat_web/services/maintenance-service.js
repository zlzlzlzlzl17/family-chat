"use strict";

function createMaintenanceService({
  db,
  fs,
  path,
  uploadDir,
  clients,
  socketHub,
  extractAttachmentFile,
  broadcast,
  cleanupBlogOrphans = () => 0,
  logger,
}) {
  const timers = [];

  function markPresenceDisconnected(ws) {
    if (!ws.auth?.uid || !ws.auth?.device_id || !ws.auth?.sid) return;
    db.prepare(
      `UPDATE device_presence SET websocket_connected=0, updated_at=?
       WHERE user_id=? AND device_id=? AND session_id=?`
    ).run(Date.now(), ws.auth.uid, ws.auth.device_id, ws.auth.sid);
  }

  function sweepSockets() {
    for (const ws of clients) {
      if (ws.isAlive === false) {
        try {
          ws.terminate();
        } catch {}
        clients.delete(ws);
        markPresenceDisconnected(ws);
        continue;
      }
      ws.isAlive = false;
      try {
        ws.ping();
      } catch {}
    }
  }

  function cleanupExpiredMessages() {
    const now = Date.now();
    const rows = db.prepare("SELECT kind, payload FROM messages WHERE expires_at <= ? AND kind != 'text'").all(now);
    for (const row of rows) {
      const file = extractAttachmentFile(row.payload, row.kind);
      if (!file) continue;
      try {
        fs.unlinkSync(path.join(uploadDir, file));
      } catch {}
    }
    const deleted = db.prepare("DELETE FROM messages WHERE expires_at <= ?").run(now).changes;
    if (deleted > 0) {
      broadcast({ type: "expired_cleanup", ts: now });
      logger.info("expired_messages_removed", { count: deleted });
    }
  }

  function cleanupAbandonedUploads() {
    const cutoff = Date.now() - 24 * 60 * 60 * 1000;
    const rows = db
      .prepare("SELECT upload_id, part_file FROM attachment_upload_sessions WHERE updated_at < ?")
      .all(cutoff);
    if (!rows.length) return;
    db.transaction(() => {
      for (const row of rows) {
        try {
          fs.unlinkSync(path.join(uploadDir, row.part_file));
        } catch {}
        db.prepare("DELETE FROM attachment_upload_sessions WHERE upload_id=?").run(row.upload_id);
      }
    })();
    logger.info("abandoned_uploads_removed", { count: rows.length });
  }

  function start() {
    timers.push(setInterval(sweepSockets, 25_000));
    timers.push(setInterval(cleanupExpiredMessages, 60 * 60 * 1000));
    timers.push(setInterval(cleanupAbandonedUploads, 60 * 60 * 1000));
    timers.push(setInterval(() => {
      const removed = cleanupBlogOrphans();
      if (removed > 0) logger.info("orphan_blog_media_removed", { count: removed });
    }, 6 * 60 * 60 * 1000));
    cleanupAbandonedUploads();
    cleanupBlogOrphans();
  }

  function stop() {
    timers.splice(0).forEach(clearInterval);
  }

  return { start, stop, markPresenceDisconnected };
}

module.exports = { createMaintenanceService };
