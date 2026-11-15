/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.coolappstore.evercallrecorder.by.svhp

import android.app.Instrumentation
import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.provider.Settings
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.coolappstore.evercallrecorder.by.svhp.data.AppPreferences
import com.coolappstore.evercallrecorder.by.svhp.data.recordings.RecordingDirection
import com.coolappstore.evercallrecorder.by.svhp.data.recordings.RecordingMetadata
import com.coolappstore.evercallrecorder.by.svhp.integrations.shizuku.ShizukuConnectionManager
import com.coolappstore.evercallrecorder.by.svhp.services.recording.ScreenRecordingSession
import com.coolappstore.evercallrecorder.by.svhp.services.screenrecorder.SamsungScreenRecorderDriver
import com.coolappstore.evercallrecorder.by.svhp.services.screenrecorder.ScreenRecorderAccessibilityService
import com.coolappstore.evercallrecorder.by.svhp.services.screenrecorder.ScreenRecorderDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Device smoke test. Requires unlocked Samsung and granted Shizuku permission. */
class ScreenRecorderAccessibilityTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            runBlocking { verifyRecording() }
            result.putString("stream", "PASS: unarmed start timed out without clicks; accessibility start, stop and audio import succeeded")
            finish(-1, result)
        } catch (error: Throwable) {
            result.putString("stream", error.stackTraceToString())
            finish(0, result)
        }
    }

    private suspend fun verifyRecording() {
        // Keep the real app service running alongside the test's node inspection.
        val automation = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val preferences = AppPreferences(targetContext)
        val originalMode = preferences.getScreenRecorderConfirmMode()
        val rawPreferences = targetContext.getSharedPreferences("evercallrecorder_prefs", android.content.Context.MODE_PRIVATE)
        val originalStorageMode = rawPreferences.getString(AppPreferences.Key.STORAGE_MODE.id, null)
        val manager = ShizukuConnectionManager(targetContext)
        var session: ScreenRecordingSession? = null
        withTimeout(10_000) {
            while (!ShizukuConnectionManager.isAvailable()) delay(100)
        }
        val shell = withTimeout(10_000) { manager.getShellService() }
        check(!shell.isScreenRecordingActive("samsung")) { "A recording is already running; refusing to test" }
        val resolver = targetContext.contentResolver
        val previousServices = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val previousEnabled = Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        try {
            // Starting instrumentation force-stops the target and removes its accessibility registration.
            val component = ComponentName(targetContext, ScreenRecorderAccessibilityService::class.java).flattenToString()
            val otherServices = previousServices.orEmpty().split(':').filter { it.isNotEmpty() && it != component }
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, otherServices.joinToString(":"))
            delay(250)
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (otherServices + component).joinToString(":"))
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        } finally {
            automation.dropShellPermissionIdentity()
        }
        try {
            delay(1_000)
            preferences.setScreenRecorderConfirmMode(AppPreferences.ScreenRecorderConfirmMode.ACCESSIBILITY)
            ScreenRecorderAccessibilityService.isConfirmationExpected = false
            findNode(automation, "android:id/button2")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(500)
            // This ROM drops a cold tile's first shell click; bind the tiles before the smoke test.
            check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS))
            delay(1_000)
            check(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            delay(500)
            check(!shell.startScreenRecording("samsung", ScreenRecorderDriver.CONFIRM_ACCESSIBILITY)) {
                "An unarmed start must time out, without the shell tapping the dialog"
            }
            val startId = "android:id/button1"
            check(findNode(automation, startId) != null) {
                "Unarmed dialog missing; windows=${automation.windows.map { it.root?.packageName }}"
            }
            resultStatus("Unarmed start timed out with dialog intact")
            val silent = findNode(automation, "${SamsungScreenRecorderDriver.SAMSUNG_CAPTURE_PACKAGE}:id/sound_suggestion_sound_type_mute_radio")
                ?: findNode(automation, "${SamsungScreenRecorderDriver.SAMSUNG_CAPTURE_PACKAGE}:id/radio_button_sound_none")
            check(silent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Could not select No sound for audio-selection regression" }
            check(findNode(automation, "android:id/button2")?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
            delay(500)

            preferences.setStorageMode(AppPreferences.StorageMode.PRIVATE)
            session = ScreenRecordingSession(targetContext, shell,
                RecordingMetadata("AccessibilityTest", RecordingDirection.INCOMING), "samsung")
            session.start()
            check(shell.isScreenRecordingActive("samsung"))
            check(!ScreenRecorderAccessibilityService.isConfirmationExpected) { "Confirmation was not disarmed" }
            delay(2_000)
            val uri = checkNotNull(session.stopAndImport()) { "Audio import failed" }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(targetContext, uri, null)
                check(extractor.trackCount == 1)
                val format = extractor.getTrackFormat(0)
                check(format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true)
                check(format.getLong(MediaFormat.KEY_DURATION) > 0)
                resultStatus("Imported audio verified: $uri")
            } finally {
                extractor.release()
            }
        } finally {
            ScreenRecorderAccessibilityService.isConfirmationExpected = false
            runCatching { if (session?.isActive == true) session.stopAndImport() }
                .onFailure { resultStatus("Cleanup stop/import failed: ${it.message}") }
            // Dismiss only our recorder dialog if an assertion failed before confirmation.
            findNode(automation, "android:id/button2")?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            preferences.setScreenRecorderConfirmMode(originalMode)
            rawPreferences.edit().putString(AppPreferences.Key.STORAGE_MODE.id, originalStorageMode).commit()
            manager.unbind()
            automation.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
            try {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, previousServices)
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, previousEnabled)
            } finally {
                automation.dropShellPermissionIdentity()
            }
        }
    }

    private fun findNode(automation: UiAutomation, id: String): AccessibilityNodeInfo? {
        val roots = listOfNotNull(automation.rootInActiveWindow) + automation.windows.mapNotNull { it.root }
        return roots.filter { it.packageName?.toString() == SamsungScreenRecorderDriver.SAMSUNG_CAPTURE_PACKAGE }
            .firstNotNullOfOrNull { it.findAccessibilityNodeInfosByViewId(id)?.firstOrNull() }
    }

    private fun resultStatus(message: String) {
        sendStatus(0, Bundle().apply { putString("stream", message) })
    }
}
