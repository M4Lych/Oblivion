# Oblivion API

Cloudflare Worker with a D1 schema and private R2 report bucket. Local tests use Node's built-in test runner and do not perform real provider authentication.

## Local development

```sh
npm install
npm test
npm run db:migrate:local
npm run dev
```

Local `wrangler.toml` has a placeholder D1 ID and non-production URLs. Set local-only values in ignored `backend/.dev.vars`; never commit them. D1 local migrations create the schema. The production auth flow additionally needs a 32+ character `SESSION_HMAC_SECRET`, Discord app secret, exact callback configuration, guild ID and bot token for guild verification, and the owner identity. Use Wrangler secret storage, not `.dev.vars` for deployments.

## Implemented routes

- `GET /api/health`, `GET /api/config`: service and boolean feature availability.
- `POST /api/discord/oauth/start`: creates short-lived OAuth state and PKCE challenge; returns an official Discord authorize URL only when configured.
- `GET /api/discord/oauth/callback`: validates/consumes state, exchanges Discord code server-side, creates/loads Oblivion account, verifies optional guild membership with the bot API, syncs configured non-owner roles, and redirects with a one-time app exchange code.
- `POST /api/discord/oauth/exchange`: consumes that one-time code and returns a signed short-lived API session.
- `GET /api/me`: server-derived account, roles, linked Discord identity, and separate Microsoft/Xbox/Minecraft verification flags.
- `GET /api/catalog`: lists enabled metadata; entitlement state is computed server-side.
- `POST /api/reports`, `GET /api/reports`, `GET /api/reports/:id`: consented, bounded, redacted uploads to private R2 and staff-authorized retrieval.
- `POST /api/admin/users/:id/roles`, `POST /api/admin/users/:id/status`: role/moderation actions with server permission checks, owner protection, expiration checks, history, and audit events.
- CurseForge search and unsupported admin areas return explicit configuration/not-implemented states.

## Configuration

See root `.env.example`. Runtime secrets are `SESSION_HMAC_SECRET`, `DISCORD_CLIENT_SECRET`, `DISCORD_BOT_TOKEN`, and `CURSEFORGE_API_KEY`. `OWNER_USER_ID` or `OWNER_DISCORD_USER_ID` is provisioned by the maintainer. `DISCORD_ROLE_MAP_JSON` maps exact trusted Discord role IDs to Oblivion roles. Discord OAuth alone assigns no Member role; the bot must verify actual guild membership. Owner and Admin are excluded from Discord role mapping.

Before production, review OAuth redirects, privacy/retention, rate limits, role provisioning, D1/R2 permissions, account unlink/delete flow, secret rotation, and abuse monitoring. No deployment is configured.
