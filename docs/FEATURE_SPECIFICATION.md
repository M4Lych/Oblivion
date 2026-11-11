# Feature specification and status

| Feature | Current behavior | Status |
|---|---|---|
| Dashboard | Original dark responsive launcher UI, local player, install presence | Implemented locally |
| Play | Calls host availability manager; optional separate-app fallback | Host unavailable; fallback works only on Android device |
| Profile | Device-local display name and account-link status placeholders | Local only |
| Character | Imports Bedrock-size PNG, stores private copy, tint preview | Partial; no 3D/game skin application |
| World library | Validates and copies `.mcworld`, lists/shares/deletes local copy | Implemented locally; no direct MC import |
| Resource/behavior packs | No supported game import surface configured | Not implemented |
| Settings | DataStore profile preferences and analytics opt-out default | Partial; no private game setting access |
| Marketplace/CurseForge | Backend catalog endpoint computes entitlements; app has honest empty state | No catalog entries or CurseForge proxy; API approval/policy required |
| News | Empty state; no invented source content | Feed not implemented |
| Community/friends | Not configured state; no remote account API | Not implemented |
| Discord | Android browser flow and callback, Android Keystore-backed PKCE/session storage, Worker OAuth state+PKCE, account creation, server-side guild check and role sync | Requires deployed HTTPS API, Discord app/bot, D1 setup and production review |
| Microsoft | Explicit disconnected state; backend/API model distinguishes sign-in, Xbox, entitlement | MSAL registration and Xbox/Minecraft verification not configured |
| Roles/admin | Worker role assignment/moderation endpoints, D1 history and audit checks | Admin client screens, live DB/secrets, and deployment not configured |
| Diagnostics | Redacted local logs, share and preview; backend has consented private R2 report routes | Upload UI/API URL and deployment not configured |
| Analytics | Preference toggle off by default; no telemetry sent | Local consent only |
| Termux CLI | Node CLI for health/stats/users/roles/reports/version; session supplied by environment | Requires deployed API and authorized token |
| Credits | Licenses and missing links shown honestly | Partial |
