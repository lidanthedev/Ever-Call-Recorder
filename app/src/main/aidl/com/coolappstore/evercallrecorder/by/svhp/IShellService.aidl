package com.coolappstore.evercallrecorder.by.svhp;

import android.os.ParcelFileDescriptor;
import com.coolappstore.evercallrecorder.by.svhp.ILogCallback;

interface IShellService {
    ParcelFileDescriptor startRecording(
        String audioSource,
        String audioCodec,
        int audioBitRate,
        String serverPath,
        boolean isDebuggingModeEnabled, // For debugging purposes, if true, the service will log additional information and change some logging behavior.
        ILogCallback appLoggerCallback
    ) = 1;

    void stopRecording() = 2;

    boolean isRecording() = 3;

    // ── Samsung system screen recorder automation (shell-side, see docs/SHIZUKU_SYSTEM_RECORD.md) ──

    /**
     * Wakes the screen, ensures the Screen recorder QS tile is present, clicks it, then polls
     * dumpsys media_projection until Samsung's recorder session is active (the confirmation
     * dialog is handled app-side by our AccessibilityService while this polls).
     * Returns false if a screen recording was already active (tile click would have STOPPED it)
     * or if the session never became active within the timeout.
     */
    boolean startScreenRecording() = 4;

    /**
     * Stops Samsung's screen recorder by clicking the QS tile again (the tile toggles).
     * No-op (returns true) when no session is active, so we never accidentally START a recording.
     * Returns false if the session was still active after the stop attempt.
     */
    boolean stopScreenRecording() = 5;

    /** True while dumpsys media_projection reports an active session owned by smartcapture. */
    boolean isScreenRecordingActive() = 6;

    /**
     * Opens the newest file in /sdcard/DCIM/Screen recordings/ whose mtime is >= minEpochSec
     * (guards against picking up an older recording). Returns a read-only FD, or null if none matched.
     * The shell process can read /sdcard without any app storage permission.
     */
    ParcelFileDescriptor openLatestScreenRecording(long minEpochSec) = 7;

    // The special Shizuku transaction code for "destroy" process
    void destroy() = 16777114;
}
