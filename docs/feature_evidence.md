# README Claim Evidence

Paths below are relative to the repository root. These map advertised behavior to implementation; they do not assert that every feature has been exercised on hardware.

| README claim | Implementation evidence | Boundary |
| --- | --- | --- |
| Android Kotlin/Compose client | `familychat_app/app/build.gradle.kts`, `MainActivity.kt`, `ui/`, `navigation/` | ARM64/ARMv7; minimum API 26 |
| Node/Express/ws backend | `familychat_web/package.json`, `server.js`, `websocket/` | Modular monolith |
| Direct/groups/contact requests | `conversation/ConversationManager.kt`; backend `routes/relationship-routes.js`, `routes/conversation-routes.js` | User directory differs from established contacts |
| REST send / WebSocket receive | `network/ChatApi.kt:sendTextMessage/connect`, `message/RealtimeGateway.kt`; backend `routes/message-attachment-routes.js`, `websocket/` | Backend also retains WS send |
| History pagination/incremental sync | `message/MessageSyncEngine.kt`; backend history route | Bounded pages; server retention applies |
| Room caches/drafts/outbox | `message/LocalChatStore.kt`, `ChatManager.kt` | Content-blob encryption is not whole-DB encryption |
| Persistent retry / idempotency | `message/MessageSyncEngine.kt`; backend client ID checks and `messages_idempotency_idx` | Bounded retries; no exactly-once guarantee |
| Delivered/read receipts | `RealtimeGateway.kt`, `ChatManager.kt`; backend receipt handlers | FCM metadata arrival can count as delivered |
| Local notification mute | `ChatCore.kt`, `push/FamilyFirebaseMessagingService.kt` | Local suppression, not server delivery suppression |
| Client encrypted text | `ChatManager.kt:sendText`, `crypto/CryptoManager.kt`, `CryptoEngines.kt` | Optional, custom protocol with documented gaps |
| Encrypted attachments | `attachment/AttachmentManager.kt`, `AttachmentStorage.kt`, `crypto/CryptoEngines.kt` | Staged/decrypted files may be plaintext locally |
| FCM push/token maintenance/self-test | Android `push/`; backend `services/fcm-service.js`, `repositories/fcm-token-repository.js` | Requires own Firebase project; not live-tested here |
| Password/refresh/device lifecycle | Android `auth/`, `device/`; backend `auth-session-service.js`, `auth-device-routes.js`, `device-security-service.js` | Device UUID is not private-key login proof |
| SQLite/WAL/migrations | `lib/database.js`, `lib/schema-migrations.js`, `database/initialize-schema.js` | No HA/distributed storage |
| Linux/Nginx/TLS deployment | `deploy/`, `docs/deployment.md`; Node binds localhost | Generic templates, not a tested production configuration |
| Retained WebRTC calls | Android `call/`, WebRTC dependency; backend call signaling/config | Outside core narrative; no chat-protocol media E2EE claim |
| Legacy web chat disabled | `server.js` disabled chat response | Management portal remains active |

Android source prefixes in this table refer to `familychat_app/app/src/main/java/com/example/chat/`. See [validation](validation.md) for actual check results.

No measured latency/throughput, coverage percentage, high availability, Windows client, active browser chat, audited E2EE, Signal compatibility or complete forward secrecy is advertised.
