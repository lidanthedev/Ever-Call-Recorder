/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.coolappstore.evercallrecorder.by.svhp.services.screenrecorder

import com.coolappstore.evercallrecorder.by.svhp.utils.AppLogger
import java.io.File

/**
 * Driver for Samsung's built-in Screen Recorder (`com.samsung.android.app.smartcapture`),
 * see docs/SHIZUKU_SYSTEM_RECORD.md for the full investigation.
 *
 * The direct service path is permission-gated (signatureOrSystem), so we drive the Quick
 * Settings tile instead: SystemUI performs the click with its own privileges. The "Start
 * recording" confirmation dialog is accepted via `uiautomator dump` + `input tap` on the
 * positive button's bounds. The dialog's audio option is forced to "Media and mic" (otherwise
 * one side of the call is silent), and the ~3 s pre-recording countdown is skipped by tapping
 * the screen center (verified on One UI 8 / Android 16).
 */
class SamsungScreenRecorderDriver : ScreenRecorderDriver() {

    companion object {
        /** Package of Samsung's Smart Capture / Screen Recorder. */
        const val SAMSUNG_CAPTURE_PACKAGE = "com.samsung.android.app.smartcapture"

        /** QS tile component that starts/stops Samsung's screen recorder (phone variant). */
        const val SAMSUNG_RECORDER_TILE = "$SAMSUNG_CAPTURE_PACKAGE/com.samsung.android.app.screenrecorder.view.RecordScreenTile"

        /** Directory where Samsung's screen recorder writes its mp4 files. Readable by shell. */
        const val SAMSUNG_RECORDINGS_DIR = "/sdcard/DCIM/Screen recordings"

        /** Max time to wait for the recorder confirmation dialog to appear after the tile click. */
        const val DIALOG_TIMEOUT_MS = 6_000L

        /** Poll interval while waiting for the confirmation dialog window to appear. */
        const val DIALOG_POLL_MS = 200L

        /** Resource-id of the recorder dialog's positive ("Start recording") button. Locale-safe. */
        const val DIALOG_BUTTON_RESOURCE_ID = "android:id/button1"

        /** Fallback text of the dialog's positive button (localized — used only if the id is absent). */
        const val DIALOG_BUTTON_FALLBACK_TEXT = "Start recording"

        /** Resource id of the "Media and mic" audio option (clickable container) in the recorder dialog. */
        const val DIALOG_AUDIO_MEDIA_AND_MIC_ID = "radio_button_sound_all"
    }

    override val tag = "SCR:SamsungScreenRecorder"
    override val backendKey = "samsung"
    override val recorderPackage = SAMSUNG_CAPTURE_PACKAGE

    // ── Prerequisites: QS tile must be in the layout or click-tile silently no-ops ──

    /** Checked only once per driver lifetime to keep session starts fast. */
    private var recorderTileEnsured = false

    override fun ensurePrerequisites() {
        if (recorderTileEnsured) return
        val spec = "custom($SAMSUNG_RECORDER_TILE)"
        val current = execShellCommand("settings get secure sysui_qs_tiles")?.trim().orEmpty()
        if (spec !in current) {
            AppLogger.i(tag, "Screen recorder tile missing from QS layout, appending it")
            execShellCommand("settings put secure sysui_qs_tiles \"$current,$spec\"")
            Thread.sleep(1000)
        }
        recorderTileEnsured = true
    }

    // ── Start/stop triggers: the tile toggles ──

    override fun requestStart() {
        AppLogger.i(tag, "Clicking screen recorder tile")
        execShellCommand("cmd statusbar click-tile $SAMSUNG_RECORDER_TILE")
    }

    override fun requestStop() {
        AppLogger.i(tag, "Clicking screen recorder tile to stop the session")
        execShellCommand("cmd statusbar click-tile $SAMSUNG_RECORDER_TILE")
    }

    /**
     * Waits for Samsung's recorder confirmation dialog and taps its positive button.
     *
     * Instead of a separate `dumpsys window` focus poll followed by a single dump (record.sh
     * style), this loops `uiautomator dump` directly: on the fast path the dialog is already in
     * the first dump (~0.5–1 s saved per start). Matches the button by resource-id first
     * (locale-safe), with the (localized) text as a fallback.
     *
     * @return true if the button appeared and was tapped within the timeout.
     */
    override fun confirmStart(): Boolean {
        val deadline = System.currentTimeMillis() + DIALOG_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val xml = dumpUiHierarchy()
            if (xml != null) {
                val nodeTags = Regex("<node[^>]*>").findAll(xml).map { it.value }

                // The "Media and mic" audio option must be selected, otherwise the recording is
                // silent on one or both sides. The checked state lives on the child RadioButton
                // (the node right after the option's container in document order); the container
                // is the clickable one. If it is not selected, tap it and re-verify on the next
                // loop iteration before tapping Start.
                val audioTag = nodeTags.firstOrNull { it.contains("resource-id=\"$SAMSUNG_CAPTURE_PACKAGE:id/$DIALOG_AUDIO_MEDIA_AND_MIC_ID\"") }
                if (audioTag != null && !isMediaAndMicSelected(xml)) {
                    if (tapNodeCenter(audioTag, "'Media and mic' audio option")) {
                        Thread.sleep(DIALOG_POLL_MS)
                        continue
                    }
                    AppLogger.w(tag, "Could not locate 'Media and mic' option bounds, proceeding with current audio setting")
                }

                val buttonTag = nodeTags.firstOrNull { it.contains("resource-id=\"$DIALOG_BUTTON_RESOURCE_ID\"") }
                    ?: nodeTags.firstOrNull { it.contains("text=\"$DIALOG_BUTTON_FALLBACK_TEXT\"") }
                if (buttonTag != null) {
                    return tapNodeCenter(buttonTag, "recorder dialog positive button")
                }
            }
            Thread.sleep(DIALOG_POLL_MS)
        }
        AppLogger.w(tag, "Recorder dialog positive button never appeared (device locked?)")
        return false
    }

    /**
     * True when the recorder dialog's "Media and mic" option is currently selected.
     * The option container ($DIALOG_AUDIO_MEDIA_AND_MIC_ID) is not itself checkable; the state
     * is on the child `android.widget.RadioButton` node that follows it in document order.
     * Defaults to true when the structure is not recognized so we never block the recording.
     */
    private fun isMediaAndMicSelected(xml: String): Boolean {
        val idPos = xml.indexOf(DIALOG_AUDIO_MEDIA_AND_MIC_ID)
        if (idPos < 0) return true
        val after = xml.substring(idPos)
        val radioTag = Regex("<node[^>]*RadioButton[^>]*>").find(after)?.value ?: return true
        return radioTag.contains("checked=\"true\"")
    }

    /**
     * Skips Samsung's ~3 s pre-recording countdown by tapping the screen center (verified
     * on-device: ~1.4 s vs ~3.7 s to projection-active; upper-third taps are ignored by the
     * overlay). Multiple spaced taps because the overlay's appearance time varies. Best-effort:
     * if all taps miss, the countdown simply runs its course and the projection poll still
     * catches the session start.
     */
    override fun afterStartConfirmed() {
        val (x, y) = getCountdownSkipTapPoint()
        repeat(3) { attempt ->
            Thread.sleep(350)
            AppLogger.d(tag, "Tapping ($x, $y) to skip the recorder countdown (attempt ${attempt + 1})")
            execShellCommand("input tap $x $y")
        }
    }

    /**
     * Accessibility mode: single center tap for the driver's tap loop — taps keep firing every
     * poll interval until the projection appears, so the countdown gets skipped regardless of
     * how late the overlay shows up.
     */
    override fun skipCountdownOnce() {
        val (x, y) = getCountdownSkipTapPoint()
        AppLogger.d(tag, "Tapping ($x, $y) to skip the recorder countdown (accessibility mode)")
        execShellCommand("input tap $x $y")
    }

    /** Screen center parsed from `wm size`, cached per driver lifetime. */
    private var cachedScreenCenter: Pair<Int, Int>? = null

    private fun getCountdownSkipTapPoint(): Pair<Int, Int> {
        cachedScreenCenter?.let { return it }
        val size = execShellCommand("wm size")?.trim().orEmpty()
        val match = Regex("(\\d+)x(\\d+)").find(size)
        val point = if (match != null) {
            val (w, h) = match.destructured
            // Screen center: verified on-device that only center taps skip the countdown. Center
            // of an in-call screen is the contact/avatar area, so a stray pass-through tap is
            // harmless there.
            Pair(w.toInt() / 2, h.toInt() / 2)
        } else {
            Pair(720, 1560) // Center fallback for 1440x3120-class devices.
        }
        cachedScreenCenter = point
        return point
    }

    /**
     * Finds the newest finalized recording in [SAMSUNG_RECORDINGS_DIR] with mtime >= [minEpochSec].
     * Hidden ".pending-*.mp4" files are skipped: Samsung writes the recording under that name
     * while finalizing and renames it to the final name only once the file is complete.
     * Grabbing the pending file yields an unreadable/incomplete mp4 (no tracks).
     */
    override fun findLatestRecording(minEpochSec: Long): File? {
        return File(SAMSUNG_RECORDINGS_DIR).listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") && it.extension.equals("mp4", ignoreCase = true) && it.lastModified() / 1000 >= minEpochSec }
            ?.maxByOrNull { it.lastModified() }
    }
}
