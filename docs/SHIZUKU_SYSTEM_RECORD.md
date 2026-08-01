# Samsung Screen Recorder Automation via Shizuku

## Implementation status

**IMPLEMENTED in Kotlin.** Components: `services/recording/ScreenRecordingSession` (app-side
orchestrator), `services/screenrecorder/SamsungScreenRecorderDriver.kt` (shell-side automation,
behind the vendor-agnostic `ScreenRecorderDriver` contract + `ScreenRecorderDrivers` registry;
`IShellService` methods only delegate by `backendKey`), and the `SAMSUNG_SCREEN_RECORDER`
entry in `integrations/scrcpy/ScrcpyAudioSource`. Dialog confirmation is user-selectable
(`AppPreferences.ScreenRecorderConfirmMode`, Settings → Recording):

- **uiautomator (default)** — **option B** below, done entirely in the shell process as a
  dump-and-tap loop (matched by resource-id `android:id/button1` for locale-safety, text as
  fallback; no separate focus poll). Costs ~2–5 s per dump.
- **accessibility** — app-side `ScreenRecorderAccessibilityService` clicks the dialog in-memory
  (~ms). The countdown is skipped from BOTH sides: the service's own `dispatchGesture` center
  taps after its click, and blind shell-side `input tap`s fired in a loop (every 250 ms,
  starting 0.5 s after the tile click) until the projection appears — the loop survives late
  countdown overlays. Requires the user to enable the service in system Accessibility settings;
  `ScreenRecordingSession` falls back to uiautomator when it isn't enabled. The service
  self-gates on the confirmation-mode preference: without that gate, both mechanisms race the
  dialog — the service dismisses it instantly, the shell dump never sees the button and
  reports a false start failure while the recorder runs.
  NOTE: an earlier accessibility attempt was removed because the dialog never got clicked —
  likely because a system alert dialog is not necessarily the *active* window. The new
  implementation queries both `rootInActiveWindow` AND `event.source` for that reason.

Speed findings (verified on-device, 2026-07-31):
- **The 3-second pre-recording countdown can be skipped by tapping the screen** (any spot;
  we tap upper-center, clear of the underlying app's in-call buttons). Measured:
  ~1.4 s tap→projection vs ~3.7 s baseline.
- The tile-presence check (`settings get sysui_qs_tiles`) is cached per shell process.
- Import must ignore hidden `.pending-*.mp4` files (finalize race) and the stop path must
  survive duplicate stop requests (see `releaseInFlight` in `RecordingForegroundService`).

The rest of this doc is the original ADB-verified investigation and remains the
ground-truth reference.

## Goal

Start/stop Samsung's built-in Screen Recorder (`com.samsung.android.app.smartcapture`)
from a Shizuku-powered app, without root and without the
`com.samsung.android.app.screenrecorder.permission.ACCESS_SCREEN_RECORDER_SVC`
permission (signatureOrSystem — unobtainable for 3rd-party apps).

## Verified Environment

- Device: Samsung, One UI 8 / Android 16 (SDK 36)
- smartcapture version: 6.1.80.31 (versionCode 618031000)
- Shell uid 2000 (no root). All flows below verified working over ADB on 2026-07-31.

## Why the Direct Path Fails

The recorder service IS exported but permission-gated:

```xml
<service android:name="com.samsung.android.app.screenrecorder.ScreenRecorderService"
    android:permission="com.samsung.android.app.screenrecorder.permission.ACCESS_SCREEN_RECORDER_SVC"
    android:exported="true" .../>
```

`am startservice` from shell → `Error: Requires permission ...ACCESS_SCREEN_RECORDER_SVC`.
`pm grant` impossible (signatureOrSystem). Dead end even with Shizuku (shell uid ≠ signature).

**Working bypass:** click the Quick Settings tile. SystemUI (system uid) performs the
click, the app's own `RecordScreenTile.onClick()` starts the service internally with
its own permissions. A confirmation dialog ("Start recording with Screen recorder?")
appears → tap its positive button → recording starts.

## Confirmed Flow (ADB-verified)

```
1. cmd statusbar click-tile <tile-component>   → dialog appears (process :tileservice)
2. tap "Start recording" button                 → countdown → recording (:screenrecorder)
3. cmd statusbar click-tile <tile-component>   → stops, saves mp4
```

Constants (decompiled from APK):

| Item | Value |
|---|---|
| Package | `com.samsung.android.app.smartcapture` |
| QS tile (phone) | `com.samsung.android.app.screenrecorder.view.RecordScreenTile` (process `:tileservice`) |
| QS tile (DeX) | `com.samsung.android.app.screenrecorder.view.DexRecordScreenTile` |
| Recorder service | `com.samsung.android.app.screenrecorder.ScreenRecorderService` (`:screenrecorder`) |
| Start action | `com.samsung.android.app.screenrecorder.ACTION_START` |
| Stop action | `com.samsung.android.app.screenrecorder.ACTION_STOP` (extra `way_to_stop`) |
| Dialog positive btn | text `Start recording`, id `android:id/button1` |
| Dialog negative btn | text `Cancel`, id `android:id/button2` |
| Output dir | `/sdcard/DCIM/Screen recordings/` |
| Output name | `Screen_Recording_yyyyMMdd_HHmmss_<AppName>.mp4` |
| Tile setting key | `secure sysui_qs_tiles`, spec `custom(<pkg>/<class>)` |

Note: actions are only usable if the CALLER holds the signature permission — via tile
click, SystemUI is effectively the caller chain, so it works. Direct `startService()`
from a Shizuku shell context still fails. **Do not** try `am startservice` via Shizuku.

## Implementation Plan

### 1. Shizuku setup

Standard Shizuku flow: `Shizuku.pingBinder()`, request permission
(`Shizuku.requestPermission()`), then use `ShizukuBinderWrapper` +
`SystemServiceHelper` (rikka.shizuku) to call system services as shell (uid 2000).

Add to manifest: `<uses-permission android:name="moe.shizuku.manager.permission.API_V13"/>`

### 2. Click the QS tile (as SystemUI would)

ADB equivalent: `cmd statusbar click-tile <component>`.

From Java/Kotlin, invoke the same shell command through Shizuku's `newProcess`
(simplest, robust):

```kotlin
val TILE = ComponentName(
    "com.samsung.android.app.smartcapture",
    "com.samsung.android.app.screenrecorder.view.RecordScreenTile"
)

fun shizukuExec(cmd: Array<String>): Int {
    val process = Shizuku.newProcess(cmd, null, null)
    val exit = process.waitFor()
    process.inputStream.close(); process.outputStream.close(); process.errorStream.close()
    return exit
}

fun clickRecorderTile() {
    shizukuExec(arrayOf("sh", "-c",
        "cmd statusbar click-tile ${TILE.flattenToShortString()}"))
}
```

Alternative (no process spawn): reflect `IStatusBarService.clickTile(ComponentName)`
— hidden API, needs `android.permission.STATUS_BAR_SERVICE` holder = shell works via
Shizuku binder. More fragile across versions; `cmd statusbar` recommended.

### 3. Ensure the tile exists in the QS layout

`click-tile` no-ops if the user removed the tile. Before first use:

```kotlin
val spec = "custom(${TILE.flattenToShortString()})"
val cur = Settings.Secure.getString(ctx.contentResolver, "sysui_qs_tiles") ?: ""
if (spec !in cur) {
    // write as shell via Shizuku:
    shizukuExec(arrayOf("sh", "-c",
        "settings put secure sysui_qs_tiles \"$cur,$spec\""))
}
```

(Verified on device: tile entry format is `custom(pkg/class)`.)

### 4. Confirm the "Start recording" dialog

After the tile click, a dialog from smartcapture appears (~0.5–2 s). Options, best first:

**A. AccessibilityService (recommended for production)**
Your app runs a normal AccessibilityService (user-enabled), watches for
`TYPE_WINDOW_STATE_CHANGED` from `com.samsung.android.app.smartcapture`, then:

```kotlin
val nodes = rootInActiveWindow
    .findAccessibilityNodeInfosByText("Start recording")
nodes?.firstOrNull { it.className == "android.widget.Button" }
    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
```

Do NOT rely on `KEYCODE_ENTER`: verified that default focus lands on
`radio_button_full_screen` — Enter toggles a radio button, it does not confirm.

**B. uiautomator-dump fallback (what the bash script does)**
`uiautomator dump` → parse node with `text="Start recording"` →
`input tap (cx, cy)`. Works headless but ~500–800 ms and fragile across locales
(text is localized). Match by resource-id `android:id/button1` for locale-safety.

### 5. Stop recording

Same `clickRecorderTile()` call — the tile toggles. (Tile click while recording
routes `ACTION_STOP` with `way_to_stop=stop_from_quick_panel`.)

### 6. Verify state (don't trust fire-and-forget)

- Recording active check:
  `dumpsys media_projection` — active session shows `packageName: com.samsung.android.app.smartcapture`;
  stopped shows `null` after `Media Projection:` header.
- Service check:
  `dumpsys activity services com.samsung.android.app.smartcapture | grep ScreenRecorderService`
- Output file: poll `ls -t /sdcard/DCIM/Screen recordings/ | head -1`, compare
  mtime against operation start (guard against reporting an older file).

## Pitfalls (all observed during testing)

1. **Locked screen**: flow fails while keyguard is up (dialog never gets focus).
   Check `dumpsys window | grep mDreamingLockscreen` or KeyguardManager before starting.
2. **Wrong tap kills silently**: tapping outside the button dismisses the dialog and
   the subsequent stop-click opens a NEW dialog instead of stopping — script reported
   success falsely. Always verify `media_projection` state after each step.
3. **Button bounds parsing**: if parsing `uiautomator dump` XML with regex, extract
   numbers only AFTER `bounds="` — `resource-id="android:id/button1"` leaks a stray
   `1` into naive `[0-9]+` extraction and offsets the tap.
4. **First run**: runtime permission prompts (mic/notifications) may appear before
   the recorder dialog. Pre-grant or handle them in the accessibility flow.
5. **Dialog settings persist**: user's choices (full/partial screen, audio source,
   "Show taps") are remembered; the dialog still appears every time — there is no
   "don't ask again" on this build.
6. **Latency**: countdown (~3 s) runs after confirm; don't start measuring duration
   until `media_projection` reports the session.
7. **Process split**: tile runs in `:tileservice`, recorder in `:screenrecorder` —
   both are the same package; filters on process name must account for this.

## Reference: working bash implementation

Full start/wait/stop/verify cycle, verified end-to-end over ADB (produced valid mp4).
Ground truth for the Kotlin port (formerly `docs/record.sh`, inlined here):

```bash
#!/bin/bash
# Record screen for N seconds on Samsung device via smartcapture QS tile.
# Usage: copy this block into a file (e.g. record.sh), then: ./record.sh [seconds]   (default 10)

DURATION="${1:-10}"
PKG=com.samsung.android.app.smartcapture
TILE=$PKG/com.samsung.android.app.screenrecorder.view.RecordScreenTile
TILE_SPEC="custom($TILE)"

# --- ensure tile present in QS layout (click-tile fails if user removed it)
CURRENT_TILES=$(adb shell settings get secure sysui_qs_tiles | tr -d '\r')
if [[ "$CURRENT_TILES" != *"$TILE_SPEC"* ]]; then
    echo "[*] tile missing from QS layout, appending"
    adb shell settings put secure sysui_qs_tiles "$CURRENT_TILES,$TILE_SPEC"
    sleep 1
fi

START_TS=$(date +%s)

echo "[*] clicking screen recorder tile"
adb shell cmd statusbar click-tile "$TILE"

# --- wait for dialog via window focus (cheap, no uiautomator)
echo "[*] waiting for dialog"
for _ in $(seq 1 30); do
    adb shell dumpsys window | grep -q "mCurrentFocus=Window{.*$PKG" && break
    sleep 0.2
done

# --- single uiautomator dump, parse Start recording button bounds
adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
BOUNDS=$(adb shell cat /sdcard/ui.xml 2>/dev/null \
    | grep -o 'text="Start recording"[^>]*bounds="\[[0-9,]*\]\[[0-9,]*\]"' \
    | grep -o 'bounds="[^"]*"' | grep -o '[0-9]\+' | paste -sd' ' -)

if [ -z "$BOUNDS" ]; then
    echo "[!] Start recording button not found"
    exit 1
fi

set -- $BOUNDS   # x1 y1 x2 y2
X=$(( ($1 + $3) / 2 ))
Y=$(( ($2 + $4) / 2 ))
echo "[*] tapping Start recording at ($X, $Y)"
adb shell input tap "$X" "$Y"

# --- verify recording actually started
for _ in $(seq 1 20); do
    ! adb shell dumpsys media_projection | grep -q "^null" && break
    sleep 0.5
done
if adb shell dumpsys media_projection | grep -q "^null"; then
    echo "[!] recording did not start"
    exit 1
fi
echo "[+] recording active"
OUR_RECORDING=1

# --- abnormal exit: attempt to stop the projection WE started (never a pre-existing one)
cleanup() {
    if [ "$OUR_RECORDING" = "1" ] && ! adb shell dumpsys media_projection | grep -q "^null"; then
        echo "[*] exit cleanup: attempting to stop the active projection"
        adb shell cmd statusbar click-tile "$TILE"
    fi
}
trap cleanup EXIT

echo "[*] recording ${DURATION}s..."
sleep "$DURATION"

echo "[*] stopping"
STOPPED=0
for attempt in 1 2 3; do
    adb shell cmd statusbar click-tile "$TILE"
    sleep 3
    if adb shell dumpsys media_projection | grep -q "^null"; then
        STOPPED=1
        break
    fi
    echo "[!] projection still active (attempt $attempt), retrying stop..."
done
if [ "$STOPPED" -eq 1 ]; then
    echo "[+] recording stopped"
else
    echo "[!] projection still active after retries, stop failed"
    exit 1
fi

# --- report only finalized files created after script start (skip hidden .pending-*.mp4;
# plain `ls` already hides dotfiles, the .mp4 filter keeps non-recording entries out)
NEWEST=$(adb shell "ls -t /sdcard/DCIM/Screen\ recordings/ | grep '\.mp4$' | head -1" | tr -d '\r')
MTIME=$(adb shell "stat -c %Y \"/sdcard/DCIM/Screen recordings/$NEWEST\" 2>/dev/null" | tr -d '\r')
if [ -n "$MTIME" ] && [ "$MTIME" -ge "$START_TS" ]; then
    echo "[+] saved: /sdcard/DCIM/Screen recordings/$NEWEST"
else
    echo "[!] no new recording file found"
    exit 1
fi
```

Note: the Kotlin uiautomator path no longer does the separate `dumpsys window` focus
poll — it loops `uiautomator dump` directly (on the fast path the dialog is already
in the first dump) and matches by resource-id, not the localized `text=` used here.

## Minimal test matrix

| Case | Expected |
|---|---|
| Tile present, screen on, unlocked | dialog → confirm → recording |
| Tile missing from QS layout | auto-append to `sysui_qs_tiles`, then works |
| Screen locked | abort early with clear error |
| Already recording (start call) | `ScreenRecorderDriver.startRecording()` busy-guard rejects BEFORE any tile click (triggers toggle) → start fails with `recording_error_screen_recorder_busy`; user's own session keeps running |
| Stop when not recording | `stopRecording()` sees no active session and no-ops (returns success) — no tile click is ever fired, so it cannot START a recording |
