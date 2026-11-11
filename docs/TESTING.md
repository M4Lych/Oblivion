# Testing

Android unit tests target pure policies: launch interruption state, saved-server codec and address validation, archive path and world archive validation, and log redaction. Android integration and physical device tests require Android SDK/device; none are claimed without execution.

Backend tests use Node's built-in runner and cover role hierarchy, owner protection, Discord membership mapping, entitlement decisions, route disabled states, OAuth state/PKCE URL construction and validation, session signing/expiry, redaction, CORS, bounded request parsing, and health checks. They do not substitute for live Discord, Microsoft, CurseForge, Cloudflare, or device integration. The D1 migration is additionally syntax-checked with SQLite; this does not replace applying it through Wrangler against a D1 instance.

Commands:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
cd backend && npm test
```
