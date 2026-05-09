package com.desmond.ofd.device

import com.desmond.ofd.backend.realmeota.data.Region
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceSnapshotTest {

    /** Captured props from the user's actual PLK110. */
    private val plk110: DeviceSnapshot = DeviceSnapshot(
        productName = "PLK110",
        brand = Brand.OnePlus,
        model = "PLK110",
        manufacturer = "OnePlus",
        androidRelease = "16",
        sdkInt = 36,
        otaVersion = "PLK110_11.A.63_0630_202605061316",
        nvId = "10010111",
        realmeUi = null,
        oplusRom = "V16.1.0",
        displayId = "PLK110_16.0.7.206(CN01)",
    )

    @Test fun rui_from_oplusrom_V16() {
        assertEquals(7, plk110.ruiVersion)
    }

    @Test fun rui_explicit_realmeui_wins() {
        val s = plk110.copy(realmeUi = "5", oplusRom = "V16.1.0")
        assertEquals(5, s.ruiVersion)
    }

    @Test fun rui_falls_back_to_sdk_when_no_props() {
        val s = plk110.copy(realmeUi = null, oplusRom = null, sdkInt = 34)
        assertEquals(5, s.ruiVersion) // Android 14 → RUI 5
    }

    @Test fun rui_caps_at_seven_for_future_android() {
        val s = plk110.copy(realmeUi = null, oplusRom = null, sdkInt = 99)
        assertEquals(7, s.ruiVersion)
    }

    @Test fun region_from_displayId_CN_suffix() {
        assertEquals(Region.CN, plk110.region)
    }

    @Test fun region_from_displayId_EX_suffix_is_GL() {
        val s = plk110.copy(displayId = "CPH2747_16.0.5.703(EX01)", productName = "CPH2747")
        assertEquals(Region.GL, s.region)
    }

    @Test fun region_from_displayId_IN_suffix() {
        val s = plk110.copy(displayId = "CPH2745_16.0.5.703(IN01)", productName = "CPH2745")
        assertEquals(Region.IN, s.region)
    }

    @Test fun region_from_displayId_NA_suffix() {
        val s = plk110.copy(displayId = "CPH2655_15.0.0.405(NA01)", productName = "CPH2655")
        assertEquals(Region.NA, s.region)
    }

    @Test fun na_endpoint_routes_via_GL() {
        assertEquals(Region.GL, Region.NA.endpointRegion)
        assertEquals(Region.GL, Region.GL.endpointRegion)
        assertEquals(Region.CN, Region.CN.endpointRegion)
    }

    @Test fun region_falls_back_to_model_prefix_PL() {
        val s = plk110.copy(displayId = null, productName = "PLZ110")
        assertEquals(Region.CN, s.region)
    }

    @Test fun region_falls_back_to_model_prefix_CPH() {
        val s = plk110.copy(displayId = null, productName = "CPH2769")
        assertEquals(Region.GL, s.region)
    }

    @Test fun region_falls_back_to_GL_for_unknown_prefix() {
        val s = plk110.copy(displayId = null, productName = "UNKNOWN")
        assertEquals(Region.GL, s.region)
    }
}
