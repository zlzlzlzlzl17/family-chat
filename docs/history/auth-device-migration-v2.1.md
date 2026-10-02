# Family Chat 2.1 authentication and device migration

Historical record of the v2.1 rollout. The rollout instructions and APK version below are not current deployment instructions; see [release and deployment](../operations/release-and-deploy.zh.md) for the current workflow.

## User-facing changes

- Sign in with the permanent eight-digit user ID and password. Usernames are display names and can change.
- The app stores a long-lived refresh token protected by Android Keystore. It no longer stores the password.
- A newly installed device starts as `pending`. It cannot read messages, connect to chat WebSocket, receive group keys, or register FCM until approved.
- Approve a pending device from an existing trusted device under Security, or from the management page.
- Existing devices that already have identity keys are migrated to `trusted` during the server upgrade.

## Server-side model

- `users`: permanent account identity and account status.
- `devices`: stable device ID and `pending`, `trusted`, or `revoked` trust state.
- `auth_sessions`: device-bound access/refresh sessions. Access JWTs last 15 minutes; refresh tokens last 30 days and are stored only as SHA-256 hashes.
- `device_identity_keys` and `direct_prekeys`: public cryptographic identity for each trusted device.
- `fcm_tokens`: bound to a device and records recent send success/failure separately from login and presence.
- `device_presence`: recent WebSocket and foreground state only.

## Revocation behavior

- Removing a device revokes all of its sessions, closes its WebSockets, removes FCM and prekeys, removes group-key envelopes, and rotates affected group epochs.
- Changing the password revokes all other sessions and clears push registration for those sessions' devices. Device trust is retained.
- Logging out revokes the current session and removes that device's current FCM registration.
- Account deletion removes sessions, devices, public keys, push tokens, presence, and key envelopes as one server transaction after management approval.

## Rollout notes

1. Deploy the server update. It migrates existing identity-key devices and legacy sessions without deleting users or messages.
2. Install `familychat_v2.1.2(beta).apk` on one existing device per account first.
3. Sign in with the eight-digit ID shown in Account management.
4. Approve each additional device from Security or `https://manage.example.com/`.
5. Confirm direct chat, group key coverage, FCM, and device removal before rolling out to every device.

The server temporarily accepts the legacy username login payload for old installed clients during rollout. The Android 2.1 client only offers permanent-ID login. Remove the compatibility branch after all devices have upgraded.
