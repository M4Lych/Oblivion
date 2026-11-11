# Manual device validation plan

Use this only with the experimental probe APK produced by the hosted `mcbe-probe` job. This APK reports package/device metadata; it does not launch or host Minecraft. Do not use it to inspect another app's files.

## Device record

Record the test date, device model, Android release/API, `Build.SUPPORTED_ABIS`, process bitness, total/available memory, device-reported OpenGL ES level, and free storage. The initial target is Samsung Galaxy M21 / Android 12. Do not infer a result for all M21 variants from one handset.

## Test steps

1. Download the `oblivion-mcbe-probe-debug-<commit>` artifact from the workflow run and install it with `adb install -r app-debug.apk`.
2. Open **Oblivion Host Spike**. Confirm manufacturer/model, Android API, process bitness, supported ABI list, memory, and device-reported OpenGL ES level match Android's public device values.
3. With the official Minecraft app absent, confirm the screen says `MINECRAFT_NOT_INSTALLED` and that no app is launched.
4. If the user already has an official Minecraft installation, confirm package visibility/version/split metadata only. The expected host result is `NO_AUTHORIZED_IN_APP_RUNTIME`; do not treat installer metadata as ownership or entitlement proof.
5. Tap **Re-check package and device metadata**. Confirm the loading indicator/status resolves to a result or a recoverable error and retry remains available.
6. Send the probe to background and resume it. Rotate the screen if supported. Use **Exit experiment** and reopen it. Confirm the UI remains usable and lifecycle events are logged.
7. Inspect only the probe's own log using `adb logcat -s OblivionHostSpike`, or, for a debuggable artifact, `adb shell run-as com.m4lych.oblivion.hostspike cat files/diagnostics/mcbe-host-spike.jsonl`. Confirm JSONL rows contain lifecycle/probe events and no tokens or game data.
8. Observe memory use, ANRs, crashes, and repeat-run stability. Do not interpret a successful metadata check as Minecraft rendering, input, authentication, or gameplay compatibility.

## Results

No compatible physical device or emulator was available during this phase. Leave results blank until the steps above have actually been run; do not mark a step passed from static inspection or APK compilation.
