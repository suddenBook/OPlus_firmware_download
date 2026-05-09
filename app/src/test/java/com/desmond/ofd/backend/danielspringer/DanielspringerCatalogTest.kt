package com.desmond.ofd.backend.danielspringer

import com.desmond.ofd.backend.realmeota.data.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DanielspringerCatalogTest {

    private val formHtml: String =
        DanielspringerCatalogTest::class.java
            .getResource("/danielspringer/form.html")!!
            .readText(Charsets.UTF_8)

    @Test fun parses_data_devices_into_nested_map() {
        val catalog = DanielspringerCatalog.parse(formHtml)
        assertTrue(
            "expected at least 30 site devices, got ${catalog.devices.size}",
            catalog.devices.size >= 30,
        )
    }

    @Test fun op15_cn_index_zero_is_plk110_16_0_7_206() {
        val catalog = DanielspringerCatalog.parse(formHtml)
        val versions = catalog.devices["OP 15"]?.get("CN")
        assertNotNull(versions)
        assertEquals("PLK110_16.0.7.206(CN01)", versions!!.first())
    }

    @Test fun siteForModel_resolves_plk110_to_op15_cn() {
        val catalog = DanielspringerCatalog.parse(formHtml)
        val resolved = catalog.siteForModel("PLK110", Region.CN)
        assertEquals("OP 15" to "CN", resolved)
    }

    @Test fun siteForModel_resolves_global_oneplus15_to_glo() {
        val catalog = DanielspringerCatalog.parse(formHtml)
        // CPH2747 is the EU/GLO variant of OnePlus 15.
        val resolved = catalog.siteForModel("CPH2747", Region.GL)
        assertNotNull(resolved)
        assertEquals("OP 15", resolved!!.first)
        assertEquals("GLO", resolved.second)
    }

    @Test fun siteForModel_returns_null_for_unknown_model() {
        val catalog = DanielspringerCatalog.parse(formHtml)
        assertNull(catalog.siteForModel("ZZZUNKNOWN", Region.GL))
    }

    @Test fun versionIndex_finds_specific_version() {
        val catalog = DanielspringerCatalog.parse(formHtml)
        val idx = catalog.versionIndexFor("OP 15", "CN", "PLK110_16.0.5.702(CN01)")
        // Version_index=1 was 16.0.5.702 in the captured HTML (0=16.0.7.206, 1=16.0.5.702).
        assertEquals(1, idx)
    }

    @Test fun region_to_site_region_mapping() {
        assertEquals("GLO", Region.GL.toSiteRegion())
        assertEquals("CN", Region.CN.toSiteRegion())
        assertEquals("EU", Region.EU.toSiteRegion())
        assertEquals("IN", Region.IN.toSiteRegion())
        assertEquals("NA", Region.NA.toSiteRegion())
    }

    @Test fun siteForModel_resolves_oneplus_13_NA_variant() {
        val catalog = DanielspringerCatalog.parse(formHtml)
        val resolved = catalog.siteForModel("CPH2655", Region.NA)
        assertEquals("OP 13" to "NA", resolved)
    }

    @Test fun catalog_covers_a_few_well_known_models() {
        val catalog = DanielspringerCatalog.parse(formHtml)
        // Sanity: these must all be in the catalog from the captured HTML.
        listOf(
            "PLK110", "PLZ110", "PJZ110", "PJD110", "PKX110",
            "CPH2747", "CPH2745", "CPH2653", "CPH2649",
            "RMX5210", "RMX5011", "OPD2413", "PKH110",
        ).forEach { model ->
            assertNotNull("$model should be in the catalog", catalog.byModelOrNull(model))
        }
    }

    private fun DanielspringerCatalog.byModelOrNull(model: String): Pair<String, String>? =
        siteForModel(model, Region.CN)
            ?: siteForModel(model, Region.GL)
            ?: siteForModel(model, Region.EU)
            ?: siteForModel(model, Region.IN)
}
