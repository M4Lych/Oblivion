# Platform and service limitations

- Android apps run under separate UIDs/sandboxes. An app cannot instantiate another app's private Activity class or access its private files. Supported cross-app interaction is through exported APIs/intents.
- Android cross-app activity embedding requires Android 13+ and opt-in by the activity being embedded. The target Galaxy M21 runs Android 12 and cannot meet the API level; the inspected Minecraft materials do not supply an Oblivion embedding SDK or opt-in contract.
- Bedrock Android engine/runtime is proprietary native game code. The public Android GameActivity library provides lifecycle glue for native code built into that app; it is not an engine delivery interface.
- Android scoped storage prevents direct private Minecraft file/database editing; Oblivion uses SAF and app-private copies.
- Microsoft sign-in does not establish Xbox identity or Minecraft ownership. No game entitlement result is claimed.
- MSAL for Android authenticates a Microsoft identity and obtains tokens for registered protected APIs; it is not by itself Xbox sign-in or Minecraft entitlement verification. Microsoft's documented Xbox Services social APIs are title/GDK-oriented; no approved generic Android API for changing Minecraft friend relationships was identified for this client.
- Launcher-side skins/cosmetics/settings are not automatically visible in vanilla Minecraft. Bedrock server/pack/add-on support is separate and content-specific.
- Minecraft friend requests and direct server joins are not implemented through undocumented endpoints or game-memory automation.
- CurseForge requests need approved API access and must filter Java/Bedrock compatibility. No API key or backend proxy is currently configured.
- External Minecraft app launch is an optional compatibility fallback only, not in-client hosting.
