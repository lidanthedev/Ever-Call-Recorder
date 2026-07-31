/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.coolappstore.evercallrecorder.by.svhp.services.screenrecorder

import android.os.ParcelFileDescriptor
import com.coolappstore.evercallrecorder.by.svhp.utils.AppLogger
import java.io.File

/**
 * Vendor-specific driver for automating a system's built-in screen recorder from the Shizuku
 * shell process (UID 2000). One implementation per vendor (e.g. [SamsungScreenRecorderDriver]).
 *
 * ## What a NEW screen recorder must implement
 *  - [backendKey]         — stable key matching `ScrcpyAudioSource.screenRecorderBackendKey`.
 *  - [recorderPackage]    — package owning the recorder; used for the MediaProjection state check.
 *  - [requestStart]       — the actual start trigger (e.g. a QS tile click). Warning: most tiles
 *                           TOGGLE — [startRecording] guards against an already-active session.
 *  - [requestStop]        — the actual stop trigger (may be the same toggle as [requestStart]).
 *  - [findLatestRecording]— locate the newest finalized recording file (vendor output dir,
 *                           file pattern, "pending file" semantics) with mtime >= a given epoch.
 *
 * ## Optional hooks (defaults cover recorders with no dialog/countdown)
 *  - [isRecordingActive]   — default: `dumpsys media_projection` mentions [recorderPackage].
 *  - [ensurePrerequisites] — e.g. make sure the QS tile exists. Default: no-op.
 *  - [confirmStart]        — confirm a system dialog (e.g. via [dumpUiHierarchy] + [tapNodeCenter]).
 *                            Default: true (no dialog).
 *  - [afterStartConfirmed] — e.g. skip a pre-recording countdown. Default: no-op.
 *
 * The [startRecording]/[stopRecording] templates orchestrate these hooks and the
 * projection-state polling; they must NOT be overridden.
 */
abstract class ScreenRecorderDriver {

    companion object {
        /** Max time to wait for the media projection session to appear/disappear after a trigger. */
        const val STATE_TIMEOUT_MS = 15_000L

        /** Poll interval while waiting for the media projection state to change. */
        const val STATE_POLL_MS = 250L

        /**
         * Dialog confirmation modes for [startRecording]. Mirrors
         * [com.coolappstore.evercallrecorder.by.svhp.data.AppPreferences.ScreenRecorderConfirmMode]
         * (int because AIDL has no enum support).
         */

        /** Shell-side `uiautomator dump` + `input tap` (driver's [confirmStart] hook). */
        const val CONFIRM_UIAUTOMATOR = 0

        /**
         * App-side accessibility service clicks the dialog; the shell process skips [confirmStart]
         * and only waits for the projection to become active ([waitForExternalConfirmation]).
         * Countdown-skip taps ([afterStartConfirmed]) still fire, blindly, after a
         * [EXTERNAL_CONFIRM_TAP_DELAY_MS] grace delay that lets the service confirm first.
         */
        const val CONFIRM_ACCESSIBILITY = 1

        /**
         * Grace delay after the start trigger before firing countdown-skip taps in
         * [CONFIRM_ACCESSIBILITY] mode — gives the app-side accessibility service time to click
         * the dialog first, so the taps land on the countdown overlay instead of the open dialog.
         */
        const val EXTERNAL_CONFIRM_TAP_DELAY_MS = 500L
    }

    /** Log tag; drivers override to identify themselves in logcat. */
    protected abstract val tag: String

    /** Stable backend key, matching `ScrcpyAudioSource.screenRecorderBackendKey` (e.g. "samsung"). */
    abstract val backendKey: String

    /** Package owning the screen recorder (checked in the MediaProjection dumpsys). */
    abstract val recorderPackage: String

    // ── Vendor hooks ─────────────────────────────────────────────────────────

    /** True while a recording session owned by [recorderPackage] is active. */
    open fun isRecordingActive(): Boolean {
        val dump = execShellCommand("dumpsys media_projection") ?: return false
        return dump.contains(recorderPackage)
    }

    /** One-time setup before a start trigger (e.g. ensure the QS tile is in the layout). */
    open fun ensurePrerequisites() {}

    /** Fires the vendor's start trigger (tile click, etc). */
    abstract fun requestStart()

    /**
     * Confirms the vendor's start-confirmation flow, if any (dialog tap, permission prompt).
     * @return true when the start flow may proceed to [afterStartConfirmed]/state polling.
     */
    open fun confirmStart(): Boolean = true

    /** Runs right after [confirmStart] succeeds (e.g. skip a pre-recording countdown). */
    open fun afterStartConfirmed() {}

    /**
     * Single countdown-skip attempt for the [CONFIRM_ACCESSIBILITY] tap loop, fired every
     * [STATE_POLL_MS] until the projection appears. Default: no-op (recorders with no countdown).
     */
    protected open fun skipCountdownOnce() {}

    /** Fires the vendor's stop trigger (may be the same toggle as [requestStart]). */
    abstract fun requestStop()

    /**
     * Locates the newest FINALIZED recording file with mtime >= [minEpochSec]
     * (the epoch captured just before [startRecording]; guards against picking up older files).
     * Must exclude any in-progress/pending files.
     */
    abstract fun findLatestRecording(minEpochSec: Long): File?

    // ── Templates (final) ────────────────────────────────────────────────────

    /**
     * Starts the recorder: busy-guard, screen wake, prerequisites, start trigger, confirm flow,
     * post-confirm hook, then polls the projection state. May block for many seconds.
     *
     * Safety: refuses to trigger when a session is already active (start/stop triggers usually
     * toggle — firing would STOP the user's own ongoing recording instead).
     *
     * @param confirmMode [CONFIRM_UIAUTOMATOR] (default): the shell process drives [confirmStart]
     * (uiautomator dump + input tap) then [afterStartConfirmed]. [CONFIRM_ACCESSIBILITY]: the
     * app-side accessibility service confirms the dialog on its own, so this process skips
     * [confirmStart]; after a [EXTERNAL_CONFIRM_TAP_DELAY_MS] grace delay it fires
     * [skipCountdownOnce] in a loop until the projection appears (countdown skipped or elapsed)
     * — no uiautomator dumps.
     * @return true if the session became active within [STATE_TIMEOUT_MS].
     */
    fun startRecording(confirmMode: Int = CONFIRM_UIAUTOMATOR): Boolean {
        if (isRecordingActive()) {
            AppLogger.w(tag, "startRecording() refused: a screen recording is already active (triggers toggle, firing would stop it)")
            return false
        }

        // Best-effort screen wake. If the keyguard is still locked a confirmation dialog never
        // gains focus and the flow times out below.
        execShellCommand("input keyevent KEYCODE_WAKEUP")

        ensurePrerequisites()

        requestStart()

        val confirmed = if (confirmMode == CONFIRM_ACCESSIBILITY) {
            AppLogger.i(tag, "Waiting for the app-side accessibility service to confirm the recorder dialog")
            // Grace delay so the accessibility service can click the dialog before the taps start.
            Thread.sleep(EXTERNAL_CONFIRM_TAP_DELAY_MS)
            // Tap-to-skip in a loop: the countdown overlay's appearance time varies, so keep
            // tapping until the projection shows up (skip succeeded or countdown ran its course).
            val deadline = System.currentTimeMillis() + STATE_TIMEOUT_MS
            var becameActive = false
            while (System.currentTimeMillis() < deadline) {
                if (isRecordingActive()) {
                    becameActive = true
                    break
                }
                skipCountdownOnce()
                Thread.sleep(STATE_POLL_MS)
            }
            becameActive
        } else {
            if (confirmStart()) {
                afterStartConfirmed()
                true
            } else {
                false
            }
        }
        if (!confirmed) {
            AppLogger.w(tag, "Start flow was not confirmed")
            return false
        }

        return waitForProjectionState(targetActive = true)
    }

    /**
     * Stops the recorder and verifies the session actually ended. Never fires the trigger when
     * no session is active (a toggling trigger would START a recording otherwise).
     *
     * @return true if no session is active when this method returns (stopped or never ran).
     */
    fun stopRecording(): Boolean {
        if (!isRecordingActive()) {
            AppLogger.d(tag, "stopRecording(): no active session, nothing to stop")
            return true
        }

        requestStop()

        return waitForProjectionState(targetActive = false)
    }

    /**
     * Opens the newest finalized recording with mtime >= [minEpochSec] and returns a read-only
     * FD for it (the shell process can read shared storage without any app permission).
     *
     * @return A read-only [ParcelFileDescriptor], or null if no matching file exists.
     */
    fun openLatestRecording(minEpochSec: Long): ParcelFileDescriptor? {
        val newest = findLatestRecording(minEpochSec)
        if (newest == null) {
            AppLogger.w(tag, "No new screen recording found (minEpochSec=$minEpochSec)")
            return null
        }
        return try {
            AppLogger.i(tag, "Opening recorded file: ${newest.absolutePath} (${newest.length()} bytes)")
            ParcelFileDescriptor.open(newest, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Exception) {
            AppLogger.e(tag, "Failed to open ${newest.absolutePath}", e)
            null
        }
    }

    /**
     * Deletes the newest finalized recording with mtime >= [minEpochSec] (same selection rule as
     * [openLatestRecording]). Used to remove the source video after its audio track was
     * successfully imported into the app library.
     *
     * @return true if a file was deleted.
     */
    fun deleteLatestRecording(minEpochSec: Long): Boolean {
        val newest = findLatestRecording(minEpochSec) ?: return false
        return try {
            val deleted = newest.delete()
            if (deleted) {
                AppLogger.i(tag, "Deleted source screen recording: ${newest.absolutePath}")
            } else {
                AppLogger.w(tag, "Failed to delete source screen recording: ${newest.absolutePath}")
            }
            deleted
        } catch (e: Exception) {
            AppLogger.e(tag, "Error deleting ${newest.absolutePath}", e)
            false
        }
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    /**
     * Runs a shell command line in this shell process and returns its combined stdout.
     * The output and exit code are always logged: these commands drive the screen recorder
     * flow and their failures are otherwise invisible.
     * @return The command output, or null if the command could not be executed.
     */
    protected fun execShellCommand(command: String): String? {
        return try {
            val process = ProcessBuilder("sh", "-c", command).apply {
                // The Shizuku user-service process may inherit a minimal environment; make sure
                // the standard tool directories are on PATH for `cmd`, `settings`, `dumpsys`, `input`.
                environment()["PATH"] = "/system/bin:/system/xbin:/product/bin:/apex/com.android.art/bin:" +
                    (environment()["PATH"] ?: "")
                redirectErrorStream(true)
            }.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            AppLogger.d(tag, "execShellCommand('$command') exit=$exitCode output='${output.trim().take(300)}'")
            output
        } catch (e: Exception) {
            AppLogger.w(tag, "execShellCommand('$command') failed: ${e.message}")
            null
        }
    }

    /** Polls [isRecordingActive] until it equals [targetActive] or the timeout elapses. */
    protected fun waitForProjectionState(targetActive: Boolean): Boolean {
        val deadline = System.currentTimeMillis() + STATE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isRecordingActive() == targetActive) {
                AppLogger.i(tag, "Screen recorder session is now ${if (targetActive) "active" else "stopped"}")
                return true
            }
            Thread.sleep(STATE_POLL_MS)
        }
        AppLogger.w(tag, "Timed out waiting for the screen recorder session to become ${if (targetActive) "active" else "stopped"}")
        return false
    }

    /**
     * Dumps the current UI hierarchy via `uiautomator dump` and returns its XML,
     * or null on failure.
     */
    protected fun dumpUiHierarchy(dumpPath: String = "/sdcard/ui.xml"): String? {
        execShellCommand("uiautomator dump $dumpPath")
        return execShellCommand("cat $dumpPath")
    }

    /**
     * Taps the center of a `<node ...>` tag's `bounds="[x1,y1][x2,y2]"` attribute.
     *
     * Bounds-parsing pitfall: numbers must be extracted ONLY from the `bounds` attribute of the
     * SAME node tag — a naive `[0-9]+` over the whole document picks up stray digits from
     * resource ids (e.g. the `1` in `android:id/button1`) and offsets the tap.
     *
     * @return true if the bounds were found and the tap was sent.
     */
    protected fun tapNodeCenter(nodeTag: String, logLabel: String): Boolean {
        val bounds = Regex("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").find(nodeTag)
        if (bounds == null) {
            AppLogger.w(tag, "No bounds attribute on node: $logLabel")
            return false
        }
        val (x1, y1, x2, y2) = bounds.destructured
        val cx = (x1.toInt() + x2.toInt()) / 2
        val cy = (y1.toInt() + y2.toInt()) / 2
        AppLogger.i(tag, "Tapping $logLabel at ($cx, $cy)")
        execShellCommand("input tap $cx $cy")
        return true
    }
}
