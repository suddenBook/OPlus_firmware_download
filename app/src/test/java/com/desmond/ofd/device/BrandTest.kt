package com.desmond.ofd.device

import org.junit.Assert.assertEquals
import org.junit.Test

class BrandTest {

    @Test fun fromBuildBrand_normal_cases() {
        assertEquals(Brand.OnePlus, Brand.fromBuildBrand("OnePlus"))
        assertEquals(Brand.OnePlus, Brand.fromBuildBrand("oneplus"))
        assertEquals(Brand.OPPO, Brand.fromBuildBrand("OPPO"))
        assertEquals(Brand.OPPO, Brand.fromBuildBrand("oppo"))
        assertEquals(Brand.Realme, Brand.fromBuildBrand("realme"))
        assertEquals(Brand.Realme, Brand.fromBuildBrand("Realme"))
        assertEquals(Brand.Other, Brand.fromBuildBrand("Samsung"))
        assertEquals(Brand.Other, Brand.fromBuildBrand(null))
    }

    @Test fun detect_from_marketing_name() {
        assertEquals(Brand.OnePlus, Brand.detect("OnePlus 15"))
        assertEquals(Brand.OnePlus, Brand.detect("OnePlus Pad 3"))
        assertEquals(Brand.OPPO, Brand.detect("OPPO Find X9 Pro"))
        assertEquals(Brand.Realme, Brand.detect("realme GT 8 Pro"))
        assertEquals(Brand.Realme, Brand.detect("Realme GT5 PRO"))
        assertEquals(Brand.Other, Brand.detect("Random Phone Maker"))
    }
}
