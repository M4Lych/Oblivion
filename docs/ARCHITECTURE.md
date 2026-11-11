# Architecture

## Repository layout

- `app/`: single-activity Kotlin app, Compose screens, local domain validation, DataStore preferences, app-private world/skin storage, launch management, diagnostics.
- `backend/`: Cloudflare Workers API, D1 schema, server-side identity/RBAC, and private report storage contracts.
- `docs/`: product, API, security, storage, deployment, and platform decisions.
- `tools/`: Termux-compatible service administration client (not yet implemented).
- `.github/workflows/`: CI build and test automation.

## Android flow

Compose UI uses Navigation Compose. `OblivionPreferences` wraps DataStore Flows. File import uses Android's document picker and stores a validated copy in the app-private files directory. `WorldLibrary` and archive policy are isolated from rendering. `AppLogger` writes redacted bounded local logs. `GameLaunchViewModel` coordinates an explicit host-capability check and lifecycle-safe fallback messaging.

The game runtime is not an Oblivion dependency in this build. A future authorized integration can implement `InClientGameHost` only when it can provide a real session surface, documented authentication, lifecycle management, and distribution rights. The current `UnconfiguredGameHost` rejects hosting instead of claiming success.

## Backend flow

Cloudflare Worker is the HTTP boundary; D1 schema exists for users, linked identities, roles/history, moderation, entitlements, catalog, reports, OAuth state, one-time app codes, and audit rows. R2 stores private diagnostic objects. Worker routes implement Discord OAuth, session exchange, server-derived account/roles, catalog entitlements, report upload/review, role changes, and moderation. Cloudflare secrets, production D1/R2, identity setup, and operational review are still required. No client-supplied role value can be trusted.

## State ownership

- Device preferences, imported library copies, and local logs: Android app-private storage.
- Identity, roles, entitlements, moderation, marketplace, and reports: backend source of truth once implemented.
- Minecraft app data and running game state: owned by official Minecraft. Oblivion does not inspect or mutate it.

## Future session integration seams

Account/profile services, launcher-side cosmetics, approved content repositories, settings presets, news, server notes, and diagnostic capture are kept in Oblivion-owned features. A future Minecraft adapter must explicitly define which of these can be handed to or rendered by the game and must report game import/activation only after confirmation from an authorized supported API.
