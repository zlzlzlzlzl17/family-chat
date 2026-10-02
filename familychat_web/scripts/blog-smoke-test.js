"use strict";

const assert = require("assert");
const fs = require("fs");
const os = require("os");
const path = require("path");
const { spawn } = require("child_process");
const Database = require("better-sqlite3");
const { createBlogAtRestService } = require("../services/blog-at-rest-service");
const { createBlogRepository } = require("../repositories/blog-repository");
const { createBlogService } = require("../services/blog-service");

const serverPath = path.join(__dirname, "..", "server.js");
const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "familychat-blog-"));
const dbPath = path.join(tempDir, "chat.sqlite");
const port = 3201;
const baseUrl = `http://127.0.0.1:${port}`;
const appHeaders = { "X-FamilyChat-Client": "android-app" };
const blogAtRestKey = Buffer.alloc(32, 7).toString("base64");

function waitForServer(child) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("server_start_timeout")), 10_000);
    child.stdout.on("data", (chunk) => {
      const output = String(chunk);
      if (output.includes("Family chat HTTP") || output.includes('"event":"listening"')) {
        clearTimeout(timer);
        resolve();
      }
    });
    child.stderr.on("data", (chunk) => process.stderr.write(chunk));
    child.on("exit", (code) => {
      clearTimeout(timer);
      reject(new Error(`server_exited_${code}`));
    });
  });
}

async function request(pathname, options = {}) {
  const response = await fetch(`${baseUrl}${pathname}`, {
    ...options,
    headers: { ...appHeaders, ...(options.headers || {}) },
  });
  const text = await response.text();
  let body = {};
  if (text) body = JSON.parse(text);
  return { response, body };
}

async function manageRequest(pathname, options = {}) {
  const response = await fetch(`${baseUrl}${pathname}`, {
    ...options,
    headers: { "X-Forwarded-Host": "manage.example.com", ...(options.headers || {}) },
  });
  const text = await response.text();
  let body = {};
  if (text && response.headers.get("content-type")?.includes("application/json")) body = JSON.parse(text);
  return { response, body, bytes: Buffer.from(text) };
}

async function login(userCode, password, deviceId) {
  return request("/api/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ user_code: userCode, password, device_id: deviceId, platform: "android" }),
  });
}

async function main() {
  const child = spawn(process.execPath, [serverPath], {
    env: {
      ...process.env,
      NODE_ENV: "development",
      HOST: "127.0.0.1",
      TRUST_PROXY_HOPS: "1",
      MANAGE_HOST: "manage.example.com",
      FCM_PROJECT_ID: "",
      FCM_CLIENT_EMAIL: "",
      FCM_PRIVATE_KEY: "",
      VAPID_PUBLIC_KEY: "",
      VAPID_PRIVATE_KEY: "",
      PORT: String(port),
      DB_PATH: dbPath,
      UPLOAD_DIR: path.join(tempDir, "uploads"),
      BLOG_UPLOAD_DIR: path.join(tempDir, "blog_uploads"),
      APP_RELEASE_DIR: path.join(tempDir, "releases"),
      JWT_SECRET: "blog-smoke-test-secret",
      SEED_DEMO_USERS: "true",
      BLOG_ENABLED: "true",
      BLOG_AT_REST_KEY: blogAtRestKey,
      MANAGE_BOOTSTRAP_USERNAME: "smoke-admin",
      MANAGE_BOOTSTRAP_PASSWORD: "smoke-admin-password",
    },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let db;
  try {
    await waitForServer(child);
    db = new Database(dbPath);
    const user = db.prepare("SELECT id, user_code FROM users WHERE username='alice'").get();
    assert.ok(user?.id, "seeded alice user is required");
    const now = Date.now();
    db.prepare(`
      INSERT INTO devices (
        user_id, device_id, platform, device_name, manufacturer, model, status,
        approved_by_device_id, approved_by_admin, created_at, approved_at,
        revoked_at, last_seen_at, last_ip
      ) VALUES (?, ?, 'android', 'Blog trusted device', 'test', 'blog',
                'trusted', '', 'smoke', ?, ?, 0, ?, '127.0.0.1')
    `).run(user.id, "blog-trusted-device", now, now, now);

    const trustedLogin = await login(user.user_code, "alice-change-me", "blog-trusted-device");
    assert.equal(trustedLogin.response.status, 200);
    assert.equal(trustedLogin.body.device_status, "trusted");
    const trustedAuth = { Authorization: `Bearer ${trustedLogin.body.token}` };

    const anonymous = await request("/api/blog/posts");
    assert.equal(anonymous.response.status, 401);

    const pendingLogin = await login(user.user_code, "alice-change-me", "blog-pending-device");
    assert.equal(pendingLogin.body.device_status, "pending");
    const pending = await request("/api/blog/posts", {
      headers: { Authorization: `Bearer ${pendingLogin.body.token}` },
    });
    assert.equal(pending.response.status, 403);
    assert.equal(pending.body.error, "device_pending");

    const form = new FormData();
    form.append("body", "Trusted family blog post");
    form.append("media_metadata", JSON.stringify([{ width: 1, height: 1, duration_ms: 0 }]));
    const png = Buffer.from(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Z3XQAAAAASUVORK5CYII=",
      "base64"
    );
    form.append("media", new Blob([png], { type: "image/png" }), "one-pixel.png");
    const created = await request("/api/blog/posts", {
      method: "POST",
      headers: trustedAuth,
      body: form,
    });
    assert.equal(created.response.status, 201);
    assert.equal(created.body.item.body, "Trusted family blog post");
    assert.equal(created.body.item.media.length, 1);
    const postId = created.body.item.id;
    const mediaUrl = created.body.item.media[0].url;
    const storedPost = db.prepare("SELECT body FROM blog_posts WHERE id=?").get(postId);
    assert.notEqual(storedPost.body, "Trusted family blog post");
    assert.match(storedPost.body, /^fcblog:v1:/);
    const storedMedia = db.prepare("SELECT storage_name FROM blog_media WHERE post_id=?").get(postId);
    const storedMediaBytes = fs.readFileSync(path.join(tempDir, "blog_uploads", storedMedia.storage_name));
    assert.equal(storedMediaBytes.subarray(0, 8).toString("ascii"), "FCBLOGM1");
    assert.notDeepStrictEqual(storedMediaBytes, png);

    const privateMedia = await fetch(`${baseUrl}${mediaUrl}`, { headers: appHeaders });
    assert.equal(privateMedia.status, 401);
    const pendingMedia = await fetch(`${baseUrl}${mediaUrl}`, {
      headers: { ...appHeaders, Authorization: `Bearer ${pendingLogin.body.token}` },
    });
    assert.equal(pendingMedia.status, 403);
    const trustedMedia = await fetch(`${baseUrl}${mediaUrl}`, {
      headers: { ...appHeaders, ...trustedAuth },
    });
    assert.equal(trustedMedia.status, 200);
    assert.deepStrictEqual(Buffer.from(await trustedMedia.arrayBuffer()), png);
    const trustedMediaRange = await fetch(`${baseUrl}${mediaUrl}`, {
      headers: { ...appHeaders, ...trustedAuth, Range: "bytes=4-17" },
    });
    assert.equal(trustedMediaRange.status, 206);
    assert.equal(trustedMediaRange.headers.get("content-range"), `bytes 4-17/${png.length}`);
    assert.deepStrictEqual(Buffer.from(await trustedMediaRange.arrayBuffer()), png.subarray(4, 18));

    const liked = await request(`/api/blog/posts/${postId}/like`, {
      method: "PUT",
      headers: trustedAuth,
      body: "",
    });
    assert.equal(liked.response.status, 200);
    assert.equal(liked.body.liked, true);
    assert.equal(liked.body.like_count, 1);

    const commented = await request(`/api/blog/posts/${postId}/comments`, {
      method: "POST",
      headers: { ...trustedAuth, "Content-Type": "application/json" },
      body: JSON.stringify({ body: "A private comment" }),
    });
    assert.equal(commented.response.status, 201);
    assert.equal(commented.body.item.body, "A private comment");
    const storedComment = db.prepare("SELECT body FROM blog_comments WHERE id=?").get(commented.body.item.id);
    assert.notEqual(storedComment.body, "A private comment");
    assert.match(storedComment.body, /^fcblog:v1:/);

    const listed = await request("/api/blog/posts", { headers: trustedAuth });
    assert.equal(listed.response.status, 200);
    assert.equal(listed.body.items.length, 1);
    assert.equal(listed.body.items[0].like_count, 1);
    assert.equal(listed.body.items[0].comment_count, 1);

    const legacyStorageName = "legacy-plain.png";
    fs.writeFileSync(path.join(tempDir, "blog_uploads", legacyStorageName), png);
    const legacyNow = Date.now();
    const legacyPostId = Number(db.prepare(
      "INSERT INTO blog_posts (author_user_id, body, created_at, updated_at) VALUES (?, ?, ?, ?)"
    ).run(user.id, "Legacy plaintext post", legacyNow, legacyNow).lastInsertRowid);
    const legacyCommentId = Number(db.prepare(
      "INSERT INTO blog_comments (post_id, author_user_id, body, created_at, updated_at) VALUES (?, ?, ?, ?, ?)"
    ).run(legacyPostId, user.id, "Legacy plaintext comment", legacyNow, legacyNow).lastInsertRowid);
    const legacyMediaId = Number(db.prepare(
      `INSERT INTO blog_media (
         post_id, media_kind, mime_type, original_name, storage_name, file_size,
         width, height, duration_ms, sort_order, created_at
       ) VALUES (?, 'image', 'image/png', 'legacy.png', ?, ?, 1, 1, 0, 0, ?)`
    ).run(legacyPostId, legacyStorageName, png.length, legacyNow).lastInsertRowid);
    const migrationAtRest = createBlogAtRestService({ keyText: blogAtRestKey, fs, path });
    const migrationRepository = createBlogRepository(db, migrationAtRest);
    const migrationService = createBlogService({
      repository: migrationRepository,
      fs,
      path,
      blogUploadDir: path.join(tempDir, "blog_uploads"),
      atRest: migrationAtRest,
      logger: { info() {} },
    });
    const migration = migrationService.migrateAtRest();
    assert.equal(migration.postsEncrypted, 1);
    assert.equal(migration.commentsEncrypted, 1);
    assert.equal(migration.encrypted, 1);
    assert.match(db.prepare("SELECT body FROM blog_posts WHERE id=?").get(legacyPostId).body, /^fcblog:v1:/);
    assert.match(db.prepare("SELECT body FROM blog_comments WHERE id=?").get(legacyCommentId).body, /^fcblog:v1:/);
    assert.equal(fs.readFileSync(path.join(tempDir, "blog_uploads", legacyStorageName)).subarray(0, 8).toString("ascii"), "FCBLOGM1");
    const migratedPost = await request(`/api/blog/posts/${legacyPostId}`, { headers: trustedAuth });
    assert.equal(migratedPost.body.item.body, "Legacy plaintext post");
    const migratedMedia = await fetch(`${baseUrl}/api/blog/media/${legacyMediaId}`, {
      headers: { ...appHeaders, ...trustedAuth },
    });
    assert.deepStrictEqual(Buffer.from(await migratedMedia.arrayBuffer()), png);

    const manageLogin = await manageRequest("/manage_api/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: "smoke-admin", password: "smoke-admin-password" }),
    });
    assert.equal(manageLogin.response.status, 200);
    const manageCookie = manageLogin.response.headers.get("set-cookie").split(";", 1)[0];
    const manageHeaders = { Cookie: manageCookie };
    const managedPosts = await manageRequest("/manage_api/blog/posts?limit=20", { headers: manageHeaders });
    assert.equal(managedPosts.response.status, 200);
    assert.equal(managedPosts.body.items.find((item) => item.id === legacyPostId).body, "Legacy plaintext post");
    const managedComments = await manageRequest(`/manage_api/blog/posts/${legacyPostId}/comments`, { headers: manageHeaders });
    assert.equal(managedComments.response.status, 200);
    assert.equal(managedComments.body.items[0].body, "Legacy plaintext comment");
    const managedMedia = await fetch(`${baseUrl}/manage_api/blog/media/${legacyMediaId}`, {
      headers: { "X-Forwarded-Host": "manage.example.com", Cookie: manageCookie },
    });
    assert.deepStrictEqual(Buffer.from(await managedMedia.arrayBuffer()), png);
    const managedCommentDelete = await manageRequest(`/manage_api/blog/comments/${legacyCommentId}`, {
      method: "DELETE",
      headers: manageHeaders,
    });
    assert.equal(managedCommentDelete.response.status, 200);
    const managedPostDelete = await manageRequest(`/manage_api/blog/posts/${legacyPostId}`, {
      method: "DELETE",
      headers: manageHeaders,
    });
    assert.equal(managedPostDelete.response.status, 200);

    const deleted = await request(`/api/blog/posts/${postId}`, { method: "DELETE", headers: trustedAuth });
    assert.equal(deleted.response.status, 200);
    const deletedMedia = await fetch(`${baseUrl}${mediaUrl}`, {
      headers: { ...appHeaders, ...trustedAuth },
    });
    assert.equal(deletedMedia.status, 404);

    // Clear-all is the destructive admin action, so it has to be shown taking
    // the comments and the media files with it - not just emptying blog_posts
    // and leaving orphaned rows and files behind.
    const clearForm = new FormData();
    clearForm.append("body", "Post to be cleared");
    clearForm.append("media_metadata", JSON.stringify([{ width: 1, height: 1, duration_ms: 0 }]));
    clearForm.append("media", new Blob([png], { type: "image/png" }), "cleared.png");
    const doomed = await request("/api/blog/posts", { method: "POST", headers: trustedAuth, body: clearForm });
    assert.equal(doomed.response.status, 201);
    const doomedId = doomed.body.item.id;
    await request(`/api/blog/posts/${doomedId}/comments`, {
      method: "POST",
      headers: { ...trustedAuth, "Content-Type": "application/json" },
      body: JSON.stringify({ body: "Comment to be cleared" }),
    });
    const doomedStorage = db.prepare("SELECT storage_name FROM blog_media WHERE post_id=?").get(doomedId);
    assert.ok(fs.existsSync(path.join(tempDir, "blog_uploads", doomedStorage.storage_name)));

    const cleared = await manageRequest("/manage_api/clear_blog", {
      method: "POST",
      headers: { ...manageHeaders, "Content-Type": "application/json" },
      body: JSON.stringify({}),
    });
    assert.equal(cleared.response.status, 200);
    assert.equal(cleared.body.deletedPosts, 1);
    assert.equal(cleared.body.deletedComments, 1);
    assert.equal(cleared.body.deletedFiles, 1);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM blog_posts").get().n, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM blog_comments").get().n, 0);
    assert.equal(db.prepare("SELECT COUNT(*) AS n FROM blog_media").get().n, 0);
    assert.equal(fs.existsSync(path.join(tempDir, "blog_uploads", doomedStorage.storage_name)), false);

    // --- Update announcements --------------------------------------------
    // The account is deliberately not a person. Everything below is about
    // proving the fences hold, because a "user" that can publish is only safe
    // if it cannot also be signed into or written to by anyone else.
    const announcer = db
      .prepare("SELECT id, user_code, username, account_status FROM users WHERE username=?")
      .get("Update Announcement");
    assert.ok(announcer, "announcement account was not seeded");
    assert.equal(announcer.account_status, "system");

    // It must not be a way in.
    const announcerLogin = await login(announcer.user_code, "", "announce-device");
    assert.equal(announcerLogin.response.status, 403);
    assert.equal(announcerLogin.body.error, "account_unavailable");

    // It must not be addable as a contact, even knowing the code.
    const announcerLookup = await request(
      `/api/contacts/lookup?user_code=${announcer.user_code}`,
      { headers: trustedAuth }
    );
    assert.equal(announcerLookup.response.status, 404);
    const announcerRequest = await request("/api/contact_requests", {
      method: "POST",
      headers: { ...trustedAuth, "Content-Type": "application/json" },
      body: JSON.stringify({ user_code: announcer.user_code }),
    });
    assert.equal(announcerRequest.response.status, 404);

    // It must not show up as family in the manage overview.
    const overview = await manageRequest("/manage_api/overview", { headers: manageHeaders });
    assert.equal(overview.response.status, 200);
    assert.equal(
      overview.body.registered_users.some((u) => u.username === "Update Announcement"),
      false,
      "announcement account leaked into the family list"
    );

    // Publishing works, and is authored by the announcement account.
    const announceForm = new FormData();
    announceForm.append("body", "Version 4.1.7 is out.");
    const published = await manageRequest("/manage_api/blog/announcements", {
      method: "POST",
      headers: manageHeaders,
      body: announceForm,
    });
    assert.equal(published.response.status, 201);
    assert.equal(published.body.item.author.username, "Update Announcement");
    assert.equal(published.body.item.body, "Version 4.1.7 is out.");
    const announcementId = published.body.item.id;
    assert.match(
      db.prepare("SELECT body FROM blog_posts WHERE id=?").get(announcementId).body,
      /^fcblog:v1:/,
      "announcement body was stored in plaintext"
    );

    // Editing its own announcement works, and stays readable after re-encryption.
    const edited = await manageRequest(`/manage_api/blog/announcements/${announcementId}`, {
      method: "PUT",
      headers: { ...manageHeaders, "Content-Type": "application/json" },
      body: JSON.stringify({ body: "Version 4.1.7 is out. Blog media now opens full screen." }),
    });
    assert.equal(edited.response.status, 200);
    assert.equal(edited.body.item.body, "Version 4.1.7 is out. Blog media now opens full screen.");

    // The guard that matters: the admin cannot rewrite a family member's post.
    const familyForm = new FormData();
    familyForm.append("body", "Something a family member wrote");
    const familyPost = await request("/api/blog/posts", {
      method: "POST",
      headers: trustedAuth,
      body: familyForm,
    });
    assert.equal(familyPost.response.status, 201);
    const tampered = await manageRequest(`/manage_api/blog/announcements/${familyPost.body.item.id}`, {
      method: "PUT",
      headers: { ...manageHeaders, "Content-Type": "application/json" },
      body: JSON.stringify({ body: "Words the family member never wrote" }),
    });
    assert.equal(tampered.response.status, 403);
    assert.equal(tampered.body.error, "not_an_announcement");
    const untouched = await request(`/api/blog/posts/${familyPost.body.item.id}`, { headers: trustedAuth });
    assert.equal(untouched.body.item.body, "Something a family member wrote");

    // An empty announcement is not a post.
    const emptyForm = new FormData();
    emptyForm.append("body", "   ");
    const empty = await manageRequest("/manage_api/blog/announcements", {
      method: "POST",
      headers: manageHeaders,
      body: emptyForm,
    });
    assert.equal(empty.response.status, 400);

    console.log("trusted-device blog, encrypted-at-rest content, and protected-media smoke test passed");
    console.log("update announcement account, publishing, editing, and its restrictions passed");
  } finally {
    if (db?.open) db.close();
    if (child.exitCode === null) {
      child.kill("SIGTERM");
      await Promise.race([
        new Promise((resolve) => child.once("exit", resolve)),
        new Promise((resolve) => setTimeout(resolve, 3_000)),
      ]);
    }
    for (let attempt = 0; attempt < 5; attempt += 1) {
      try {
        fs.rmSync(tempDir, { recursive: true, force: true });
        break;
      } catch (error) {
        if (attempt === 4) throw error;
        await new Promise((resolve) => setTimeout(resolve, 150 * (attempt + 1)));
      }
    }
  }
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
