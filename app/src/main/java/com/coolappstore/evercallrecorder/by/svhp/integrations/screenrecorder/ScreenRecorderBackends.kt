/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.coolappstore.evercallrecorder.by.svhp.integrations.screenrecorder

import android.content.Context
import android.content.pm.PackageManager
import com.coolappstore.evercallrecorder.by.svhp.services.screenrecorder.SamsungScreenRecorderDriver

/**
 * App-side metadata for each system screen recorder backend, used to gate the
 * "Apps audio source" dropdown: a backend is only selectable when its recorder
 * package is installed on the device (see [ScreenRecorderBackends.isSupported]).
 *
 * The shell-side automation itself lives in
 * [com.coolappstore.evercallrecorder.by.svhp.services.screenrecorder.ScreenRecorderDriver]
 * implementations, keyed by the same backend key.
 */
data class ScreenRecorderBackendInfo(
    /** Stable key shared with `ScrcpyAudioSource.screenRecorderBackendKey` and the shell driver. */
    val backendKey: String,
    /** Package that must be installed for this backend to work (owns the recorder). */
    val requiredPackage: String
)

object ScreenRecorderBackends {

    private val backends = listOf(
        ScreenRecorderBackendInfo(
            backendKey = "samsung",
            requiredPackage = SamsungScreenRecorderDriver.SAMSUNG_CAPTURE_PACKAGE
        )
    )

    /** True when [backendKey] is known and its recorder package is installed. */
    fun isSupported(context: Context, backendKey: String): Boolean {
        val info = backends.firstOrNull { it.backendKey == backendKey } ?: return false
        return try {
            context.packageManager.getPackageInfo(info.requiredPackage, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
