package com.desmond.ofd.catalog

import com.desmond.ofd.backend.realmeota.data.Region
import com.desmond.ofd.device.Brand

/** One row in the manual-mode device picker, with all known sale regions. */
data class DeviceEntry(
    val model: String,
    val marketingName: String,
    val brand: Brand,
    val regions: List<Region>,
)
