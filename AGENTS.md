# AGENTS.md

## Project

Fork of [ShizuCallRecorder](https://github.com/kitsumed/ShizuCallRecorder) — non-root call recorder for Android. Records two-sided call audio by running `scrcpy-server` (audio-only) inside the Shizuku shell process (UID 2000). Single module `:app`, Kotlin + Jetpack Compose (Material 3). No unit/instrumented tests exist; no lint/ktlint config — verification = compile + on-device behavior.

- Root project name is still `ShizuCallRecorder`; app namespace/applicationId: `com.coolappstore.evercallrecorder.by.svhp`.
- JDK 17, Gradle 9.4.1 (wrapper), AGP 9.1.1, Kotlin 2.3.21. minSdk 30 / targetSdk 36.

## Build

- `./gradlew assembleDebug` — **first build needs network**: `downloadScrcpyServer` task downloads scrcpy-server v4.0 from GitHub releases (SHA-256 pinned in `app/build.gradle.kts`) and `extractLibphonenumberMetadata` pulls phone metadata out of the libphonenumber jar. Both are injected as generated assets via `androidComponents` — do not look for them in `src/main/assets`.
- Version override (CI uses this): `./gradlew assembleRelease -PversionName=X.Y.Z -PversionCode=N -PciBuildNumber=ID`. Local defaults: versionName 3.0.0 / versionCode 3.
- Release signing config only activates when `GITHUB_ACTIONS` env var is set (env vars `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); local release builds are unsigned. Release has minify + shrinkResources on.
- CI: `.github/workflows/build-app.yml` is manual-dispatch only (builds APK + draft release); `codeql.yml` runs on PRs to main/master.

## Recording pipeline (core architecture)

Call detection → Shizuku user service → scrcpy-server → pipe → muxer. Touch one layer, check the others. An alternate backend exists for VoIP calls: the `SAMSUNG_SCREEN_RECORDER` audio source drives Samsung's system screen recorder (tile click + shell-side dialog confirmation via uiautomator dump + input tap, like `docs/record.sh`) instead of scrcpy-server — see the `docs/ status` section below and `docs/SHIZUKU_SYSTEM_RECORD.md`:

1. **Detection**: `services/call/PhoneStateReceiver` (telephony) + `services/call/AppCallNotificationListenerService` (WhatsApp/Telegram VoIP via notification access; VoIP never raises PHONE_STATE). Manifest documents a quirk: two PHONE_STATE broadcasts fire when `READ_CALL_LOG` is granted, unordered, only the second has the number.
2. **Privilege bridge**: `integrations/shizuku/ShizukuConnectionManager` binds `services/ShellService.kt` via Shizuku `bindUserService` (AIDL stubs in `app/src/main/aidl`: `IShellService`, `ILogCallback`). `ShellService` runs in the shell process and launches scrcpy-server with `app_process`; audio flows LocalServerSocket → kernel pipe → app process.
   - `ShellService` must keep both no-arg and single-Context constructors + `@Keep` (Shizuku v13 requirement, R8 must not rename); `destroy()` must call `exitProcess`. Bound with `daemon(false)` intentionally.
3. **Decode/write**: `integrations/scrcpy/ScrcpyClient` parses scrcpy's custom wire format (4-byte FourCC, then 12-byte per-packet headers: 8-byte PTS+flags, 4-byte size; **first packet must be CONFIG**), `ScrcpyAudioMuxer` writes the container. `services/recording/AudioRecordingEngine` + `RecordingForegroundService` orchestrate.
4. **Bumping scrcpy**: update `scrcpyVersion`/`scrcpyServerSha256` in `app/build.gradle.kts` AND verify flag bitmasks/frame-header order against upstream `Streamer.java`/`AudioCodec.java` (links in `ScrcpyClient.kt` kdoc).
5. Shizuku server itself can be started/stopped via broadcast intents with a user-copied auth key ("Manage Shizuku" setting, see `docs/configuration.md`).

## Conventions

- GPL v3+ header comment block at top of every source file (`.kt`, `.kts`, `AndroidManifest.xml`) — replicate it on new files.
- Logging: `utils/AppLogger` with tags formatted `"SCR:ClassName"`.
- `proguard-rules.pro` deliberately uses `-dontobfuscate` and keeps line numbers/SourceFile — do not add obfuscation.
- `android.nonTransitiveRClass=false` in gradle.properties is intentional.

## Runtime requirement

App needs the **Shizuku fork** `thedjchi/Shizuku` (with Watchdog enabled), not official Shizuku — the fork's intent API (`moe.shizuku.privileged.api.START/STOP` with auth extra) is what "Manage Shizuku" uses.

## docs/ status

- `docs/SHIZUKU_SYSTEM_RECORD.md` is a handoff spec for the Samsung screen-recorder feature, which **IS NOW IMPLEMENTED in Kotlin**: `services/recording/ScreenRecordingSession` (app-side orchestrator) + `services/screenrecorder/` (`ScreenRecorderDriver` abstract shell-side contract, `SamsungScreenRecorderDriver`, `ScreenRecorderDrivers` registry). `IShellService`'s 5 screen-recorder methods take a `backendKey` and only delegate to the registered driver. Adding a new vendor = new driver subclass + registry line + `ScrcpyAudioSource` entry (with `screenRecorderBackendKey`) + availability gate in `integrations/screenrecorder/ScreenRecorderBackends`. Key constraint still applies: direct `am startservice` to Samsung's recorder fails (signatureOrSystem permission) even via Shizuku; working path is `cmd statusbar click-tile` + shell-side dialog confirmation via uiautomator dump + input tap (like `docs/record.sh`).
- `docs/record.sh` is the ADB-verified bash reference implementation for that feature — treat as ground truth when porting.
- `docs/configuration.md` is user-facing Shizuku setup doc.
