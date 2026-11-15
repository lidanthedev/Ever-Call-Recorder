/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.coolappstore.evercallrecorder.by.svhp.services.screenrecorder

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
 * The countdown runs normally; no gestures or coordinate taps are sent.
 *
 * The service is restricted (via its xml config `packageNames`) to the registered recorder
 * packages only, so it sees no other app's windows. The user must enable it manually in system
 * Accessibility settings. When it is not enabled, accessibility-mode recording fails safely.
 * It also self-gates on the confirmation-mode preference so
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
                if (!isConfirmationExpected) break
                if (root.packageName?.toString() != driver.recorderPackage) continue
                if (ensureRequiredAudioOptionSelected(root, driver)) {
                    clickStartButton(root, driver)
                }
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
     * ([ScreenRecorderDriver.startDialogRequiredAudioOptionIds] is empty).
     * The checked state lives on the (child) checkable node; the container is the clickable one.
     */
    private fun ensureRequiredAudioOptionSelected(root: AccessibilityNodeInfo, driver: ScreenRecorderDriver): Boolean {
        if (driver.startDialogRequiredAudioOptionIds.isEmpty()) return true
        val container = driver.startDialogRequiredAudioOptionIds.firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
        } ?: return false
        if (!container.isVisibleToUser || !container.isEnabled) return false
        if (isChecked(container)) return true
        if (!container.isClickable) return false

        AppLogger.i(TAG, "Selecting required audio option (${container.viewIdResourceName})")
        if (!container.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return false
        return container.refresh() && isChecked(container)
    }

    /** True when [node] or any of its descendants is a checked checkable. */
    private fun isChecked(node: AccessibilityNodeInfo): Boolean {
        if (node.isCheckable) return node.isChecked
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child -> if (isChecked(child)) return true }
        }
        return false
    }

    /**
     * Clicks the start dialog's positive button once per app-initiated start attempt.
     *
     * Double-click / wrong-dialog guard: later recorder dialogs (e.g. a stop confirmation) can
     * share the same positive-button id, so the button is only clicked when the driver's
     * required audio-option node exists in the same window — that node is unique to the START
     * dialog. Once the recorder session is running, no other dialog gets auto-clicked.
     * (For drivers WITHOUT a required audio option, the button-id match alone decides.)
     */
    private fun clickStartButton(root: AccessibilityNodeInfo, driver: ScreenRecorderDriver) {
        val button = root.findAccessibilityNodeInfosByViewId(driver.startDialogButtonId)?.firstOrNull() ?: return
        val clicked = button.takeIf { it.isVisibleToUser && it.isEnabled && it.isClickable }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        if (clicked) {
            isConfirmationExpected = false
            AppLogger.i(TAG, "Clicked recorder dialog positive button via accessibility")
        }
    }
}
