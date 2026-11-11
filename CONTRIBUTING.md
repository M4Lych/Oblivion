# Contributing

1. Use Java 17, Android SDK Platform 36, Node 18+, and the Gradle wrapper.
2. Keep Android changes in `app/` and Worker changes in `backend/`.
3. Add tests for pure decision logic and archive validation.
4. Never add credentials, signing material, Minecraft game files, unauthorized Marketplace assets, or local `.dev.vars` files.
5. State clearly when a feature is client-local, service-configured, optional fallback, or unsupported by platform APIs.

Run `./gradlew :app:testDebugUnitTest :app:assembleDebug` and `npm test` from `backend/` before proposing changes when the required toolchains are available.

The project license is pending maintainer selection. Until then, no contribution license or redistribution permission is implied.
