/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.coolappstore.evercallrecorder.by.svhp.services.screenrecorder

/**
 * Registry of all screen recorder drivers, keyed by `ScrcpyAudioSource.screenRecorderBackendKey`.
 *
 * Adding support for a new vendor's system screen recorder requires exactly four things:
 *  1. A new [ScreenRecorderDriver] subclass in this package (see the class kdoc for the contract).
 *  2. One line in [registry] below mapping its backend key to its factory.
 *  3. A new entry in `ScrcpyAudioSource` carrying the same `screenRecorderBackendKey`
 *     (plus its strings, and its availability gate in
 *     [com.coolappstore.evercallrecorder.by.svhp.integrations.screenrecorder.ScreenRecorderBackends]).
 *  4. The vendor recorder package added to `packageNames` in
 *     `res/xml/screen_recorder_accessibility_service.xml` (static xml — accessibility
 *     confirmation cannot see the vendor's dialog without it).
 */
object ScreenRecorderDrivers {

    private val registry: Map<String, () -> ScreenRecorderDriver> = mapOf(
        "samsung" to { SamsungScreenRecorderDriver() }
    )

    /** Live driver instances. Drivers cache state (tile presence, screen size) that must
     * survive across AIDL calls, so instances are memoized for the shell process lifetime. */
    private val instances = mutableMapOf<String, ScreenRecorderDriver>()

    /**
     * Returns the shared driver instance for [backendKey], or null when the key is unknown.
     * Synchronized: AIDL calls arrive on binder thread-pool threads.
     */
    @Synchronized
    fun forKey(backendKey: String): ScreenRecorderDriver? {
        instances[backendKey]?.let { return it }
        val created = registry[backendKey]?.invoke() ?: return null
        instances[backendKey] = created
        return created
    }

    /**
     * Returns the driver whose [ScreenRecorderDriver.recorderPackage] matches [packageName],
     * or null when no registered backend owns that package. Used by the app-side
     * [ScreenRecorderAccessibilityService] to route window events to the right vendor driver.
     * Instantiates drivers lazily as needed; instances are shared with [forKey].
     */
    @Synchronized
    fun forPackage(packageName: String): ScreenRecorderDriver? {
        for (key in registry.keys) {
            val driver = forKey(key) ?: continue
            if (driver.recorderPackage == packageName) return driver
        }
        return null
    }
}
