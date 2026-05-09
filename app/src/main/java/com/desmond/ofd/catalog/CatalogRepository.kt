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
class CatalogRepository(private val context: Context) {

    @Volatile private var cached: List<DeviceEntry>? = null

    suspend fun allDevices(): List<DeviceEntry> = withContext(Dispatchers.IO) {
        cached ?: build().also { cached = it }
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
            compareBy(
                { it.brand.ordinal },
                { it.marketingName },
                { it.model },
            )
        )
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
}
