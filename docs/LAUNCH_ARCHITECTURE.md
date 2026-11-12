# Minecraft Bedrock launch architecture investigation

## Existing project and local reference inspection

The target repository was a clean Git checkout on `main` at its initial README commit. The adjacent `/public/Oblivion` checkout is a separate, dirty worktree on `oblivion/m14-device-fixes`, with an `origin` pointing to `M4Lych/nexus-bedrock-android`. It was inspected read-only as legacy reference; none of its files were copied or edited.

That local implementation has a launcher-owned `MinecraftActivity` subclassing `com.mojang.minecraftpe.MainActivity`, and that class extends Android Games `GameActivity`. It prepares a version, APK/split inputs, native library directories and per-profile paths, creates an activity launch intent, loads native libraries and a preloader, calls the game superclass lifecycle, adapts keyboard/touch/input, tracks pause/destroy, and restarts launcher process after normal game exit. Its manifest registers launcher and loading/game activities. The `minecraft` Android library module provides Minecraft/Xbox/Microsoft namespaces and GameActivity-derived compatibility classes; the app also has NDK/CMake code and native binaries.

This is an application-specific integration, not a general Android facility. The local legacy worktree's third-party audit also says the game compatibility module, prebuilt `.so` files, and some assets lack complete source/provenance or redistribution evidence. We therefore use its structure as architectural evidence only; no class, native library, game asset, authentication endpoint, or binary was reused.

## Public LeviLauncher findings

The public Android source is in [LiteLDev/LeviLaunchroid](https://github.com/LiteLDev/LeviLaunchroid) (the older `LeviLauncher-Android` URL redirects to the renamed project); its [Apache-2.0 license](https://github.com/LiteLDev/LeviLauncher-Android/blob/main/LICENSE) applies to that source repository. The public [game package manager](https://github.com/LiteLDev/LeviLaunchroid/blob/main/app/src/main/java/org/levimc/launcher/core/minecraft/GamePackageManager.kt) inspects official package/split APK paths, resolves ABI-specific native-library directories, extracts/copies game `.so` files, creates an AssetManager, and loads required libraries. Its Minecraft compatibility module supplies a Minecraft-namespaced `MainActivity` based on Android `GameActivity`; launcher-side activity code manages lifecycle and game-specific runtime preparation. The upstream guide requires a legitimate Google Play copy, ARM64 and Android 9+ and documents managed game versions and content management. [LeviLauncher requirements](https://docs.levimc.org/LeviLauncher/guide/requirements-installation?platform=android), [native-mod guide](https://docs.levimc.org/LeviLamina/developer_guides/android/).

The [LeviLamina Android native-mod guide](https://docs.levimc.org/LeviLamina/developer_guides/android/) confirms its native mods are loaded through LeviLauncher-specific Preloader SDK, packaged as `.so` modules, and require exact version/lifecycle handling. Android `GameActivity` itself is a Jetpack component for a game whose native C/C++ implementation is built into that app; it does not provide another app's engine. [Android GameActivity documentation](https://developer.android.com/games/agdk/game-activity).

General Android building blocks include Activities and lifecycle callbacks, PackageManager metadata, application contexts and native library loading APIs. The Minecraft activity superclass, runtime package metadata, exact native library set, ABI/version preparation, asset wiring, authentication integration and preloader are game/launcher-specific. No code from LeviLauncher is reused. Apache-2.0 permits some reuse subject to its terms, but that license does not grant rights to Minecraft, Mojang/Microsoft SDK material, third-party binaries, the Preloader SDK, or bundled art. Oblivion's project license and complete dependency review are also pending.

## Architecture decision

Oblivion uses a host-provider boundary. `InClientGameHost` describes whether an authorized provider can offer a real host session; `UnconfiguredGameHost` returns unavailable. `GameLaunchViewModel` coordinates states, logs the check, restores transient operations as interrupted after process recreation, and handles an explicit optional external app fallback. The Play action always runs this manager check first. The separate-app action is labeled as a fallback and is never called a hosted session.

This is the closest safe implementation possible with current supported interfaces. Full gameplay hosting is not implemented because the official Minecraft Android app does not expose a documented embedding/runtime SDK in the inspected materials. Android cross-app Activity embedding is available only on Android 13+ and requires the target application to opt in; it cannot be enabled by Oblivion. [Android activity embedding documentation](https://developer.android.com/develop/ui/views/layout/activity-embedding). The target Samsung Galaxy M21 runs Android 12, so it does not meet that API requirement. Even on Android 13+, visual activity embedding would not make the game engine part of Oblivion or provide authorized access to game state/data.

The alternative LeviLauncher-style architecture requires an authorized game compatibility module, the game's Activity and resource contract, signed/official APK and split handling, native dependency loading for supported ABIs, game-specific authentication/entitlement flow, version compatibility tracking, and legal redistribution/use rights. Those components are neither a generic SDK nor present in the new Oblivion project. Creating a fake `GameActivity` around the placeholder UI or loading undocumented game binaries would misrepresent capability and violate project security boundaries.

## Launch states implemented

- `PREPARING_IN_CLIENT`: capability check is running.
- `IN_CLIENT_UNAVAILABLE`: no authorized host provider exists; detailed reason shown.
- `EXTERNAL_FALLBACK_STARTING`: user explicitly chose separate-app launch.
- `EXTERNAL_FALLBACK_RETURNED`: Oblivion resumed; message states that no hosted session exists.
- `FAILED`: launch/fallback error with retry guidance.
- `INTERRUPTED`: transient state recovered after process death; no session is claimed/restored.

All state changes are logged through redacting bounded local diagnostics. Failed fallback launch is recoverable from the screen. Game state is not retained because no in-client runtime exists.

## Dependencies and platform requirements

Current app dependencies: AndroidX, Compose, Navigation, lifecycle/ViewModel, DataStore. No NDK, GameActivity, JNI, preloader, Minecraft compatibility module, or game binary is included. The project targets Android API 35, minimum API 26, and the Samsung Android 12 target.

To pursue a real host implementation, the maintainers need a documented and authorized Android SDK or written game publisher permission covering runtime integration, app identity, auth/ownership, storage, native distributions, and supported versions/ABIs. The provider contract and state management can then be extended and tested against that SDK. A compatible physical test device and legitimately licensed game install are required for integration verification.

## Current evidence

Source inspection and unit tests can verify state transitions, safe interruption, and UI fallback labels. This environment has no Java/Android SDK or attached device, so the Android build and real game launch have not been verified here. No claim of in-client gameplay is made.

## Experimental native loader scaffold

The app now includes a non-exported `OblivionGameActivity` and a small JNI/CMake loader diagnostic. It checks only for explicitly staged files in Oblivion's private `files/mcpe_env` directory and attempts to map `libminecraftpe.so` after optionally mapping staged `libc++_shared.so` and `libfmod.so`. It does not copy files from Minecraft's package, change linker namespaces, invoke an engine entry point, create a rendering surface, or provide lifecycle/input integration. A missing or rejected library is reported as failure. A successful `dlopen` is reported only as library mapping, never as gameplay. The production `GameLaunchManager` still uses `UnconfiguredGameHost`; this experimental Activity is not wired to Play.
