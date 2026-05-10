package com.desmond.ofd.device

import com.desmond.ofd.backend.realmeota.data.Region

/**
 * A frozen read of a device's OTA-relevant system properties. Constructed by [DeviceProps.snapshot]
 * on a real device, or directly in tests with hand-picked values.
 *
 * Derived properties ([ruiVersion], [region]) use lazy evaluation so the data class's
 * [equals]/[hashCode]/[copy] contract stays based on raw inputs.
 */
data class DeviceSnapshot(
    /** `ro.product.name` — the model code, e.g. `PLK110`. */
    val productName: String,
    /** `ro.product.brand` — coerced to a [Brand] enum. */
    val brand: Brand,
    /** `ro.product.model` — usually the same as [productName] but not guaranteed. */
    val model: String,
    val manufacturer: String,
    /** `Build.VERSION.RELEASE`, e.g. `"16"`. */
    val androidRelease: String,
    /** `Build.VERSION.SDK_INT`, e.g. 36. */
    val sdkInt: Int,
    /** `ro.build.version.ota`, e.g. `PLK110_11.A.63_0630_202605061316`. */
    val otaVersion: String? = null,
    /** `ro.build.oplus_nv_id`, e.g. `10010111`. */
    val nvId: String? = null,
    /** `ro.build.version.realmeui` — explicit RUI integer, often empty on OnePlus. */
    val realmeUi: String? = null,
    /** `ro.build.version.oplusrom`, e.g. `V16.1.0`. */
    val oplusRom: String? = null,
    /** `ro.build.display.id`, e.g. `PLK110_16.0.7.206(CN01)`. */
    val displayId: String? = null,
) {
    /** ColorOS generation (1..7) inferred from props, with sensible fallbacks. */
    val ruiVersion: Int by lazy { deriveRuiVersion() }

    /** Region inferred from `displayId` suffix or model prefix, defaults to GL. */
    val region: Region by lazy { deriveRegion() }

    private fun deriveRuiVersion(): Int {
        // Direct integer in ro.build.version.realmeui — most authoritative when present.
        realmeUi?.toIntOrNull()?.takeIf { it in 1..7 }?.let { return it }

        // OPPO/OnePlus often expose version.oplusrom = "V16.1.0" — major number maps:
        //   V11 → RUI 2, V12 → RUI 3, ..., V16 → RUI 7.
        oplusRom?.let { rom ->
            ROM_MAJOR.find(rom)?.groupValues?.get(1)?.toIntOrNull()?.let { major ->
                if (major in 11..17) return major - 9
            }
        }

        // SDK-level fallback (each Android release coincides with a RUI bump).
        return when {
            sdkInt >= 36 -> 7   // Android 16+
            sdkInt == 35 -> 6   // Android 15
            sdkInt == 34 -> 5   // Android 14
            sdkInt == 33 -> 4   // Android 13
            sdkInt in 31..32 -> 3 // Android 12 / 12L
            sdkInt == 30 -> 2   // Android 11
            else -> 1
        }
    }

    private fun deriveRegion(): Region {
        // Strongest signal: ro.build.display.id like "PLK110_16.0.7.206(CN01)".
        displayId?.let { id ->
            DISPLAY_REGION.find(id)?.groupValues?.get(1)?.uppercase()?.let { code ->
                when (code) {
                    "CN" -> return Region.CN
                    "IN" -> return Region.IN
                    "EX" -> return Region.GL // OPPO conflates EU and Global under EX##
                    "EU" -> return Region.EU
                    "NA" -> return Region.NA
                }
            }
        }
        // Fallback: model-code prefix heuristic.
        val name = productName
        return when {
            name.length >= 2 && name.startsWith("PL") -> Region.CN
            name.length >= 2 && name.startsWith("PJ") -> Region.CN
            name.length >= 2 && name.startsWith("PK") -> Region.CN
            name.startsWith("CPH") -> Region.GL
            name.startsWith("RMX") -> Region.GL
            name.startsWith("OPD") -> Region.GL
            else -> Region.GL
        }
    }

    companion object {
        private val ROM_MAJOR = Regex("""V(\d+)""")
        private val DISPLAY_REGION = Regex("""\(([A-Z]{2})\d+\)\s*$""")
    }
}
