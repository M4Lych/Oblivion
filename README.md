# Oblivion

Oblivion is a new Android Bedrock launcher and community client. The active project is a Kotlin/Jetpack Compose Android app in `app/` with a separate Cloudflare Workers backend in `backend/`.

## Current implementation

- Dark, responsive launcher dashboard and profile, character, library, community, news, marketplace, servers, settings, diagnostics, admin status, and credits screens.
- Device-local profile preferences, original geometric character preview, PNG skin archive copy, and validated `.mcworld` imports in Oblivion-private storage.
- A launch-management flow that checks for a supported in-client game host. No such game runtime adapter is available in this build, so gameplay hosting is explicitly marked unavailable. Opening Minecraft in its own app is offered only as an optional compatibility fallback.
- A Cloudflare Worker with D1 schema, state+PKCE Discord OAuth, server-side guild-role synchronization, signed sessions, RBAC/moderation endpoints, entitlement-filtered catalog metadata, and consented private diagnostic reports. Production routes need deployment secrets, database/bucket setup, and review.

The launcher does not include Minecraft binaries or assets and does not alter Minecraft's private storage. Imports stay in Oblivion until the user shares them through Android's document/content mechanisms; importing into this app does not mean Minecraft installed the content.

## Build

Requirements: Java 17 and Android SDK Platform 36 (the package supports API 26+; target device is Android 12).

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

To configure the public HTTPS API origin for Discord OAuth, add `-POBLIVION_API_BASE_URL=https://your-api.example`. This setting contains no secret; OAuth client secrets and bot credentials stay in Cloudflare Worker secrets.

Debug APK output: `app/build/outputs/apk/debug/app-debug.apk`.

The Debug APK is intentionally unsigned. Android will not install it directly; sign a local test copy with a locally generated test key before using it on a device. CI never receives, creates, or uploads a signing key, and does not produce a release APK.

## Backend

```sh
cd backend
npm test
npm run dev
```

See [Deployment](docs/DEPLOYMENT.md) and [Implementation status](IMPLEMENTATION_STATUS.md). No credentials are needed for the local health endpoint or tests. Never put server credentials in Android resources or `BuildConfig`.

Oblivion is independent and is not affiliated with Mojang, Microsoft, Discord, CurseForge, Minecraft, Minecraft Dungeons, or Ave Mujica.
