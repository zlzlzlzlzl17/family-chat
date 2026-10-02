# Firebase Configuration

FCM is optional for local message/API work. This repository contains no working Firebase configuration or service-account key.

## Android

1. Create your own Firebase project and register an Android application with application ID `com.example.chat`.
2. Download that project's Android `google-services.json`.
3. Place it at `familychat_app/app/google-services.json` (ignored by Git).
4. Rebuild the app. The Gradle script applies the Google Services plugin only if this file exists.

Without the file, messaging code remains buildable but Firebase initialization and push token acquisition may be unavailable. Do not replace the configuration with invented JSON.

A service-account private key never belongs in the Android app or APK. Android client API identifiers are not the server authorization credential.

## Server

Use a service account in the same project with permission to send FCM messages. Ensure the Firebase Cloud Messaging HTTP v1 API is available for that project, then set:

```dotenv
FCM_PROJECT_ID=your-project-id
FCM_CLIENT_EMAIL=service-account@your-project-id.iam.gserviceaccount.com
FCM_PRIVATE_KEY=
```

Populate the private key only in your ignored environment file or service secret configuration. A quoted value with literal backslash+n newline escapes is accepted by `server.js`; do not paste the key into docs, terminal transcripts or Git.

The backend signs an RS256 OAuth assertion, obtains a scoped Google access token, caches it and calls FCM HTTP v1. It uses Node's HTTPS/crypto modules rather than Firebase Admin SDK.

## Registration and verification

An authenticated trusted device registers its FCM token through `/api/fcm_register`. Token sync uses WorkManager, and revocation/logout remove token associations. Normal chat push data is generic and does not contain the message body.

Use the retained push self-test only with your own project and test device. Real FCM delivery was not exercised by the showcase backend tests. Device Google services, notification permissions, connectivity and platform restrictions can affect delivery.
