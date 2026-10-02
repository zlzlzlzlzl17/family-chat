# Security and Encryption

This document describes source-visible behavior and limitations. It is not a security certification. The showcase cleanup does not change the cryptographic protocol.

## What can be confirmed

- With text encryption enabled, Android encrypts message content before submission; the backend stores and relays the payload; Android decrypts it.
- Chat attachment bytes and private metadata are encrypted on Android. File keys are wrapped for direct/group recipients.
- Normal chat FCM notifications contain generic text and IDs, not message bodies or chat keys.
- Protocol private keys/state remain client-side in the normal exchange paths.
- **Cannot confirm a complete, authenticated and reliably secure E2EE system**, particularly against a malicious server replacing identities or recipient key lists.

A request's `e2ee` flag is client-controlled metadata, not cryptographic validation. The backend accepts plaintext text messages and does not establish that every flagged payload is ciphertext.

## Algorithms and implementation

Android uses `java.security`, `javax.crypto` and Android Keystore, not libsignal.

| Use | Source-visible implementation |
| --- | --- |
| Content encryption | AES-256-GCM, random 12-byte IV, 128-bit tag, purpose/conversation associated data |
| Derivation | HKDF-SHA256 using HMAC-SHA256 |
| Agreement | ECDH on P-256 / secp256r1 |
| Identity signature | SHA256withECDSA, P-256 identity signing key |
| Direct messaging | Custom X3DH-style initialization and DH/chain ratchet; multi-device content-key envelopes; legacy v1 compatibility |
| Groups | Custom sender key per sender/device/conversation/epoch, HKDF per counter, signed encrypted key envelopes |
| Password storage | bcrypt, registration cost 12 |
| Blog at rest | Node crypto AES-256-GCM with a server-configured 32-byte key |

Protocol names do not establish Signal compatibility, full forward secrecy, post-compromise security or independent audit.

## Key generation and storage

- Device UUID: random UUID in ordinary preferences; it is a logical identifier.
- Signing identity: generated in Android Keystore under a device-specific alias.
- ECDH identity, signed prekey, one-time prekeys and ratchet key pairs: generated on Android; private values are serialized and stored through `SecureCryptoStore`.
- `SecureCryptoStore`: AES-GCM encrypted preferences protected by a Keystore wrapping key. These protocol private keys are **not all non-exportable Keystore key objects**. Legacy plaintext values have migration code.
- Direct content keys and attachment file keys: random 32-byte keys generated on the client.
- Group sender keys: random client keys scoped to sender/device/conversation/epoch/key ID.
- Local chat cache key: random 32-byte data key protected by the secure crypto store; cached content blobs use AES-GCM.
- Tokens: separate Keystore-protected encrypted preference store.

There are device-specific keys and session/message keys, not one documented global per-user master key. Hardware-backed storage is not guaranteed by the code.

## Exchange

Clients publish signing public identities, signed ECDH/prekey public material and one-time public prekeys. The server returns bundles for trusted conversation devices and transactionally marks a one-time prekey as claimed. Initiating clients verify bundle signatures and derive sessions locally. A private message's random content key is encrypted for each recipient device.

For groups, the client encrypts a sender key separately for recipient device ECDH identities and signs those envelopes. The backend stores wrapped envelopes; recipient devices verify/decrypt them. Membership/device changes can rotate the server-managed group epoch.

The normal backend exchange does not contain plaintext chat private keys or a general chat-body decryption routine.

## Server visibility and storage

| Content | What the server can see |
| --- | --- |
| Encrypted chat text | Ciphertext plus identifiers/metadata |
| Encryption-disabled text | Plaintext |
| Reply preview | Readable preview, potentially from decrypted text |
| Chat attachments | Ciphertext bytes and some display/transfer metadata |
| Users/devices/members/mentions/timestamps/receipts | Readable |
| System/security notices and avatars | Readable |
| Blog | Plaintext during API processing; stored encrypted at rest when enabled; server can decrypt |

SQLite itself is not encrypted as a whole. Android's chat Room content blobs are encrypted, but indexes/state remain readable. The separate Android Blog Room body/comment fields are plaintext.

Blog is **not E2EE**: `BLOG_AT_REST_KEY` is held by the server. Retained video/media Range handling does not change that boundary.

## Known gaps

1. **Reply preview leakage.** `ChatManager.buildReplyPreview` can extract decrypted text; `ReplyPreview.toWireJson` sends it separately; server `sanitizeReplyTo` stores it outside encrypted payload.
2. **Direct receive identity verification.** Initial payloads carry `sender_identity_ecdh_signature`, but `x3dhRecipientRoot/createReceiverSession` do not visibly verify/bind that signature to the sending user's signing identity.
3. **Key replacement acceptance.** Safety codes and change notices exist, but refreshed bundles are adopted and sessions reset without a mandatory manual trust confirmation or key transparency mechanism.
4. **Device approval is not cryptographic login proof.** Login checks password and submitted device UUID/database state, not a signed private-key challenge. Legacy device-ID fallback can select an existing trusted device.
5. **Prekey lifecycle.** Server claims are recorded, but no complete local consume/delete/replenish path for used one-time private prekeys or scheduled signed-prekey rotation was identified.
6. **Group security boundary.** Recipient members possess the sender key; ordinary group ciphertext has no independent per-message sender signature. Counter-derived keys are not an erasing group ratchet.
7. **Plaintext on endpoints.** Staged attachment sources, decrypted shared cache files, exports and Blog caches can contain plaintext. Backup exclusion and logout cleanup do not promise secure erasure everywhere.
8. **User-directory privacy.** The authenticated user endpoint exposes all users and `last_login_ip`.
9. **Public file URLs.** `/uploads` serves files without conversation authorization. Encrypted chat files depend on client crypto for confidentiality; avatars are readable.
10. **WebSocket token in URL.** The access token is a query parameter; reverse-proxy access logs must not record full request URIs for this route.
11. **Retention.** TTL cleanup does not erase all copies in WAL, backups or exports.
12. **Delivery meaning.** FCM metadata can generate delivered receipts before chat body synchronization/decryption.

These are documented findings, not fixes included in this cleanup.

## Secrets and configuration

Never commit SSH private keys, release signing material/passwords, service-account JSON/private keys, JWT secrets, TURN credentials, Blog keys, actual environment files, database snapshots or user media. `.gitignore` covers common paths and extensions; custom paths still require inspection.

Firebase Android configuration contains client/project identifiers and is distinct from a server service-account private key. The repository excludes both real client configuration and server credentials; each developer configures their own project.

Use unique secrets for JWT, Blog and push/relay services. Production rejects missing/short known-example JWT values and demo-user seeding. Management TOTP is retained, but it is not the Android login protocol.

The published snapshot contains only placeholder deployment values. The original repository and its history were left untouched; do not push that original history under the assumption that snapshot cleanup sanitized it.

## Evidence

Core sources: [CryptoEngines](../familychat_app/app/src/main/java/com/example/chat/crypto/CryptoEngines.kt), [CryptoManager](../familychat_app/app/src/main/java/com/example/chat/crypto/CryptoManager.kt), [DeviceIdentityManager](../familychat_app/app/src/main/java/com/example/chat/device/DeviceIdentityManager.kt), [SecureCryptoStore](../familychat_app/app/src/main/java/com/example/chat/SecureCryptoStore.kt), [message route](../familychat_web/routes/message-attachment-routes.js), [device/account service](../familychat_web/services/device-account-service.js), [Blog at-rest service](../familychat_web/services/blog-at-rest-service.js).
