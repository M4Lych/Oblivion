# Implementation status

## Working local features

- Compose launcher navigation and responsive dashboard/profile/library/community/settings/diagnostics screens.
- Local display name, analytics/reduced-motion preferences, and validated saved-server notes via DataStore.
- Bedrock-size PNG skin import into app-private files with a persistent preview selection; the preview uses an original character illustration and a sampled skin color, not a full 3D/game rendering.
- `.mcworld` import to private library, file size/zip/path/`level.dat` checks, duplicate detection, share and delete operations.
- Local structured diagnostics with rotation and redaction.
- Explicit launch check and optional separate-app fallback. Hosted gameplay is unavailable.
- Android Discord OAuth start/browser callback, Keystore-backed PKCE/session storage and sign-out, plus Worker OAuth state+PKCE, actual guild membership verification, signed session exchange, D1 account/role/audit schema, entitlement-filtered catalog metadata, protected report upload/read APIs, role and moderation endpoints, health checks, and backend unit tests. Live operation requires an HTTPS deployment, Discord app/bot, secrets and database/bucket setup.

## Not active

Microsoft/Xbox/Minecraft auth, CurseForge proxy, backend news, friends, owner analytics/admin API/UI, privacy/account deletion endpoints, diagnostic upload UI, and production deployment. Discord Android/backend integration is implemented but needs configuration and review before live use. The integrated Minecraft gameplay host is not implemented: the official Android game exposes no supported runtime/embedding interface in the materials reviewed, and cross-app activity embedding requires opt-in from the target app. The separate-app handoff remains a clearly labeled optional fallback only.

See `docs/LAUNCH_ARCHITECTURE.md` for the central gameplay-hosting blocker and `docs/FEATURE_SPECIFICATION.md` for per-feature status.
