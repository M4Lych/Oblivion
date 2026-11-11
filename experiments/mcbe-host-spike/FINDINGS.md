# MCBE in-app host spike: findings

Reviewed: 2026-11-11. This is an isolated investigation and Android metadata-probe app. No third-party source, runtime, APK, native library, DEX, asset, token, or credential was copied into Oblivion.

## Verdict

**AUTHORIZATION BLOCKED.** The reference launchers demonstrate that an integrated Bedrock Activity can be made to work with game-version-specific internals. They do not expose an SDK or grant Oblivion permission to load or reuse the proprietary Minecraft runtime. No public, documented Minecraft Android host interface or written authorization was available for this experiment. The required licensed runtime, activity/lifecycle contract, render/input integration, asset contract, and entitlement/authentication handoff are therefore absent.

This is not a claim that such an implementation is physically impossible. It means no implementation satisfying the experiment's authorization and Android-sandbox constraints can currently be built or honestly demonstrated. A frame from a blank `GameActivity`, an external app launch, or an Activity Embedding demonstration would not be a gameplay host.

## Reference findings

### LeviLaunchroid

**Verified from public source:** the Android repository is a multi-module Kotlin/Java Android app with an `app` module and a Minecraft compatibility module. `MinecraftLauncher` builds an Intent for its own `MinecraftLoadingActivity`; that loading/runtime path transitions to the launcher-owned Minecraft-namespaced Activity. Its `com.mojang.minecraftpe.MainActivity` extends Android Games `GameActivity`. The implementation includes game-specific activity/lifecycle and input behavior rather than delegating to another app's launcher Activity.

`GamePackageManager` discovers the official package and its base/split APK metadata, chooses ABI-specific library paths, reads package APK contents, prepares native libraries and assets, and loads required game libraries such as `libminecraftpe.so`. The launch path also prepares game version/profile paths and calls the game Activity lifecycle. These are Minecraft implementation details, not general Android services.

Levi's Android requirements page currently specifies Android 9+, ARM64, a Google Play Minecraft install at 1.21.80 or newer, at least 1 GB available memory (3 GB recommended), and 2 GB free storage (5 GB recommended). Those are LeviLauncher requirements only, not proof that Oblivion or the M21 works.

Sources: [Android repository](https://github.com/LiteLDev/LeviLaunchroid), [`MinecraftLauncher`](https://github.com/LiteLDev/LeviLaunchroid/blob/main/app/src/main/java/org/levimc/launcher/core/minecraft/MinecraftLauncher.java), [`GamePackageManager`](https://github.com/LiteLDev/LeviLaunchroid/blob/main/app/src/main/java/org/levimc/launcher/core/minecraft/GamePackageManager.kt), [Minecraft `MainActivity`](https://github.com/LiteLDev/LeviLaunchroid/blob/main/minecraft/src/main/java/com/mojang/minecraftpe/MainActivity.java), [requirements](https://docs.levimc.org/LeviLauncher/guide/requirements-installation?platform=android).

**Boundary:** this confirms a real integrated implementation exists, but not a supported integration route for third-party clients. The repository's Apache-2.0 license covers only the source it licenses; it does not grant Minecraft/Mojang binaries, assets, private interfaces, or separately licensed components.

### Android `GameActivity`

Android's official GameActivity documentation describes it as an Activity, SurfaceView, input/lifecycle bridge, and native glue for C/C++ game code built into the application. The application must supply the native engine entry point/library. GameActivity does not discover, authorize, or provide the Minecraft engine from a different package.

Sources: [GameActivity guide](https://developer.android.com/games/agdk/game-activity), [integration steps](https://developer.android.com/games/agdk/game-activity/get-started).

### Selaura Android launcher

**Verified from public source:** its Kotlin launcher reads the installed Minecraft package's base/split APK paths and native-library directory. It extracts DEX/native content, mutates `BaseDexClassLoader` internals reflectively (`pathList`, `addDexPath`, `addNativePath`), loads its own Selaura library from a CDN-managed location, and constructs an Intent for a `com.mojang.minecraftpe.Selaura` Activity. That Activity extends its game-specific `MainActivity`, appends APK asset paths reflectively, and loads the Selaura native library. This is a launcher plus a Bedrock native modification framework, not a general-purpose host SDK.

Its Gradle app configuration sets minSdk 28, compile/target SDK 35 and an `arm64-v8a` ABI filter; the separate launcher library compiles against a local `minecraft.jar` and stubs. These settings demonstrate its own build target, not a public runtime contract or redistributable game component. The public app build script also contains release-signing values; none were reproduced, and they should be treated as compromised and rotated by that project's maintainers.

The repository README describes Google Sign-In for package retrieval/display. That is not evidence of Microsoft authentication, Xbox identity verification, or Minecraft entitlement verification and cannot be adopted as Oblivion's ownership flow.

Sources: [launcher source](https://github.com/selauraclient/android-launcher), [`Launcher.kt`](https://github.com/selauraclient/android-launcher/blob/master/app/src/main/java/com/selauraclient/launcher/utils/Launcher.kt), [`Selaura` Activity](https://github.com/selauraclient/android-launcher/blob/master/launcher/src/main/java/com/mojang/minecraftpe/Selaura.kt), [app Gradle configuration](https://github.com/selauraclient/android-launcher/blob/master/app/build.gradle.kts), [launcher library configuration](https://github.com/selauraclient/android-launcher/blob/master/launcher/build.gradle.kts), [README and license](https://github.com/selauraclient/android-launcher/blob/master/README.md).

**Boundary:** reflective class-loader mutation, extracting proprietary game DEX/native code, and relying on private game classes are not used by Oblivion. The launcher is GPL-3.0; checked-in Minecraft-named artifacts and downloaded native components need separate provenance and redistribution review. No such artifacts were reused.

### Selaura and LimerenceMC/client

**Verified from public source:** LimerenceMC/client is a fork of Selaura. Both describe a C++ Bedrock utility/modification client rather than an independent game host. Limerence's Android entry point `dlopen`s `libminecraftpe.so`, resolves `android_main` and `ANativeActivity_onCreate` using `dlsym`, forwards the engine entry points, and starts its mod initialization. This expects a Minecraft runtime process and is not a way for an ordinary app to acquire one.

Limerence's README says Android 9+ / ARM64, but its checked-in `CMakePresets.json` sets `ANDROID_ABI` to `x86_64` for both Android presets. A caller could try overriding the preset, but that would not establish that all code/dependencies build for ARM64. The upstream Selaura CMake declares C++23 and does not contain an explicit Android target branch; the fork adds an Android macro branch. Neither project was built here.

The CMake file fetches several dependencies using floating `master`/`main` refs. It includes `cpp-i18n`, whose inspected upstream license is AGPL-3.0, and Dobby (Apache-2.0); each source/dependency revision and full license set would need a separate audit before integration. The primary projects are GPL-3.0. No component was copied or vendored.

Sources: [Limerence fork relationship](https://github.com/LimerenceMC/client), [Limerence README](https://github.com/LimerenceMC/client/blob/master/README.md), [Android ABI presets](https://github.com/LimerenceMC/client/blob/master/CMakePresets.json), [CMake dependencies](https://github.com/LimerenceMC/client/blob/master/CMakeLists.txt), [native entry point](https://github.com/LimerenceMC/client/blob/master/src/load/main.cpp), [Selaura upstream](https://github.com/SelauraClient/Selaura), [Selaura CMake](https://github.com/SelauraClient/Selaura/blob/master/CMakeLists.txt), [cpp-i18n license](https://github.com/Sinan-Karakaya/cpp-i18n/blob/main/LICENSE), [Dobby license](https://github.com/selauraclient/Dobby/blob/master/LICENSE).

## Architecture comparison

| Project | Role | How gameplay/runtime is reached | Android/ARM64 evidence | Oblivion disposition |
|---|---|---|---|---|
| LeviLaunchroid | Integrated launcher and game Activity host | Launcher-owned game Activity plus version-specific package/asset/native preparation | Maintainer documents ARM64; no M21 test by Oblivion | Reference only; Apache license does not license Minecraft internals |
| Selaura Android launcher | Launcher for Selaura mod | Reflective DEX/native class-loader paths, game-specific Activity/assets, native library | README says Android 9+; source assumes arm64; no Oblivion verification | Reference only; GPL-3.0 and proprietary artifact concerns |
| Selaura | Bedrock native mod/framework | Runs within a compatible Bedrock runtime | README claims Android ARM64; upstream CMake not explicit for Android | Reference only; GPL-3.0 |
| LimerenceMC/client | Selaura fork / Bedrock native mod | `dlopen`/`dlsym` against `libminecraftpe.so` | README claims ARM64; both checked-in Android presets select x86_64 | Reject as host; reference only; GPL-3.0 plus dependency audit |

## Oblivion spike implemented

The isolated app in this directory is a real compatibility/host-boundary probe, not a gameplay host. It:

- Queries Android-visible package metadata for `com.mojang.minecraftpe` using `PackageManager` and an explicit package-visibility `<queries>` declaration.
- Reports version/split count/exported activity metadata, device API, process bitness, supported ABIs, available/total memory, and reported OpenGL ES level.
- Treats installer package metadata as informational only; it does **not** verify ownership or entitlement.
- Shows loading, completed, and recoverable error states, with a manual retry and exit action.
- Re-runs the idempotent probe after Activity recreation, cancels its worker on destruction, and logs create/start/resume/pause/stop/destroy plus probe outcomes.
- Writes bounded JSONL logs only to the experiment app's private files directory. It does not request storage/network permissions.

It does **not** inspect APK/split contents, touch Minecraft's private files, load DEX/classes/native libraries, construct a game Activity, create a rendering surface, launch an external game, or claim gameplay. The report explicitly states the host limitation.

## Proposed authorized host design

If Mojang/Microsoft or an authorized runtime provider supplies a documented SDK and written integration scope, isolate it behind an Oblivion-owned `McbeRuntimeProvider`:

1. `HostSessionCoordinator` owns a cancellable state machine: `CHECKING_COMPATIBILITY -> INITIALIZING -> RUNNING -> EXITING`, with explicit `UNAVAILABLE`, `FAILED`, and `INTERRUPTED` outcomes.
2. The provider reports supported game/build versions, ABI, authentication/entitlement state, minimum memory/storage/GPU requirements, and a diagnostic-safe failure reason.
3. The provider creates the actual authorized game Activity/surface and defines lifecycle, input, rendering, pause/resume, and termination contracts. Oblivion must not fabricate this contract with `GameActivity`.
4. Oblivion owns the surrounding UI, account/profile/cosmetic state, news, settings, logs, and server/content metadata. Only documented APIs may transfer supported worlds/packs/settings or apply cosmetics to the running game.
5. All authentication and ownership checks remain with official/provider flows. The provider must not require access to another app's private sandbox or redistribution of proprietary runtime files.
6. Every provider event is logged through the redacted diagnostic layer; process death restores `INTERRUPTED`, never a false `RUNNING` state.

Until that interface and permission exist, the production `GameLaunchManager` remains unchanged and its optional external fallback remains explicitly separate-app behavior.

## Galaxy M21 assessment

| Item | Evidence/status |
|---|---|
| OS target | User target is Android 12 (API 31); this specific device was not queried in this run. |
| CPU/GPU | Samsung identifies Exynos 9611 and Mali-G72 MP3. |
| RAM/storage | Samsung lists 4/64 GB and 6/128 GB variants. Exact variant and free storage are unknown. |
| ABI | ARM64 is expected from the Exynos Cortex-A73/A53 platform; actual `Build.SUPPORTED_ABIS` and 64-bit process status require running this probe on the device. |
| Graphics | GPU model is known from public specs; OpenGL ES level, driver behavior, Vulkan support, surface behavior, and game compatibility are untested. |
| Minecraft | Installed package, build, split layout, account entitlement, and game runtime compatibility are unknown without device testing. |
| Memory/performance | No runtime memory budget or frame timing was measured. Levi's minimum/recommended RAM values are not Oblivion measurements. |

Sources: [Samsung Galaxy M21 specifications](https://news.samsung.com/in/samsung-launches-wattamonster-galaxy-m21-2021-edition), [Samsung Exynos 9611 specifications](https://semiconductor.samsung.com/processor/mobile-processor/exynos-9611/).

## Build and runtime evidence

Build command attempted:

```sh
timeout 90s ./gradlew --no-daemon --info -p experiments/mcbe-host-spike :app:assembleDebug
```

Result: **failed before Java/Kotlin compilation** with `SDK location not found`; neither `ANDROID_HOME`/`ANDROID_SDK_ROOT` nor a local `sdk.dir` is configured and no Android SDK directory was present. No APK was produced. The Gradle configuration resolved AGP 8.13.2 and Kotlin Android 2.3.0 under JDK 17.0.18 on Alpine Linux aarch64. The probe has not run on an emulator or physical device, so package metadata, Activity lifecycle, rendering, input, and gameplay have no runtime test result.

No Android SDK/NDK/GameActivity/native dependencies were added to the production project. The experiment's Activity and probe sources remain uncompiled in this environment.

## Next legitimate steps

1. Obtain a documented Minecraft Bedrock Android embedding/runtime SDK or written publisher authorization that explicitly permits the host Activity/session, lifecycle, input/render surface, assets, native runtime linkage, and supported distribution model.
2. Obtain the authorized SDK from its provider with a versioned license and Android 12 / ARM64 support declaration; separately audit every transitive dependency and license.
3. Build the isolated spike on a standard Linux Android SDK runner, then implement only the documented provider adapter and state lifecycle.
4. Install only a user-owned official Minecraft copy on a Galaxy M21; verify API/ABI/RAM/storage/GLES, authentication and entitlement through supported flows, then capture evidence for rendering, touch/input, pause/resume, termination, and error logs.
5. Keep the production host unavailable until that end-to-end test passes. If no authorized runtime interface is offered, the gameplay-host acceptance criterion remains blocked.

## Phase 5 validation preparation

- Extended the probe's displayed public device metadata to include manufacturer/model, Android release/API, supported ABI/process bitness, memory, and the device-reported GLES level. Package lookup remains limited to the explicitly declared `com.mojang.minecraftpe` visibility query; installation, installer, or version metadata still does not imply ownership.
- Added JVM unit tests for the report's installed/missing states and explicit non-entitlement/non-gameplay wording. These tests use synthetic M21-like report values and do not access PackageManager or represent a physical-device result.
- Added an isolated `mcbe-probe` GitHub Actions job that uses Ubuntu, JDK 17, Android API 36 and Build Tools 35.0.0, runs probe lint/unit-test/assemble tasks, checks APK structure/package identity, and uploads a 14-day Debug artifact. The existing production Android job remains separate.
- Added [DEVICE_TEST_PLAN.md](DEVICE_TEST_PLAN.md). No physical M21 or emulator is attached, so installation, package detection, lifecycle, log output, memory and stability remain untested.
- Ran `timeout 150s ./gradlew --no-daemon -p experiments/mcbe-host-spike :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`. Gradle failed while resolving `:app:lintReportDebug` dependencies with `SDK location not found`, before lint, unit tests, or Kotlin compilation could execute. No APK exists.
- Ran `timeout 150s ./gradlew --no-daemon :app:lintDebug :app:testDebugUnitTest :app:assembleDebug` for production. It failed on the same missing Android SDK, before the production lint/tests/compile ran. The workflow has not been executed: it can validate only the committed revision selected on GitHub, while this worktree is uncommitted.
- The production `GameLaunchManager`, host-unavailable state, and separate-app fallback were not changed. No host code was merged into `app/`.
