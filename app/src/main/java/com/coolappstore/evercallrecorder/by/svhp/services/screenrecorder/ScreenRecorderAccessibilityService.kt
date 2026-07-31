/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.coolappstore.evercallrecorder.by.svhp.services.screenrecorder

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.coolappstore.evercallrecorder.by.svhp.data.AppPreferences
import com.coolappstore.evercallrecorder.by.svhp.utils.AppLogger

/**
 * App-side [AccessibilityService] that confirms Samsung's screen recorder "Start recording"
 * dialog in-memory, replacing the slow shell-side `uiautomator dump` + `input tap` path
 * (~2-5 s per dump → ~10-30 ms node lookup). Selected via the "Recorder dialog confirmation"
 * setting ([com.coolappstore.evercallrecorder.by.svhp.data.AppPreferences.ScreenRecorderConfirmMode]).
 *
 * It mirrors exactly what the uiautomator path does in [SamsungScreenRecorderDriver.confirmStart]:
 *  1. Forces the dialog's audio option to "Media and mic" (otherwise one side of the call is silent).
 *  2. Clicks the dialog's positive button (`android:id/button1`).
 *  3. Skips the ~3 s pre-recording countdown with [dispatchGesture] center taps. The shell
 *     process ALSO fires its own blind `input tap`s [ScreenRecorderDriver.EXTERNAL_CONFIRM_TAP_DELAY_MS]
 *     after the tile click in this mode — belt and suspenders; extra center taps are harmless.
 *
 * The service is restricted (via its xml config) to the Smart Capture package only, so it sees
 * no other app's windows. The user must enable it manually in system Accessibility settings;
 * when it is not enabled, the recording flow falls back to the uiautomator path automatically.
 * It also self-gates on the confirmation-mode preference so both mechanisms never race the
 * same dialog.
 */
class ScreenRecorderAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SCR:ScreenRecorderAccessibilityService"

        /** Fully-qualified view id of the dialog's positive ("Start recording") button. Locale-safe. */
        private const val DIALOG_BUTTON_ID = "android:id/button1"

        /** View id of the "Media and mic" audio option (clickable container) in the recorder dialog. */
        private const val MEDIA_AND_MIC_ID =
            "${SamsungScreenRecorderDriver.SAMSUNG_CAPTURE_PACKAGE}:id/${SamsungScreenRecorderDriver.DIALOG_AUDIO_MEDIA_AND_MIC_ID}"

        /** Min delay between two positive-button clicks; repeated window events fire in bursts. */
        private const val CLICK_DEBOUNCE_MS = 2_000L

        /** Countdown-skip taps: same spacing as the shell-side uiautomator flow (3 taps, 350 ms apart). */
        private const val COUNTDOWN_SKIP_TAP_COUNT = 3
        private const val COUNTDOWN_SKIP_TAP_SPACING_MS = 350L

        /**
         * True when the user has enabled this service in system Accessibility settings.
         * Reads the flattened `enabled_accessibility_services` secure setting.
         */
        fun isServiceEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ScreenRecorderAccessibilityService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
        }

        /** Opens the system Accessibility settings screen so the user can enable the service. */
        fun openAccessibilitySettings(context: Context) {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Epoch ms of the last successful positive-button click (debounce). */
    @Volatile
    private var lastButtonClickMs = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName != SamsungScreenRecorderDriver.SAMSUNG_CAPTURE_PACKAGE) return

        // Self-gate: only act when the user actually selected the accessibility confirmation
        // mode. Without this, the uiautomator flow and this service race on the same dialog:
        // the service dismisses it instantly, the shell-side dump then never sees the button
        // and reports a false "session never became active" failure while the recorder runs.
        if (AppPreferences(this).getScreenRecorderConfirmMode() != AppPreferences.ScreenRecorderConfirmMode.ACCESSIBILITY) return

        // A previous attempt at this service failed because the dialog never got clicked: the
        // recorder dialog is a system alert and may NOT be the "active" window, so the node tree
        // is looked up in BOTH the active window root and the event's own source node.
        val roots = listOfNotNull(rootInActiveWindow, event.source)
        try {
            for (root in roots) {
                ensureMediaAndMicSelected(root)
                clickStartButton(root)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error while handling recorder dialog: ${e.message}")
        }
    }

    override fun onInterrupt() {
        // No-op: nothing to interrupt, clicks are fire-and-forget.
    }

    /**
     * Selects the "Media and mic" audio option when it is not already checked.
     * The checked state lives on the (child) checkable node; the container with
     * [MEDIA_AND_MIC_ID] is the clickable one.
     */
    private fun ensureMediaAndMicSelected(root: AccessibilityNodeInfo) {
        val container = root.findAccessibilityNodeInfosByViewId(MEDIA_AND_MIC_ID)?.firstOrNull() ?: return
        if (isChecked(container)) {
            AppLogger.v(TAG, "'Media and mic' audio option already selected")
            return
        }
        AppLogger.i(TAG, "Selecting 'Media and mic' audio option")
        container.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /** True when [node] or any of its descendants is a checked checkable. */
    private fun isChecked(node: AccessibilityNodeInfo): Boolean {
        if (node.isCheckable) return node.isChecked
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> if (child.isCheckable) return child.isChecked }
        }
        return false
    }

    /**
     * Clicks the dialog's positive ("Start recording") button, debounced: the same dialog fires
     * several window events in a row, and a double click could reach the recorder UI underneath.
     *
     * Double-click / wrong-dialog guard: later recorder dialogs (e.g. a stop confirmation) share
     * `android:id/button1`, so the button is only clicked when the audio-options node
     * ([MEDIA_AND_MIC_ID]) exists in the same window — that node is unique to the START dialog.
     * Once the recorder session is running, no other dialog gets auto-clicked.
     *
     * On success, schedules the countdown-skip taps (the shell-side flow cannot time them in
     * accessibility mode — it does not know when the dialog closes).
     */
    private fun clickStartButton(root: AccessibilityNodeInfo) {
        val isStartDialog = !root.findAccessibilityNodeInfosByViewId(MEDIA_AND_MIC_ID).isNullOrEmpty()
        if (!isStartDialog) return

        val button = root.findAccessibilityNodeInfosByViewId(DIALOG_BUTTON_ID)?.firstOrNull() ?: return
        val now = System.currentTimeMillis()
        if (now - lastButtonClickMs < CLICK_DEBOUNCE_MS) return

        val clicked = button.takeIf { it.isClickable }?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (clicked) {
            lastButtonClickMs = now
            AppLogger.i(TAG, "Clicked recorder dialog positive button via accessibility")
            scheduleCountdownSkipTaps()
        }
    }

    /**
     * Skips Samsung's ~3 s pre-recording countdown by tapping the screen center via
     * [dispatchGesture] (the accessibility-mode replacement for the shell-side `input tap`
     * in [SamsungScreenRecorderDriver.afterStartConfirmed], which is skipped in this mode).
     * Multiple spaced taps because the overlay's appearance time varies; a stray center tap
     * on an in-call screen is harmless (avatar area). Best-effort.
     */
    private fun scheduleCountdownSkipTaps() {
        val handler = Handler(Looper.getMainLooper())
        repeat(COUNTDOWN_SKIP_TAP_COUNT) { attempt ->
            handler.postDelayed({
                AppLogger.d(TAG, "Dispatching center tap to skip the recorder countdown (attempt ${attempt + 1})")
                dispatchCenterTap()
            }, COUNTDOWN_SKIP_TAP_SPACING_MS * (attempt + 1))
        }
    }

    /** Dispatches a single tap gesture at the screen center. */
    private fun dispatchCenterTap() {
        val metrics = resources.displayMetrics
        val path = Path().apply { moveTo(metrics.widthPixels / 2f, metrics.heightPixels / 2f) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
