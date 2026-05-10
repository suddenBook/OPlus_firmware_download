package com.desmond.ofd.catalog

import android.content.Context
import com.desmond.ofd.backend.realmeota.data.Region
import com.desmond.ofd.device.Brand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * List-shaped accessor over [DeviceCatalog] for the manual-mode picker. Decorates the raw
 * `catalog.json` map with [Brand] detection + [Region] parsing, sorts by brand → name → model.
 */
class CatalogRepository(context: Context) {

    private val context = context.applicationContext

    suspend fun allDevices(): List<DeviceEntry> {
        cachedDevices?.let { return it }
        return withContext(Dispatchers.Default) {
            cachedDevices?.let { return@withContext it }
            synchronized(cacheLock) {
                cachedDevices ?: build().also { cachedDevices = it }
            }
        }
    }

    fun marketingName(model: String): String? =
        DeviceCatalog.marketingName(context, model)

    private fun build(): List<DeviceEntry> {
        val map = DeviceCatalog.load(context)
        return map.map { (model, entry) ->
            DeviceEntry(
                model = model,
                marketingName = entry.name,
                brand = Brand.detect(entry.name),
                regions = entry.regions.mapNotNull(::parseRegion).distinct(),
            )
        }.sortedWith(
            compareBy<DeviceEntry> { it.brand.ordinal }
                .then(naturalOrderComparator { it.marketingName })
                .thenBy { it.model },
        )
    }

    /**
     * Natural ordering: tokenises the string into runs of digits and non-digits, then compares
     * digit runs numerically and text runs lexicographically. Ensures "OnePlus 2" sorts before
     * "OnePlus 15" instead of after (alphabetic sort puts '1' before '2').
     */
    private fun <T> naturalOrderComparator(selector: (T) -> String): Comparator<T> =
        Comparator { a, b -> compareNatural(selector(a), selector(b)) }

    private fun compareNatural(a: String, b: String): Int {
        val left = NUMERIC_OR_TEXT.findAll(a).map { it.value }.toList()
        val right = NUMERIC_OR_TEXT.findAll(b).map { it.value }.toList()
        val n = minOf(left.size, right.size)
        for (i in 0 until n) {
            val l = left[i]
            val r = right[i]
            val li = l.toLongOrNull()
            val ri = r.toLongOrNull()
            val cmp = when {
                li != null && ri != null -> li.compareTo(ri)
                else -> l.compareTo(r, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return left.size.compareTo(right.size)
    }

    private fun parseRegion(raw: String): Region? = when (raw.uppercase()) {
        "CN" -> Region.CN
        "IN" -> Region.IN
        "EU" -> Region.EU
        "NA" -> Region.NA
        // ROW / GLO / GLOBAL all map to GL.
        "GL", "GLO", "GLOBAL", "ROW" -> Region.GL
        else -> null
    }

    private companion object {
        private val cacheLock = Any()
        @Volatile private var cachedDevices: List<DeviceEntry>? = null
        private val NUMERIC_OR_TEXT = Regex("""\d+|\D+""")
    }
}
