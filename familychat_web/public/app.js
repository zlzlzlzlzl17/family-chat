const $ = (id) => document.getElementById(id);

const refs = {
  loginView: $("loginView"),
  chatView: $("chatView"),
  titleText: $("titleText"),
  loginLead: $("loginLead"),
  usernameLabel: $("usernameLabel"),
  passwordLabel: $("passwordLabel"),
  username: $("username"),
  password: $("password"),
  loginBtn: $("loginBtn"),
  loginHint: $("loginHint"),
  statusDot: $("statusDot"),
  statusText: $("statusText"),
  chatStatusDot: $("chatStatusDot"),
  chatStatusText: $("chatStatusText"),
  langSelect: $("langSelect"),
  langSelectChat: $("langSelectChat"),
  languageLabel: $("languageLabel"),
  languageLabelChat: $("languageLabelChat"),
  meText: $("meText"),
  sidebarTitle: $("sidebarTitle"),
  securityTitle: $("securityTitle"),
  actionsTitle: $("actionsTitle"),
  roomTitle: $("roomTitle"),
  roomSubtitle: $("roomSubtitle"),
  roomSecretLabel: $("roomSecretLabel"),
  roomSecret: $("roomSecret"),
  hintText: $("hintText"),
  e2eeToggle: $("e2eeToggle"),
  e2eeLabel: $("e2eeLabel"),
  enablePushBtn: $("enablePushBtn"),
  changePwBtn: $("changePwBtn"),
  deleteBtn: $("deleteBtn"),
  logoutBtn: $("logoutBtn"),
  chat: $("chat"),
  typingHint: $("typingHint"),
  replyBar: $("replyBar"),
  replyingLabel: $("replyingLabel"),
  replyText: $("replyText"),
  replyCancelBtn: $("replyCancelBtn"),
  mentionMenu: $("mentionMenu"),
  imageInput: $("imageInput"),
  fileInput: $("fileInput"),
  imageBtn: $("imageBtn"),
  fileBtn: $("fileBtn"),
  voiceBtn: $("voiceBtn"),
  message: $("message"),
  sendBtn: $("sendBtn"),
};

const I18N = {
  en: {
    title: "Family Chat",
    lead: "Private family messaging with encrypted media.",
    username: "Username",
    password: "Password",
    login: "Login",
    loginHint: "Use alice / bob / carol",
    language: "Language",
    disconnected: "Disconnected",
    connecting: "Connecting...",
    connected: "Connected",
    error: "Error",
    me: (u, admin) => `${u}${admin ? " · admin" : ""}`,
    sidebarTitle: "Family Chat",
    roomTitle: "Family Chat Room",
    roomSubtitle: "Encrypted messages, voice, files and photos",
    security: "Security",
    actions: "Actions",
    roomSecret: "Room secret",
    roomSecretPlaceholder: "Shared secret, never sent to the server",
    messagesExpire: "Messages and media expire in 3 days.",
    e2ee: "End-to-end encryption",
    enablePush: "Enable push",
    changePassword: "Change password",
    deleteHistory: "Delete history",
    logout: "Logout",
    typeMessage: "Type a message",
    send: "Send",
    replying: "Replying",
    unread: "Unread messages",
    needSecret: "Enter the room secret first.",
    decryptNeedSecret: "Encrypted message. Enter the room secret to read it.",
    decryptFailed: "Unable to decrypt with the current room secret.",
    encryptedAttachment: "Encrypted attachment. Enter the room secret to open it.",
    badAttachment: "Unable to load this attachment.",
    imageAttachment: "Image",
    fileAttachment: "File",
    voiceAttachment: "Voice note",
    download: "Download",
    open: "Open",
    preparing: "Preparing...",
    replyTo: (u) => `Reply to ${u}`,
    historyDeleted: "History deleted for everyone.",
    deletedBy: (u) => `${u} deleted the history`,
    forcedLogout: "You were signed out because this account logged in on another device.",
    passwordPrompt: "New password (min 8 characters):",
    passwordChanged: "Password changed. Please log in again.",
    confirmDelete: "Delete all chat history for everyone?",
    pushEnabled: "Push enabled.",
    pushNoSupport: "Push is not supported on this browser.",
    pushServerDisabled: "Push is not configured on the server.",
    pushDenied: "Notification permission denied.",
    uploadFailed: "Upload failed",
    loginFailed: "Login failed",
    recordFailed: "Microphone access failed.",
    noRecorder: "Voice recording is not supported on this browser.",
    recording: "Recording voice message...",
    attachmentSent: "Encrypted attachment sent.",
    replyHint: "Double-click or long press to reply",
    mentionRole: "member",
    encryptedPreview: "Encrypted message",
  },
  zh: {
    title: "家庭聊天室",
    lead: "支持加密图片、文件和语音的家庭聊天。",
    username: "用户名",
    password: "密码",
    login: "登录",
    loginHint: "使用 alice / bob / carol",
    language: "语言",
    disconnected: "未连接",
    connecting: "连接中...",
    connected: "已连接",
    error: "错误",
    me: (u, admin) => `${u}${admin ? " · 管理员" : ""}`,
    sidebarTitle: "家庭聊天室",
    roomTitle: "家庭聊天房间",
    roomSubtitle: "加密文字、语音、文件和图片",
    security: "安全",
    actions: "操作",
    roomSecret: "房间密钥",
    roomSecretPlaceholder: "共享密钥，不会发送到服务器",
    messagesExpire: "消息和媒体会在 3 天后自动过期。",
    e2ee: "端到端加密",
    enablePush: "开启推送",
    changePassword: "修改密码",
    deleteHistory: "清空记录",
    logout: "退出登录",
    typeMessage: "输入消息",
    send: "发送",
    replying: "正在回复",
    unread: "未读消息",
    needSecret: "请先输入房间密钥。",
    decryptNeedSecret: "这是加密消息，请输入房间密钥后查看。",
    decryptFailed: "当前房间密钥无法解密。",
    encryptedAttachment: "这是加密附件，请输入房间密钥后打开。",
    badAttachment: "无法加载该附件。",
    imageAttachment: "图片",
    fileAttachment: "文件",
    voiceAttachment: "语音",
    download: "下载",
    open: "打开",
    preparing: "处理中...",
    replyTo: (u) => `回复 ${u}`,
    historyDeleted: "聊天记录已为所有人清空。",
    deletedBy: (u) => `${u} 清空了聊天记录`,
    forcedLogout: "该账号已在另一台设备登录，当前设备已退出。",
    passwordPrompt: "请输入新密码（至少 8 位）：",
    passwordChanged: "密码已修改，请重新登录。",
    confirmDelete: "确认清空所有人的聊天记录吗？",
    pushEnabled: "推送已开启。",
    pushNoSupport: "当前浏览器不支持推送。",
    pushServerDisabled: "服务器未配置推送。",
    pushDenied: "通知权限被拒绝。",
    uploadFailed: "上传失败",
    loginFailed: "登录失败",
    recordFailed: "无法使用麦克风。",
    noRecorder: "当前浏览器不支持语音录制。",
    recording: "正在录制语音...",
    attachmentSent: "加密附件已发送。",
    replyHint: "双击或长按消息可回复",
    mentionRole: "成员",
    encryptedPreview: "加密消息",
  },
};

let lang = localStorage.getItem("lang") || "en";
let token = localStorage.getItem("token") || "";
let me = null;
let users = [];
let userMap = new Map();
let readStateMap = new Map();
let ws = null;
let reconnectTimer = null;
let lastId = 0;
let replyTo = null;
let mentionOptions = [];
let mentionCtx = null;
let attachmentCache = new Map();
let mediaRecorder = null;
let mediaStream = null;
let recorderChunks = [];
let recorderStartedAt = 0;
let hintTimer = null;
const webcryptoOk = !!(globalThis.crypto && crypto.subtle);

const t = (key, ...args) => {
  const value = I18N[lang]?.[key] ?? I18N.en[key] ?? key;
  return typeof value === "function" ? value(...args) : value;
};

const parseJson = (text, fallback = null) => {
  try {
    return JSON.parse(text);
  } catch {
    return fallback;
  }
};

const formatTime = (ts) => new Date(ts).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });

function formatBytes(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return "";
  const units = ["B", "KB", "MB", "GB"];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value >= 10 || unit === 0 ? 0 : 1)} ${units[unit]}`;
}

function showLogin() {
  refs.loginView.style.display = "";
  refs.chatView.style.display = "none";
  setStatus("disconnected");
}

function showChat() {
  refs.loginView.style.display = "none";
  refs.chatView.style.display = "";
}

function setStatus(state) {
  const map = {
    connected: ["#25d366", t("connected")],
    connecting: ["#34b7f1", t("connecting")],
    error: ["#df3c3c", t("error")],
    disconnected: ["#f59e0b", t("disconnected")],
  };
  const [color, label] = map[state] || map.disconnected;
  refs.statusDot.style.background = color;
  refs.statusText.textContent = label;
  refs.chatStatusDot.style.background = color;
  refs.chatStatusText.textContent = label;
}

function showHint(text, ms = 2400) {
  clearTimeout(hintTimer);
  refs.typingHint.textContent = text;
  refs.typingHint.style.display = "";
  hintTimer = setTimeout(() => {
    refs.typingHint.style.display = "none";
  }, ms);
}

function applyLang() {
  document.title = t("title");
  refs.titleText.textContent = t("title");
  refs.loginLead.textContent = t("lead");
  refs.usernameLabel.textContent = t("username");
  refs.passwordLabel.textContent = t("password");
  refs.username.placeholder = t("username");
  refs.password.placeholder = t("password");
  refs.loginBtn.textContent = t("login");
  refs.loginHint.textContent = t("loginHint");
  refs.languageLabel.textContent = t("language");
  refs.languageLabelChat.textContent = t("language");
  refs.sidebarTitle.textContent = t("sidebarTitle");
  refs.securityTitle.textContent = t("security");
  refs.actionsTitle.textContent = t("actions");
  refs.roomTitle.textContent = t("roomTitle");
  refs.roomSubtitle.textContent = t("roomSubtitle");
  refs.roomSecretLabel.textContent = t("roomSecret");
  refs.roomSecret.placeholder = t("roomSecretPlaceholder");
  refs.hintText.textContent = t("messagesExpire");
  refs.e2eeLabel.textContent = t("e2ee");
  refs.enablePushBtn.textContent = t("enablePush");
  refs.changePwBtn.textContent = t("changePassword");
  refs.deleteBtn.textContent = t("deleteHistory");
  refs.logoutBtn.textContent = t("logout");
  refs.message.placeholder = t("typeMessage");
  refs.sendBtn.textContent = t("send");
  refs.replyingLabel.textContent = t("replying");
  refs.langSelect.value = lang;
  refs.langSelectChat.value = lang;
  if (me) refs.meText.textContent = t("me", me.username, me.is_admin);
  refreshReadStatuses();
  updateUnreadDivider();
}

refs.langSelect.addEventListener("change", () => {
  lang = refs.langSelect.value;
  localStorage.setItem("lang", lang);
  refs.langSelectChat.value = lang;
  applyLang();
});

refs.langSelectChat.addEventListener("change", () => {
  lang = refs.langSelectChat.value;
  localStorage.setItem("lang", lang);
  refs.langSelect.value = lang;
  applyLang();
});

function roomSecretKey() {
  return me ? `roomSecret:${me.username}` : "roomSecret:anon";
}

function secretValue() {
  return refs.roomSecret.value.trim();
}

async function api(path, opts = {}) {
  const headers = { ...(opts.headers || {}) };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (!(opts.body instanceof FormData) && !headers["Content-Type"]) headers["Content-Type"] = "application/json";
  const response = await fetch(path, { ...opts, headers });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.error || "request_failed");
  return data;
}

function clearAttachmentCache() {
  for (const entry of attachmentCache.values()) {
    Promise.resolve(entry).then((result) => {
      if (result?.blobUrl) URL.revokeObjectURL(result.blobUrl);
    }).catch(() => {});
  }
  attachmentCache = new Map();
}

async function loadMe() {
  me = await api("/api/me");
  refs.meText.textContent = t("me", me.username, me.is_admin);
  refs.deleteBtn.disabled = !me.is_admin;
  refs.roomSecret.value = localStorage.getItem(roomSecretKey()) || "";
  refs.e2eeToggle.checked = webcryptoOk;
  refs.e2eeToggle.disabled = !webcryptoOk;
  refs.roomSecret.disabled = !webcryptoOk;
  showChat();
}

async function syncUsers() {
  const data = await api("/api/users");
  users = Array.isArray(data.items) ? data.items : [];
  userMap = new Map(users.map((user) => [user.username, user]));
}

async function syncReadStates() {
  const data = await api("/api/read_states");
  readStateMap = new Map((data.items || []).map((item) => [item.username, Number(item.last_read_message_id || 0)]));
  refreshReadStatuses();
  updateUnreadDivider();
}

function isNearBottom() {
  return refs.chat.scrollHeight - refs.chat.scrollTop - refs.chat.clientHeight < 96;
}

function scrollToBottom(smooth = false) {
  refs.chat.scrollTo({ top: refs.chat.scrollHeight, behavior: smooth ? "smooth" : "auto" });
}

function latestMessageId() {
  return [...refs.chat.querySelectorAll(".msg[data-id]")].reduce((max, row) => Math.max(max, Number(row.dataset.id || 0)), 0);
}

function addSystem(text) {
  const row = document.createElement("div");
  row.className = "system-chip";
  row.innerHTML = `<span>${text}</span>`;
  refs.chat.appendChild(row);
  scrollToBottom();
}

function purgeExpiredFromUI() {
  const now = Date.now();
  for (const row of refs.chat.querySelectorAll(".msg[data-expires-at]")) {
    if (Number(row.dataset.expiresAt || 0) <= now) row.remove();
  }
  updateUnreadDivider();
}

function messageFromRow(row) {
  return {
    id: Number(row.dataset.id || 0),
    ts: Number(row.dataset.ts || 0),
    expires_at: Number(row.dataset.expiresAt || 0),
    username: row.dataset.username || "",
    color: row.dataset.color || "#075e54",
    kind: row.dataset.kind || "text",
    payload: row.dataset.payload || "",
    e2ee: Number(row.dataset.e2ee || 0),
    reply_to: row.dataset.replyTo || "",
    mentions: parseJson(row.dataset.mentions || "[]", []),
  };
}

async function loadHistory() {
  clearAttachmentCache();
  refs.chat.innerHTML = "";
  clearReplyTo();
  lastId = 0;
  while (true) {
    const data = await api(`/api/history?since_id=${lastId}&limit=200`);
    const items = data.items || [];
    if (!items.length) break;
    for (const item of items) {
      addChat(item, false);
      lastId = item.id;
    }
  }
  purgeExpiredFromUI();
  refreshReadStatuses();
  updateUnreadDivider();
  requestAnimationFrame(() => {
    scrollToBottom();
    maybeSendReadReceipt(true);
  });
}

function wsUrl() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  return `${proto}://${location.host}/ws?token=${encodeURIComponent(token)}`;
}

function connectWs() {
  if (!token) return;
  if (reconnectTimer) clearTimeout(reconnectTimer);
  if (ws) {
    try { ws.close(); } catch {}
  }
  setStatus("connecting");
  ws = new WebSocket(wsUrl());
  ws.onopen = () => {
    setStatus("connected");
    maybeSendReadReceipt(true);
  };
  ws.onerror = () => setStatus("error");
  ws.onclose = () => {
    setStatus("disconnected");
    if (token) reconnectTimer = setTimeout(connectWs, 1500);
  };
  ws.onmessage = (event) => {
    const msg = parseJson(event.data, null);
    if (!msg) return;
    if (msg.type === "force_logout") {
      localStorage.removeItem("token");
      token = "";
      me = null;
      if (ws) ws.close();
      showLogin();
      alert(t("forcedLogout"));
      return;
    }
    if (msg.type === "history_deleted") {
      clearAttachmentCache();
      refs.chat.innerHTML = "";
      addSystem(t("deletedBy", msg.by || "Someone"));
      addSystem(t("historyDeleted"));
      readStateMap = new Map(users.map((u) => [u.username, 0]));
      return;
    }
    if (msg.type === "expired_cleanup") return purgeExpiredFromUI();
    if (msg.type === "read_receipt") {
      readStateMap.set(msg.username, Number(msg.last_read_message_id || 0));
      refreshReadStatuses();
      return updateUnreadDivider();
    }
    if (msg.type === "read_snapshot" && Array.isArray(msg.items)) {
      readStateMap = new Map(msg.items.map((item) => [item.username, Number(item.last_read_message_id || 0)]));
      refreshReadStatuses();
      return updateUnreadDivider();
    }
    if (msg.type === "chat") {
      lastId = Math.max(lastId, Number(msg.id || 0));
      addChat(msg, isNearBottom() || msg.username === me?.username);
      purgeExpiredFromUI();
      if (msg.username !== me?.username && !document.hidden) setTimeout(() => maybeSendReadReceipt(true), 120);
      else updateUnreadDivider();
    }
  };
}

function parseMentions(text) {
  const seen = new Set();
  const out = [];
  for (const match of String(text).matchAll(/@([a-zA-Z0-9_.-]{1,32})/g)) {
    const username = match[1];
    if (!userMap.has(username) || seen.has(username)) continue;
    seen.add(username);
    out.push(username);
  }
  return out;
}

function setReplyTo(value) {
  replyTo = value;
  refs.replyText.textContent = value.preview;
  refs.replyBar.style.display = "";
}

function clearReplyTo() {
  replyTo = null;
  refs.replyText.textContent = "";
  refs.replyBar.style.display = "none";
}

refs.replyCancelBtn.addEventListener("click", clearReplyTo);

function renderQuoteBlock(item, container) {
  if (!item.reply_to) return;
  const reply = typeof item.reply_to === "string" ? parseJson(item.reply_to, null) : item.reply_to;
  if (!reply?.id) return;
  const box = document.createElement("div");
  box.className = "quoteBubble";
  const meta = document.createElement("div");
  meta.className = "quoteMeta";
  meta.textContent = t("replyTo", reply.username || "unknown");
  const preview = document.createElement("div");
  preview.textContent = reply.preview || "";
  box.append(meta, preview);
  container.appendChild(box);
}

function renderMentionText(target, text) {
  const fragment = document.createDocumentFragment();
  let index = 0;
  for (const match of String(text).matchAll(/@([a-zA-Z0-9_.-]{1,32})/g)) {
    const start = match.index || 0;
    fragment.appendChild(document.createTextNode(String(text).slice(index, start)));
    const full = match[0];
    if (userMap.has(match[1])) {
      const span = document.createElement("span");
      span.className = "mention";
      span.textContent = full;
      fragment.appendChild(span);
    } else {
      fragment.appendChild(document.createTextNode(full));
    }
    index = start + full.length;
  }
  fragment.appendChild(document.createTextNode(String(text).slice(index)));
  target.replaceChildren(fragment);
}

function readStatusFor(id) {
  const others = users.filter((user) => user.username !== me?.username);
  if (!others.length) return { text: "✓✓", seen: true };
  const count = others.filter((user) => (readStateMap.get(user.username) || 0) >= id).length;
  if (count >= others.length) return { text: "✓✓", seen: true };
  if (count > 0) return { text: "✓✓", seen: false };
  return { text: "✓", seen: false };
}

function refreshReadStatuses() {
  for (const row of refs.chat.querySelectorAll(".msg.mine[data-id]")) {
    const status = row.querySelector(".read-status");
    if (!status) continue;
    const info = readStatusFor(Number(row.dataset.id || 0));
    status.textContent = info.text;
    status.classList.toggle("seen", info.seen);
  }
}

function updateUnreadDivider() {
  refs.chat.querySelector(".unread-divider")?.remove();
  if (!me) return;
  const threshold = Number(readStateMap.get(me.username) || 0);
  if (!threshold) return;
  const row = [...refs.chat.querySelectorAll(".msg[data-id]")].find(
    (item) => Number(item.dataset.id || 0) > threshold && item.dataset.username !== me.username
  );
  if (!row) return;
  const divider = document.createElement("div");
  divider.className = "unread-divider";
  divider.innerHTML = `<span>${t("unread")}</span>`;
  refs.chat.insertBefore(divider, row);
}

function attachReplyGesture(target, item) {
  target.title = t("replyHint");
  target.addEventListener("dblclick", () => void beginReply(item));
  let timer = null;
  const cancel = () => {
    if (timer) clearTimeout(timer);
    timer = null;
  };
  const start = () => {
    timer = setTimeout(() => void beginReply(item), 420);
  };
  target.addEventListener("touchstart", start, { passive: true });
  target.addEventListener("touchend", cancel);
  target.addEventListener("touchcancel", cancel);
  target.addEventListener("touchmove", cancel, { passive: true });
  target.addEventListener("mousedown", start);
  target.addEventListener("mouseup", cancel);
  target.addEventListener("mouseleave", cancel);
}

async function beginReply(item) {
  setReplyTo({
    id: item.id,
    username: item.username,
    color: item.color,
    preview: await buildReplyPreview(item),
  });
  refs.message.focus();
}

async function buildReplyPreview(item) {
  if (item.kind === "photo" || item.kind === "image") return t("imageAttachment");
  if (item.kind === "audio") return t("voiceAttachment");
  if (item.kind === "file") return t("fileAttachment");
  if (!item.e2ee) return String(item.payload || "").slice(0, 120);
  if (!secretValue()) return t("encryptedPreview");
  try {
    return (await decryptText(secretValue(), item.payload)).slice(0, 120);
  } catch {
    return t("encryptedPreview");
  }
}

function addChat(item, shouldScroll) {
  const mine = me && item.username === me.username;
  const row = document.createElement("div");
  row.className = `msg${mine ? " mine" : ""}`;
  row.dataset.id = String(item.id || 0);
  row.dataset.ts = String(item.ts || 0);
  row.dataset.expiresAt = String(item.expires_at || 0);
  row.dataset.username = item.username || "";
  row.dataset.color = item.color || "#075e54";
  row.dataset.kind = item.kind || "text";
  row.dataset.payload = item.payload || "";
  row.dataset.e2ee = String(item.e2ee ? 1 : 0);
  row.dataset.replyTo = item.reply_to || "";
  row.dataset.mentions = JSON.stringify(item.mentions || []);

  const wrap = document.createElement("div");
  wrap.className = "bubble-wrap";
  const bubble = document.createElement("div");
  bubble.className = "bubble";
  if (!mine) {
    const user = document.createElement("div");
    user.className = "bubble-user";
    user.textContent = item.username;
    user.style.color = item.color || "#075e54";
    bubble.appendChild(user);
  }
  const body = document.createElement("div");
  body.className = "body";
  renderQuoteBlock(item, body);
  bubble.appendChild(body);
  const footer = document.createElement("div");
  footer.className = `bubble-footer${mine ? " mine" : ""}`;
  const time = document.createElement("span");
  time.textContent = formatTime(item.ts || Date.now());
  footer.appendChild(time);
  if (mine) {
    const status = document.createElement("span");
    status.className = "read-status";
    footer.appendChild(status);
  }
  bubble.appendChild(footer);
  wrap.appendChild(bubble);
  row.appendChild(wrap);
  refs.chat.appendChild(row);
  attachReplyGesture(bubble, item);
  void renderMessageBody(body, row, item);
  refreshReadStatuses();
  updateUnreadDivider();
  if (shouldScroll) scrollToBottom(true);
}

async function renderMessageBody(body, row, item) {
  const content = document.createElement("div");
  body.appendChild(content);
  if (item.kind === "photo") {
    const payload = parseJson(item.payload, {});
    const img = document.createElement("img");
    img.className = "photo";
    img.src = payload.url || "";
    img.alt = payload.name || "photo";
    content.appendChild(img);
    return;
  }
  if (item.kind === "text") {
    if (!item.e2ee) return renderMentionText(content, item.payload);
    if (!secretValue()) {
      content.textContent = t("decryptNeedSecret");
      return;
    }
    try {
      const plain = await decryptText(secretValue(), item.payload);
      if (row.isConnected) renderMentionText(content, plain);
    } catch {
      if (row.isConnected) content.textContent = t("decryptFailed");
    }
    return;
  }
  const card = document.createElement("div");
  card.className = "attachment-card";
  content.appendChild(card);
  if (!secretValue()) {
    card.textContent = t("encryptedAttachment");
    return;
  }
  try {
    const material = await getAttachmentMaterial(item);
    if (!row.isConnected) return;
    if (item.kind === "image") {
      const img = document.createElement("img");
      img.className = "photo";
      img.src = material.blobUrl;
      img.alt = material.meta.name || "image";
      card.appendChild(img);
    }
    if (item.kind === "audio") {
      const audio = document.createElement("audio");
      audio.controls = true;
      audio.src = material.blobUrl;
      card.appendChild(audio);
    }
    const meta = document.createElement("div");
    meta.className = "attachment-meta";
    const kindLabel =
      item.kind === "image" ? t("imageAttachment") :
      item.kind === "audio" ? t("voiceAttachment") :
      t("fileAttachment");
    meta.innerHTML = `<strong>${material.meta.name || kindLabel}</strong><small>${formatBytes(material.meta.size)}${material.meta.mime ? ` · ${material.meta.mime}` : ""}</small>`;
    card.appendChild(meta);
    const actions = document.createElement("div");
    actions.className = "attachment-actions";
    const button = document.createElement("button");
    button.type = "button";
    button.className = "mini-btn";
    button.textContent = item.kind === "file" ? t("download") : t("open");
    button.addEventListener("click", () => {
      const link = document.createElement("a");
      link.href = material.blobUrl;
      link.download = material.meta.name || `${item.kind}.bin`;
      link.click();
    });
    actions.appendChild(button);
    card.appendChild(actions);
  } catch {
    card.textContent = t("badAttachment");
  }
}

function rerenderEncryptedRows() {
  clearAttachmentCache();
  for (const row of refs.chat.querySelectorAll(".msg[data-id]")) {
    const item = messageFromRow(row);
    if (!item.e2ee) continue;
    const body = row.querySelector(".body");
    if (!body) continue;
    body.innerHTML = "";
    renderQuoteBlock(item, body);
    void renderMessageBody(body, row, item);
  }
}

function maybeSendReadReceipt(force = false) {
  if (!me || !ws || ws.readyState !== WebSocket.OPEN) return;
  if (!force && (document.hidden || !isNearBottom())) return;
  const id = latestMessageId();
  if (!id || id <= Number(readStateMap.get(me.username) || 0)) return;
  readStateMap.set(me.username, id);
  refreshReadStatuses();
  updateUnreadDivider();
  ws.send(JSON.stringify({ type: "read", last_read_message_id: id }));
}

refs.chat.addEventListener("scroll", () => {
  if (!document.hidden && isNearBottom()) maybeSendReadReceipt();
});
document.addEventListener("visibilitychange", () => {
  if (!document.hidden) maybeSendReadReceipt(true);
});
window.addEventListener("focus", () => maybeSendReadReceipt(true));

function b64(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = "";
  const chunk = 0x8000;
  for (let i = 0; i < bytes.length; i += chunk) binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  return btoa(binary);
}

function fromB64(value) {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

async function deriveKey(secret, saltB64) {
  const encoder = new TextEncoder();
  const material = await crypto.subtle.importKey("raw", encoder.encode(secret), "PBKDF2", false, ["deriveKey"]);
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", salt: fromB64(saltB64), iterations: 200000, hash: "SHA-256" },
    material,
    { name: "AES-GCM", length: 256 },
    false,
    ["encrypt", "decrypt"]
  );
}

async function encryptText(secret, plain) {
  const encoder = new TextEncoder();
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const saltB64 = b64(salt);
  const ivB64 = b64(iv);
  const key = await deriveKey(secret, saltB64);
  const cipher = await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, encoder.encode(plain));
  return btoa(JSON.stringify({ v: 1, salt: saltB64, iv: ivB64, ct: b64(cipher) }));
}

async function decryptText(secret, payload) {
  const data = parseJson(atob(payload), null);
  if (!data?.salt || !data?.iv || !data?.ct) throw new Error("bad_payload");
  const key = await deriveKey(secret, data.salt);
  const plain = await crypto.subtle.decrypt(
    { name: "AES-GCM", iv: new Uint8Array(fromB64(data.iv)) },
    key,
    fromB64(data.ct)
  );
  return new TextDecoder().decode(plain);
}

async function encryptBinary(secret, buffer) {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const saltB64 = b64(salt);
  const ivB64 = b64(iv);
  const key = await deriveKey(secret, saltB64);
  return {
    cipher: await crypto.subtle.encrypt({ name: "AES-GCM", iv }, key, buffer),
    salt: saltB64,
    iv: ivB64,
  };
}

async function decryptBinary(secret, saltB64, ivB64, buffer) {
  const key = await deriveKey(secret, saltB64);
  return crypto.subtle.decrypt(
    { name: "AES-GCM", iv: new Uint8Array(fromB64(ivB64)) },
    key,
    buffer
  );
}

async function getAttachmentMaterial(item) {
  const secret = secretValue();
  if (!secret) throw new Error("missing_secret");
  const cacheKey = `${item.id}:${secret}`;
  if (!attachmentCache.has(cacheKey)) {
    attachmentCache.set(cacheKey, (async () => {
      const payload = parseJson(item.payload, null);
      if (!payload?.attachment?.url || !payload?.enc?.salt || !payload?.enc?.iv || !payload?.meta) {
        throw new Error("bad_payload");
      }
      const response = await fetch(payload.attachment.url);
      if (!response.ok) throw new Error("fetch_failed");
      const cipherBytes = await response.arrayBuffer();
      const plainBytes = await decryptBinary(secret, payload.enc.salt, payload.enc.iv, cipherBytes);
      const meta = parseJson(await decryptText(secret, payload.meta), {});
      const blob = new Blob([plainBytes], { type: meta.mime || "application/octet-stream" });
      return { meta, blobUrl: URL.createObjectURL(blob) };
    })());
  }
  return attachmentCache.get(cacheKey);
}

async function sendMessage() {
  if (!ws || ws.readyState !== WebSocket.OPEN) return;
  const text = refs.message.value.trim();
  if (!text) return;
  const payload = {
    type: "chat",
    kind: "text",
    mentions: parseMentions(text),
    reply_to: replyTo ? JSON.stringify(replyTo) : "",
  };
  if (refs.e2eeToggle.checked) {
    if (!secretValue()) return alert(t("needSecret"));
    payload.e2ee = 1;
    payload.payload = await encryptText(secretValue(), text);
  } else {
    payload.e2ee = 0;
    payload.payload = text;
  }
  ws.send(JSON.stringify(payload));
  refs.message.value = "";
  clearReplyTo();
  hideMentionMenu();
  autoResizeComposer();
}

async function sendAttachment(file, kind, extra = {}) {
  if (!webcryptoOk || !secretValue()) return alert(t("needSecret"));
  showHint(t("preparing"), 5000);
  const bytes = await file.arrayBuffer();
  const encrypted = await encryptBinary(secretValue(), bytes);
  const meta = {
    name: file.name || `${kind}.bin`,
    mime: file.type || extra.mime || "application/octet-stream",
    size: file.size || bytes.byteLength,
    durationMs: extra.durationMs || 0,
  };
  const formData = new FormData();
  formData.append("attachment", new Blob([encrypted.cipher], { type: "application/octet-stream" }), `${kind}.enc`);
  formData.append("kind", kind);
  formData.append("payload", JSON.stringify({
    v: 1,
    enc: { salt: encrypted.salt, iv: encrypted.iv },
    meta: await encryptText(secretValue(), JSON.stringify(meta)),
  }));
  formData.append("reply_to", replyTo ? JSON.stringify(replyTo) : "");
  formData.append("mentions", JSON.stringify([]));
  await api("/api/upload_attachment", { method: "POST", body: formData });
  clearReplyTo();
  showHint(t("attachmentSent"));
}

function hideMentionMenu() {
  refs.mentionMenu.style.display = "none";
  refs.mentionMenu.innerHTML = "";
  mentionOptions = [];
  mentionCtx = null;
}

function pickMention(option) {
  if (!mentionCtx) return;
  const before = refs.message.value.slice(0, mentionCtx.start);
  const after = refs.message.value.slice(mentionCtx.end);
  const insert = `@${option.username} `;
  refs.message.value = `${before}${insert}${after}`;
  const pos = before.length + insert.length;
  refs.message.focus();
  refs.message.setSelectionRange(pos, pos);
  hideMentionMenu();
  autoResizeComposer();
}

function updateMentionMenu() {
  const before = refs.message.value.slice(0, refs.message.selectionStart);
  const match = before.match(/(^|\s)@([a-zA-Z0-9_.-]{0,32})$/);
  if (!match) return hideMentionMenu();
  const query = (match[2] || "").toLowerCase();
  mentionOptions = users.filter((user) => user.username.toLowerCase().includes(query)).slice(0, 6);
  if (!mentionOptions.length) return hideMentionMenu();
  mentionCtx = { start: refs.message.selectionStart - query.length - 1, end: refs.message.selectionStart };
  refs.mentionMenu.innerHTML = "";
  for (const option of mentionOptions) {
    const row = document.createElement("button");
    row.type = "button";
    row.className = "mention-option";
    row.innerHTML = `<strong>@${option.username}</strong><span>${t("mentionRole")}</span>`;
    row.addEventListener("click", () => pickMention(option));
    refs.mentionMenu.appendChild(row);
  }
  refs.mentionMenu.style.display = "";
}

function autoResizeComposer() {
  refs.message.style.height = "auto";
  refs.message.style.height = `${Math.min(refs.message.scrollHeight, 180)}px`;
}

async function enablePush() {
  if (!("serviceWorker" in navigator)) return alert(t("pushNoSupport"));
  await navigator.serviceWorker.register("/sw.js");
  const { publicKey } = await api("/api/push_public_key");
  if (!publicKey) return alert(t("pushServerDisabled"));
  const permission = await Notification.requestPermission();
  if (permission !== "granted") return alert(t("pushDenied"));
  const reg = await navigator.serviceWorker.ready;
  const padding = "=".repeat((4 - (publicKey.length % 4)) % 4);
  const value = (publicKey + padding).replace(/-/g, "+").replace(/_/g, "/");
  const data = atob(value);
  const key = Uint8Array.from([...data].map((char) => char.charCodeAt(0)));
  const sub = await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
  await api("/api/push_subscribe", { method: "POST", body: JSON.stringify(sub) });
  alert(t("pushEnabled"));
}

async function toggleVoiceRecording() {
  if (mediaRecorder && mediaRecorder.state === "recording") {
    mediaRecorder.stop();
    return;
  }
  if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === "undefined") {
    return alert(t("noRecorder"));
  }
  try {
    mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const mimeType = MediaRecorder.isTypeSupported("audio/webm;codecs=opus")
      ? "audio/webm;codecs=opus"
      : MediaRecorder.isTypeSupported("audio/ogg;codecs=opus")
        ? "audio/ogg;codecs=opus"
        : "";
    mediaRecorder = new MediaRecorder(mediaStream, mimeType ? { mimeType } : undefined);
    recorderChunks = [];
    recorderStartedAt = Date.now();
    mediaRecorder.ondataavailable = (event) => {
      if (event.data?.size) recorderChunks.push(event.data);
    };
    mediaRecorder.onstop = async () => {
      const blob = new Blob(recorderChunks, { type: mediaRecorder.mimeType || "audio/webm" });
      const ext = blob.type.includes("ogg") ? "ogg" : "webm";
      const file = new File([blob], `voice-${Date.now()}.${ext}`, { type: blob.type });
      mediaStream?.getTracks().forEach((track) => track.stop());
      mediaStream = null;
      mediaRecorder = null;
      refs.voiceBtn.classList.remove("recording");
      try {
        await sendAttachment(file, "audio", { durationMs: Date.now() - recorderStartedAt, mime: file.type });
      } catch (error) {
        alert(`${t("uploadFailed")}: ${error.message}`);
      }
    };
    mediaRecorder.start();
    refs.voiceBtn.classList.add("recording");
    showHint(t("recording"), 100000);
  } catch {
    alert(t("recordFailed"));
  }
}

refs.message.addEventListener("input", () => {
  autoResizeComposer();
  updateMentionMenu();
});
refs.message.addEventListener("click", updateMentionMenu);
refs.message.addEventListener("keyup", updateMentionMenu);
refs.message.addEventListener("blur", () => setTimeout(hideMentionMenu, 120));
refs.message.addEventListener("keydown", (event) => {
  if (event.key === "Tab" && mentionOptions.length) {
    event.preventDefault();
    pickMention(mentionOptions[0]);
    return;
  }
  if (event.key === "Enter" && !event.shiftKey) {
    event.preventDefault();
    void sendMessage();
  }
});

refs.loginBtn.addEventListener("click", async () => {
  try {
    const data = await api("/api/login", {
      method: "POST",
      body: JSON.stringify({ username: refs.username.value.trim(), password: refs.password.value }),
    });
    token = data.token;
    localStorage.setItem("token", token);
    await loadMe();
    await syncUsers();
    await syncReadStates();
    await loadHistory();
    connectWs();
    applyLang();
  } catch (error) {
    alert(`${t("loginFailed")}: ${error.message}`);
  }
});

refs.logoutBtn.addEventListener("click", () => {
  localStorage.removeItem("token");
  token = "";
  me = null;
  if (ws) ws.close();
  clearAttachmentCache();
  showLogin();
});

refs.changePwBtn.addEventListener("click", async () => {
  const next = prompt(t("passwordPrompt"));
  if (!next) return;
  try {
    await api("/api/change_password", { method: "POST", body: JSON.stringify({ new_password: next }) });
    alert(t("passwordChanged"));
  } catch (error) {
    alert(error.message);
  }
});

refs.deleteBtn.addEventListener("click", async () => {
  if (!me?.is_admin || !confirm(t("confirmDelete"))) return;
  try {
    await api("/api/delete_history", { method: "POST", body: "{}" });
  } catch (error) {
    alert(error.message);
  }
});

refs.enablePushBtn.addEventListener("click", async () => {
  try {
    await enablePush();
  } catch (error) {
    alert(error.message);
  }
});

refs.roomSecret.addEventListener("input", () => {
  if (me) localStorage.setItem(roomSecretKey(), refs.roomSecret.value);
  rerenderEncryptedRows();
});

refs.sendBtn.addEventListener("click", () => void sendMessage());
refs.imageBtn.addEventListener("click", () => refs.imageInput.click());
refs.fileBtn.addEventListener("click", () => refs.fileInput.click());
refs.voiceBtn.addEventListener("click", () => void toggleVoiceRecording());

refs.imageInput.addEventListener("change", async () => {
  const file = refs.imageInput.files?.[0];
  refs.imageInput.value = "";
  if (!file) return;
  try {
    await sendAttachment(file, "image");
  } catch (error) {
    alert(`${t("uploadFailed")}: ${error.message}`);
  }
});

refs.fileInput.addEventListener("change", async () => {
  const file = refs.fileInput.files?.[0];
  refs.fileInput.value = "";
  if (!file) return;
  try {
    await sendAttachment(file, "file");
  } catch (error) {
    alert(`${t("uploadFailed")}: ${error.message}`);
  }
});

applyLang();
showLogin();
autoResizeComposer();

(async () => {
  if (!token) return;
  try {
    await loadMe();
    await syncUsers();
    await syncReadStates();
    await loadHistory();
    connectWs();
    applyLang();
  } catch {
    localStorage.removeItem("token");
    token = "";
    showLogin();
  }
})();
