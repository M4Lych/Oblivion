# Authentication

Oblivion identity, Microsoft identity, Xbox identity, Minecraft entitlement, and Discord identity are distinct.

Microsoft sign-in should use official MSAL for Android after app registration and redirect setup. This can verify Microsoft identity only; Xbox and Minecraft entitlement require additional supported official APIs and are not implemented/configured. No password collection or token logging is allowed.

Discord uses the official OAuth2 authorization-code flow with state and PKCE. The Android client creates its own PKCE verifier and stores it with the short-lived Oblivion session using Android Keystore-backed AES-GCM encryption. It opens only the validated `https://discord.com/oauth2/authorize` URL. The Worker stores the confidential Discord client secret, validates state, exchanges Discord's code, and issues a short-lived one-time app code; the Android client exchanges that with its PKCE verifier. Guild membership must be checked server-side using an authorized bot/API integration before assigning Member. OAuth success alone is not membership proof. Session revocation is requested on sign-out; local credentials are cleared even if the service is unreachable.

Set the public service URL at build time with `-POBLIVION_API_BASE_URL=https://your-api.example`. This URL is not a secret. Do not put Discord credentials or bot tokens in Gradle properties. A configured production service still needs an exact `oblivion://auth/discord` callback, Discord app and bot setup, D1 migrations, secret storage, HTTPS deployment, and abuse/privacy review. Session refresh, account deletion, and identity unlinking are not implemented and must be addressed before production account creation.
