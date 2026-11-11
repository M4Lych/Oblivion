# Android device test plan

This plan is for a physical Samsung Galaxy M21 running Android 12. No device run is implied by this document. Record the Android build number, Oblivion commit SHA, APK SHA-256, and test outcome for every run.

## Get a test-installable copy

The workflow artifact is intentionally unsigned. Android cannot install that file directly. Download the artifact from the Actions run for the exact commit being tested, then sign a local copy with a test-only key kept outside the repository. Do not reuse a release key.

Example local commands (the tools prompt for key details):

```sh
keytool -genkeypair -keystore "$HOME/oblivion-test.jks" -alias oblivion-test \
  -keyalg RSA -keysize 2048 -validity 3650
apksigner sign --ks "$HOME/oblivion-test.jks" --ks-key-alias oblivion-test \
  --out app-debug-test.apk app-debug.apk
apksigner verify app-debug-test.apk
adb install -r app-debug-test.apk
```

Keep the generated keystore private and untracked. The app’s local data may be cleared by uninstalling; use `adb install -r` during persistence checks.

## Manual checks

1. **Installation and startup:** install the locally signed test copy. Launch Oblivion from the launcher icon. Confirm the dashboard appears without requiring Microsoft or Discord sign-in. Record cold-start time and any crash or error.
2. **Navigation and layout:** visit Home, Library, Community, Profile, and Settings. Check portrait and landscape layouts, back navigation, scroll behavior, text contrast, and touch targets. Confirm the Play screen reports that in-client gameplay is unavailable; its separate-app option remains an explicitly labeled fallback.
3. **Profile persistence:** change the local display name, force-stop and reopen Oblivion, then confirm the name persists. Confirm the UI still identifies the profile as local and shows no fabricated server ID or verified role.
4. **Skin PNG import:** import a user-owned 64×64 PNG and a 64×32 PNG. Confirm the launcher preview updates and persists after restart. Try a non-PNG renamed as `.png`, an unsupported dimension, and a file larger than 16 MiB; each should be rejected without changing Minecraft.
5. **World archive import:** select a valid `.mcworld` with root `level.dat`; confirm it appears in the Oblivion library. Try an invalid archive and confirm it is rejected. Confirm the UI does not claim that Minecraft imported or activated it. Test share/export and confirmed deletion.
6. **Server notes:** add a hostname and a host with a valid port, search, edit, copy the address, and remove a note after confirmation. Restart the app and verify saved notes remain. Try malformed hosts and ports outside 1–65535.
7. **Settings persistence:** change optional analytics and reduce-motion preferences. Restart Oblivion and confirm the values persist and analytics remains off unless explicitly enabled.
8. **Diagnostics:** open local diagnostics, search/filter entries, and share/export a log file. Confirm errors are readable and tokens/password-like values are redacted. Diagnostic report upload is not available in this client build.
9. **Pause/resume:** background Oblivion from each major screen, wait briefly, and return. Confirm state is stable, navigation remains responsive, and no screen claims an active hosted Minecraft session.
10. **Memory/performance:** scroll the dashboard and library, import a representative world, and observe responsiveness and memory. Capture `adb shell dumpsys meminfo com.m4lych.oblivion` before and after import and record values, device temperature if relevant, and any ANR, crash, or sustained lag.

## Record results

For each step record Pass / Fail / Not Applicable, reproduction details, screenshots where useful, and the relevant exported Oblivion log. Do not include account tokens, private files, or credentials in a report. A successful run on one Galaxy M21 does not establish compatibility across Android devices.
