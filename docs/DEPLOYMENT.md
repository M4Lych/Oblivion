# Deployment

## Android

Install Java 17 and Android SDK Platform 36, then use `./gradlew :app:testDebugUnitTest :app:assembleDebug`. To enable the Android Discord client, pass the public HTTPS API origin with `-POBLIVION_API_BASE_URL=https://your-api.example`; this is a public endpoint, not a credential. No release signing is configured.

## Worker local development

The Worker uses Wrangler and D1. Install dependencies in `backend/`, run `npm test`, then `npm run dev`. Local development uses the checked-in placeholder config and local D1. Wrangler's local D1 runtime depends on its native `workerd` binary; Alpine/musl or Android/Termux environments may not be supported. In those environments, run Worker/D1 development on a supported glibc host or Cloudflare. D1 enforces declared foreign keys by default; the migration must not try to toggle `PRAGMA foreign_keys` inside its transaction. Do not deploy until routes, authentication, migrations, data retention, rate limiting, and secret handling are reviewed.

## Required service setup (future)

- Cloudflare account, Workers project, D1 database, R2 bucket, and migration application.
- HTTPS API domain.
- Discord application, redirect URI, bot membership/permissions if membership lookup is enabled, guild and approved role mapping.
- Microsoft app registration with correct Android package/signature redirect. Xbox/Minecraft APIs require separate supported access/eligibility.
- CurseForge API approval/key if their official API is used.
- Owner identity provisioned through a controlled initial migration or one-time administrative process, not public client registration.

The Termux CLI is `node tools/oblivion.mjs`. Set `OBLIVION_API_BASE_URL` and `OBLIVION_ADMIN_TOKEN` in the current shell only. It never saves or prints the token. Available commands are `health`, `stats [fromEpoch toEpoch]`, `users [query]`, `roles`, `reports`, and `version`.

Secrets go through Worker secrets (e.g. `wrangler secret put DISCORD_CLIENT_SECRET`), never checked-in files or the APK. Automatic deployment is not configured.
