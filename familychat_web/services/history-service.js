"use strict";

function createHistoryService({ db, fs, path, uploadDir, extractAttachmentFile }) {
  const UPLOAD_DIR = uploadDir;

function deleteAttachmentFiles(rows) {
  let deletedFiles = 0;
  for (const row of rows) {
    const file = extractAttachmentFile(row.payload, row.kind);
    if (!file) continue;
    try {
      fs.unlinkSync(path.join(UPLOAD_DIR, file));
      deletedFiles += 1;
    } catch {}
  }
  return deletedFiles;
}

function clearAttachmentMessages() {
  const rows = db
    .prepare("SELECT id, kind, payload FROM messages WHERE kind IN ('photo', 'image', 'audio', 'file')")
    .all();
  const deletedFiles = deleteAttachmentFiles(rows);
  const changedMessages = db.prepare(
    `
      UPDATE messages
      SET kind='attachment_cleared',
          payload='',
          reply_to='',
          mentions='[]',
          e2ee=0
      WHERE kind IN ('photo', 'image', 'audio', 'file')
    `
  ).run().changes;
  return { deletedFiles, changedMessages };
}

function clearAllHistory() {
  const rows = db
    .prepare("SELECT id, kind, payload FROM messages WHERE kind IN ('photo', 'image', 'audio', 'file')")
    .all();
  const deletedFiles = deleteAttachmentFiles(rows);
  const deletedMessages = db.prepare("DELETE FROM messages").run().changes;
  const now = Date.now();
  db.prepare("UPDATE read_states SET last_read_message_id=0, updated_at=?").run(now);
  db.prepare("UPDATE conversation_read_states SET last_read_message_id=0, updated_at=?").run(now);
  db.prepare("UPDATE conversation_delivery_states SET last_delivered_message_id=0, updated_at=?").run(now);
  return { deletedFiles, deletedMessages };
}

  return { deleteAttachmentFiles, clearAttachmentMessages, clearAllHistory };
}

module.exports = { createHistoryService };
