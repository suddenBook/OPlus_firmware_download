package com.desmond.ofd.device

import android.os.Build

/**
 * Live reader of the running device's OTA-relevant properties. Use [snapshot] in
 * production code; tests should construct a [DeviceSnapshot] directly with fixture values.
 *
 * Most props live on `android.os.SystemProperties`, which is `@hide` and accessed via
 * reflection. `Build.*` covers what's exposed publicly.
 */
object DeviceProps {

    fun snapshot(): DeviceSnapshot = DeviceSnapshot(
        productName = Build.PRODUCT,
        brand = Brand.fromBuildBrand(Build.BRAND),
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        otaVersion = systemProperty("ro.build.version.ota"),
        nvId = systemProperty("ro.build.oplus_nv_id"),
        realmeUi = systemProperty("ro.build.version.realmeui"),
        oplusRom = systemProperty("ro.build.version.oplusrom"),
        displayId = systemProperty("ro.build.display.id"),
    )

    /**
     * Reflectively reads `android.os.SystemProperties.get(String)`. Returns null if either the
     * class lookup fails (shouldn't on Android) or the property is unset / blank.
     */
    private fun systemProperty(key: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }
}
