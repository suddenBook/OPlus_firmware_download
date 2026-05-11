package com.desmond.ofd.device

import android.os.Build
import java.util.concurrent.TimeUnit

/**
 * Live reader of the running device's OTA-relevant properties. Use [snapshot] in
 * production code; tests should construct a [DeviceSnapshot] directly with fixture values.
 *
 * Most props live on `android.os.SystemProperties`, which is `@hide` and accessed via
 * reflection. `Build.*` covers what's exposed publicly.
 */
object DeviceProps {

    fun snapshot(useShellFallback: Boolean = false): DeviceSnapshot = DeviceSnapshot(
        productName = Build.PRODUCT,
        brand = Brand.fromBuildBrand(Build.BRAND),
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER,
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        otaVersion = systemProperty("ro.build.version.ota", useShellFallback),
        nvId = systemProperty("ro.build.oplus_nv_id", useShellFallback),
        realmeUi = systemProperty("ro.build.version.realmeui", useShellFallback),
        oplusRom = systemProperty("ro.build.version.oplusrom", useShellFallback),
        displayId = systemProperty("ro.build.display.id", useShellFallback),
    )

    /**
     * Reflectively reads `android.os.SystemProperties.get(String)`. Returns null if either the
     * class lookup fails (shouldn't on Android) or the property is unset / blank.
     */
    private fun systemProperty(key: String, useShellFallback: Boolean): String? =
        reflectionSystemProperty(key) ?: if (useShellFallback) shellSystemProperty(key) else null

    private fun reflectionSystemProperty(key: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        val get = cls.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }

    private fun shellSystemProperty(key: String): String? = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("/system/bin/getprop", key))
        if (!process.waitFor(1, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        process.inputStream.bufferedReader().use { reader ->
            reader.readText().trim().takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}
