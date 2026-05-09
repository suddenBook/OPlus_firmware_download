package com.desmond.ofd.backend.realmeota.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/** Golden vectors captured from realme-ota's `crypto.sha256()` (uppercase hex). */
class HashTest {

    @Test fun sha256_of_test() {
        assertEquals(
            "9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08",
            Hash.sha256Upper("test"),
        )
    }

    @Test fun sha256_of_default_imei_zeros() {
        assertEquals(
            "14BDCD6FD64180AF5E7791DF91B6AF8E9A3E7BC844997EB8C29252706DF97CA5",
            Hash.sha256Upper("000000000000000"),
        )
    }

    @Test fun sha256_of_real_imei() {
        assertEquals(
            "5E2B9930DF03823C27A5E9168EB4266185A663F8E9260EF72796623E6E729343",
            Hash.sha256Upper("860517089867818"),
        )
    }
}
