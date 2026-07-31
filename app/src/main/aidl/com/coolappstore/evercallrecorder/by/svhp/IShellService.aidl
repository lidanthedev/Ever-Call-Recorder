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

    // ── System screen recorder automation (shell-side, vendor drivers in services/screenrecorder) ──
    // All methods take a backendKey matching ScrcpyAudioSource.screenRecorderBackendKey
    // (e.g. "samsung") and delegate to the registered ScreenRecorderDriver.

    /**
     * Wakes the screen, runs the driver's prerequisites + start trigger + confirm flow, then
     * polls the media projection state until the recorder session is active.
     * Returns false if a screen recording was already active or the flow timed out.
     */
    boolean startScreenRecording(String backendKey) = 4;

    /**
     * Stops the recorder via the driver's stop trigger. No-op (returns true) when no session
     * is active, so it can never accidentally START a recording.
     * Returns false if the session was still active after the stop attempt.
     */
    boolean stopScreenRecording(String backendKey) = 5;

    /** True while the driver's recorder owns an active media projection session. */
    boolean isScreenRecordingActive(String backendKey) = 6;

    /**
     * Opens the newest finalized recording whose mtime is >= minEpochSec (guards against
     * picking up an older recording). Returns a read-only FD, or null if none matched.
     * The shell process can read shared storage without any app storage permission.
     */
    ParcelFileDescriptor openLatestScreenRecording(String backendKey, long minEpochSec) = 7;

    /**
     * Deletes the newest finalized recording whose mtime is >= minEpochSec (same selection
     * rule as openLatestScreenRecording). Used to remove the source video after its audio
     * track was successfully imported. Returns true if a file was deleted.
     */
    boolean deleteLatestScreenRecording(String backendKey, long minEpochSec) = 8;

    // The special Shizuku transaction code for "destroy" process
    void destroy() = 16777114;
}
