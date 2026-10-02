"use strict";

function registerSystemRoutes({
  app,
  authMiddleware,
  vapidPublicKey,
  defaultReleaseChannel,
  normalizeReleaseChannel,
  getAppReleaseState,
  getCallIceServers,
  sendPushHealthCheck,
  logger,
}) {
  app.get("/api/push_public_key", (_req, res) => {
    res.json({ publicKey: vapidPublicKey || "" });
  });

  app.get("/api/app_release", authMiddleware, (req, res) => {
    const channel = normalizeReleaseChannel(req.query.channel, defaultReleaseChannel);
    res.json({ item: getAppReleaseState(channel), channel });
  });

  app.get("/api/call_config", authMiddleware, (_req, res) => {
    res.json({ items: getCallIceServers() });
  });

  app.post("/api/push_self_test", authMiddleware, async (req, res) => {
    const diagnosticId = String(req.body?.diagnostic_id || "").trim().slice(0, 80);
    if (!/^[A-Za-z0-9:_-]{8,80}$/.test(diagnosticId)) {
      return res.status(400).json({ error: "bad_diagnostic_id" });
    }
    try {
      const result = await sendPushHealthCheck(req.auth.uid, diagnosticId);
      logger.info("push_self_test_requested", {
        userId: req.auth.uid,
        delivered: !!result.delivered,
        tokenCount: Number(result.tokenCount || 0),
      });
      res.status(result.delivered ? 202 : 503).json({ ok: !!result.delivered, ...result });
    } catch (error) {
      logger.warn("push_self_test_failed", { userId: req.auth.uid, error: error?.name || "Error" });
      res.status(502).json({ error: "push_test_failed" });
    }
  });
}

module.exports = { registerSystemRoutes };
