package com.desmond.ofd.backend.danielspringer

import com.desmond.ofd.backend.realmeota.data.Region
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup

/**
 * Parses the form HTML at `https://roms.danielspringer.at/index.php?view=ota` and exposes
 *
 *  - [devices] — the raw catalog from the `<select#device data-devices=...>` JSON attribute,
 *    keyed by site device label (e.g. `OP 15`) → site region (e.g. `CN`) → list of display names
 *    (e.g. `PLK110_16.0.7.206(CN01)`).
 *  - [siteForModel] — inverse mapping from a model code (`PLK110`) + our [Region] to the
 *    site-specific `(deviceLabel, regionLabel)` pair we need to POST.
 *  - [versionIndexFor] — locate a specific display name's index in the list (0 = newest).
 */
class DanielspringerCatalog(val devices: Map<String, Map<String, List<String>>>) {

    /** Pre-built inverse: `modelCode` → ([siteDevice], [siteRegion]). */
    private val byModel: Map<String, Pair<String, String>> = buildMap {
        for ((siteDevice, regions) in devices) {
            for ((siteRegion, displayNames) in regions) {
                for (display in displayNames) {
                    val model = display.substringBefore('_')
                    if (model.isNotEmpty() && model !in this) {
                        put(model, siteDevice to siteRegion)
                    }
                }
            }
        }
    }

    /** Look up the site's `(device, region)` labels for a given model + our Region. */
    fun siteForModel(model: String, region: Region): Pair<String, String>? {
        val cached = byModel[model] ?: return null
        val targetSiteRegion = region.toSiteRegion()
        if (cached.second == targetSiteRegion) return cached
        // Re-scan to find an entry that matches the target site region for this model.
        val (siteDevice, _) = cached
        val regions = devices[siteDevice] ?: return cached
        if (regions[targetSiteRegion]?.any { it.startsWith("${model}_") } == true) {
            return siteDevice to targetSiteRegion
        }
        return cached
    }

    /** Index of [displayName] inside `devices[siteDevice][siteRegion]`, or -1 if absent. */
    fun versionIndexFor(siteDevice: String, siteRegion: String, displayName: String): Int =
        devices[siteDevice]?.get(siteRegion)?.indexOf(displayName) ?: -1

    /** Total number of unique model codes recognized. */
    val modelCount: Int get() = byModel.size

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parse the form HTML and return a fully-built [DanielspringerCatalog]. */
        fun parse(formHtml: String): DanielspringerCatalog {
            val doc = Jsoup.parse(formHtml)
            val select = doc.selectFirst("select#device")
                ?: error("Form HTML missing <select id=\"device\">")
            val raw = select.attr("data-devices")
            if (raw.isBlank()) error("data-devices attribute is empty")
            val parsed = json.decodeFromString<Map<String, Map<String, List<String>>>>(raw)
            return DanielspringerCatalog(parsed)
        }
    }
}

/** Map our internal [Region] to danielspringer's region labels. */
fun Region.toSiteRegion(): String = when (this) {
    Region.GL -> "GLO"
    Region.CN -> "CN"
    Region.EU -> "EU"
    Region.IN -> "IN"
    Region.NA -> "NA"
}
