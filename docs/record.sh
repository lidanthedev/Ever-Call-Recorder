#!/bin/bash
# Record screen for N seconds on Samsung device via smartcapture QS tile.
# Usage: ./record.sh [seconds]   (default 10)

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

echo "[*] recording ${DURATION}s..."
sleep "$DURATION"

echo "[*] stopping"
adb shell cmd statusbar click-tile "$TILE"
sleep 3

if adb shell dumpsys media_projection | grep -q "^null"; then
    echo "[+] recording stopped"
else
    echo "[!] projection still active, stop failed"
    exit 1
fi

# --- report only files created after script start
NEWEST=$(adb shell "ls -t /sdcard/DCIM/Screen\ recordings/ | head -1" | tr -d '\r')
MTIME=$(adb shell "stat -c %Y \"/sdcard/DCIM/Screen recordings/$NEWEST\" 2>/dev/null" | tr -d '\r')
if [ -n "$MTIME" ] && [ "$MTIME" -ge "$START_TS" ]; then
    echo "[+] saved: /sdcard/DCIM/Screen recordings/$NEWEST"
else
    echo "[!] no new recording file found"
    exit 1
fi
