# MCBE host compatibility spike

This standalone Android app is isolated from Oblivion's production Gradle build and launcher navigation. It performs a real, user-triggered `PackageManager` and device-capability probe for `com.mojang.minecraftpe`, logs lifecycle/probe events to app-private diagnostics, and reports whether a public host adapter is available.

It deliberately does not launch Minecraft, load its classes or libraries, read its APK contents, access its private data, or create a fake gameplay surface. The probe can establish installation metadata and device facts, but it cannot establish entitlement or authorize runtime loading.

## Build attempt

From the repository root:

```sh
./gradlew --no-daemon -p experiments/mcbe-host-spike :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Requirements: JDK 17, Android SDK Platform 36, Android Gradle Plugin 8.13.2, Kotlin 2.3.0. The command was attempted under JDK 17.0.18 on Alpine aarch64. Gradle configured the project and resolved the Android/Kotlin plugins, then failed before compilation because no Android SDK path was configured (`SDK location not found`). No APK was produced. An Android SDK runner and a compatible device/emulator are still needed.

## Result boundary

`NO_AUTHORIZED_IN_APP_RUNTIME` is the expected outcome when the Minecraft package is installed. The probe is an implemented compatibility diagnostic, **not** a gameplay host and not evidence that Minecraft ran inside Oblivion. A real session requires an authorized, documented runtime/embedding interface and its authentication, asset, lifecycle, rendering, and input contracts.

The report also displays manufacturer/model using Android's public `Build` fields. Unit tests cover the report text and the distinction between package detection and runtime/entitlement claims; they do not simulate a device or query PackageManager. See [manual device validation](DEVICE_TEST_PLAN.md) for the physical test procedure. The `mcbe-probe` GitHub Actions job builds, lints, tests, validates the APK package identity, and uploads a short-lived Debug artifact. A workflow run tests only the committed revision selected for that run.
