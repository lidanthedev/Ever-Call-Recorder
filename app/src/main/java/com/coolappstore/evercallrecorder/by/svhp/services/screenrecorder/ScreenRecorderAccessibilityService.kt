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
 * App-side [AccessibilityService] that confirms a system screen recorder's "Start recording"
 * dialog in-memory, replacing the slow shell-side `uiautomator dump` + `input tap` path
 * (~2-5 s per dump → ~10-30 ms node lookup). Selected via the "Recorder dialog confirmation"
 * setting ([com.coolappstore.evercallrecorder.by.svhp.data.AppPreferences.ScreenRecorderConfirmMode]).
 *
 * Vendor-agnostic: window events are routed to the [ScreenRecorderDriver] whose
 * [ScreenRecorderDriver.recorderPackage] matches the event's package, and all dialog specifics
 * (positive-button id, required audio-option id) come from that driver — nothing here is
 * Samsung-specific. For the Samsung driver it:
 *  1. Forces the dialog's audio option to "Media and mic" (otherwise one side of the call is silent).
 *  2. Clicks the dialog's positive button.
 *  3. Skips the pre-recording countdown with [dispatchGesture] center taps. The shell
 *     process ALSO fires its own blind `input tap`s [ScreenRecorderDriver.EXTERNAL_CONFIRM_TAP_DELAY_MS]
 *     after the tile click in this mode — belt and suspenders; extra center taps are harmless.
 *
 * The service is restricted (via its xml config `packageNames`) to the registered recorder
 * packages only, so it sees no other app's windows. The user must enable it manually in system
 * Accessibility settings; when it is not enabled, the recording flow falls back to the
 * uiautomator path automatically. It also self-gates on the confirmation-mode preference so
 * both mechanisms never race the same dialog, AND on [isConfirmationExpected] so it never
 * confirms a dialog the user opened manually via the QS tile.
 */
class ScreenRecorderAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SCR:ScreenRecorderAccessibilityService"

        /**
         * True only while OUR app is waiting for the recorder's start dialog (set by
         * [com.coolappstore.evercallrecorder.by.svhp.services.recording.ScreenRecordingSession]
         * around its shell-side start call, in accessibility confirmation mode only).
         * Prevents the service from auto-confirming a dialog the USER opened manually by
         * tapping the QS tile themselves — with no call in progress, the dialog belongs to
         * the user and must not be clicked away.
         */
        @Volatile
        var isConfirmationExpected = false

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

    /** Single lazily-created preferences instance reused across events. Reads are always live
     * (SharedPreferences), so the confirmation-mode gate below still sees the current value. */
    private val preferences by lazy { AppPreferences(this) }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        // Route to the vendor driver owning this recorder package; ignore everything else.
        val driver = ScreenRecorderDrivers.forPackage(packageName) ?: return

        // Self-gate: only act when the user actually selected the accessibility confirmation
        // mode. Without this, the uiautomator flow and this service race on the same dialog:
        // the service dismisses it instantly, the shell-side dump then never sees the button
        // and reports a false "session never became active" failure while the recorder runs.
        if (preferences.getScreenRecorderConfirmMode() != AppPreferences.ScreenRecorderConfirmMode.ACCESSIBILITY) return

        // Only confirm dialogs WE triggered (an app call is being recorded). A dialog from a
        // manual QS tile tap (no call) belongs to the user — never click it.
        if (!isConfirmationExpected) {
            AppLogger.v(TAG, "Ignoring recorder dialog: no app-initiated recording session is expecting it")
            return
        }

        // A previous attempt at this service failed because the dialog never got clicked: the
        // recorder dialog is a system alert and may NOT be the "active" window, so the node tree
        // is looked up in BOTH the active window root and the event's own source node.
        val roots = listOfNotNull(rootInActiveWindow, event.source)
        try {
            for (root in roots) {
                ensureRequiredAudioOptionSelected(root, driver)
                clickStartButton(root, driver)
            }
        } catch (e: Exception) {
            AppLogger.w(TAG, "Error while handling recorder dialog: ${e.message}")
        }
    }

    override fun onInterrupt() {
        // No-op: nothing to interrupt, clicks are fire-and-forget.
    }

    /**
     * Selects the driver's required audio option (e.g. Samsung's "Media and mic") when it is
     * not already checked. No-op for vendors whose dialog has no such option
     * ([ScreenRecorderDriver.startDialogRequiredAudioOptionId] is null).
     * The checked state lives on the (child) checkable node; the container is the clickable one.
     */
    private fun ensureRequiredAudioOptionSelected(root: AccessibilityNodeInfo, driver: ScreenRecorderDriver) {
        val optionId = driver.startDialogRequiredAudioOptionId ?: return
        val container = root.findAccessibilityNodeInfosByViewId(optionId)?.firstOrNull() ?: return
        if (isChecked(container)) {
            AppLogger.v(TAG, "Required audio option already selected")
            return
        }
        AppLogger.i(TAG, "Selecting required audio option ($optionId)")
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
     * Clicks the start dialog's positive button, debounced: the same dialog fires several
     * window events in a row, and a double click could reach the recorder UI underneath.
     *
     * Double-click / wrong-dialog guard: later recorder dialogs (e.g. a stop confirmation) can
     * share the same positive-button id, so the button is only clicked when the driver's
     * required audio-option node exists in the same window — that node is unique to the START
     * dialog. Once the recorder session is running, no other dialog gets auto-clicked.
     * (For drivers WITHOUT a required audio option, the button-id match alone decides.)
     *
     * On success, schedules the countdown-skip taps (the shell-side flow cannot time them in
     * accessibility mode — it does not know when the dialog closes).
     */
    private fun clickStartButton(root: AccessibilityNodeInfo, driver: ScreenRecorderDriver) {
        val optionId = driver.startDialogRequiredAudioOptionId
        if (optionId != null && root.findAccessibilityNodeInfosByViewId(optionId).isNullOrEmpty()) return

        val button = root.findAccessibilityNodeInfosByViewId(driver.startDialogButtonId)?.firstOrNull() ?: return
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
     * Skips a pre-recording countdown by tapping the screen center via [dispatchGesture]
     * (the accessibility-mode replacement for the shell-side `input tap` in
     * [SamsungScreenRecorderDriver.afterStartConfirmed], which is skipped in this mode).
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
