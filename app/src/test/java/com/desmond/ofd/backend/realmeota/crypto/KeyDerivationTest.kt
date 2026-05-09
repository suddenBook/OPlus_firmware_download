package com.desmond.ofd.backend.realmeota.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyDerivationTest {

    @Test fun derive_matches_python_pseudo_54321abcdefghij() {
        // First-digit char '5' -> keysTable[5] = "0oiu3jdy"; chars [4..11] = "1abcdefg"
        assertEquals(
            "306f6975336a64793161626364656667",
            KeyDerivation.derive("54321abcdefghij").toHex(),
        )
    }

    @Test fun derive_matches_python_pseudo_012345678901234() {
        // First-digit '0' -> "oppo1997"; chars [4..11] = "45678901"
        assertEquals(
            "6f70706f313939373435363738393031",
            KeyDerivation.derive("012345678901234").toHex(),
        )
    }

    @Test fun derive_matches_python_pseudo_987abc123def456() {
        // First-digit '9' -> "87j3id7w"; chars [4..11] = "bc123def"
        assertEquals(
            "38376a33696437776263313233646566",
            KeyDerivation.derive("987abc123def456").toHex(),
        )
    }

    @Test fun derive_always_returns_16_bytes() {
        repeat(10) {
            val key = KeyDerivation.derive(KeyDerivation.randomCtrPseudoKey())
            assertEquals(16, key.size)
        }
    }

    @Test fun random_pseudo_keys_have_expected_shape() {
        val ctr = KeyDerivation.randomCtrPseudoKey()
        assertEquals(15, ctr.length)
        assert(ctr.all { it.isDigit() }) { "CTR pseudo-key must be all digits, got '$ctr'" }

        val ecb = KeyDerivation.randomEcbPseudoKey()
        assertEquals(15, ecb.length)
        assert(ecb[0].isDigit()) { "ECB pseudo-key first char must be digit, got '${ecb[0]}'" }
        assert(ecb.drop(1).all { it.isLetterOrDigit() }) { "ECB tail must be alphanumeric: '$ecb'" }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
