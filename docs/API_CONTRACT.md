# API contract

Base URL is deployment-specific and must be HTTPS. The Android client must contain only the public base URL. Secrets such as Discord client secret/bot token and CurseForge key belong in Worker secrets.

The Worker implements `GET /api/health` and `GET /api/config`, Discord OAuth start/callback/exchange, `POST /api/auth/logout`, `GET /api/me`, `GET /api/catalog`, protected diagnostic report routes, and owner/admin role and status routes. Runtime endpoints fail closed when configuration/database bindings are missing. CurseForge remains an explicit `503` until API approval and a Bedrock compatibility policy are configured.

Planned authenticated contracts:

- `POST /api/discord/oauth/start`: accepts a client PKCE challenge and returns a Discord authorization URL with backend-generated OAuth state and PKCE.
- `GET /api/discord/oauth/callback`: consumes state, exchanges the Discord code server-side, verifies guild membership, and returns a short-lived one-time app code to the fixed callback URI.
- `POST /api/discord/oauth/exchange`: requires the client PKCE verifier and returns a 7-day signed session.
- `GET /api/me`: server-derived account, current roles, linked Discord identity, and separate unverified Microsoft/Xbox/Minecraft states.
- `POST /api/auth/logout`: revokes the caller's signed session and writes an audit event.
- `GET /api/catalog`: enabled metadata and backend-computed entitlement state.
- `POST /api/reports`: size-bounded, consented, redacted report upload with private object storage and report ID. Review routes require `report:review`.
- `POST /api/admin/users/{id}/roles` and `/status`: backend permission checks, owner protections, role/moderation history, and audit events.

User ID and roles are derived from a signed session and D1. Never accept a role, entitlement, or acting-user ID from request JSON as proof of authority. Discord app linking/unlinking, account deletion/export, CurseForge, friends, news authoring, analytics, and full entitlement mutation are not implemented yet. Live endpoints are not deployed.
