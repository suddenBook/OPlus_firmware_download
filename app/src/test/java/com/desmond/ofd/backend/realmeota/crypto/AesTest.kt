package com.desmond.ofd.backend.realmeota.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden vectors captured from realme-ota Python output (`/tmp/golden_vectors.py`).
 * The deterministic paths (ECB and CTR-v1 with fixed pseudo-key) are byte-compared;
 * CTR-v2 (random key/iv) gets a round-trip plus a Python-encrypted decryption check.
 */
class AesTest {

    // ---- ECB (deterministic with fixed pseudo-key) ----

    @Test fun ecb_empty_object() {
        assertEquals(
            "Qce1rbohV4qxFiZ6tCflEw==54321abcdefghij",
            Aes.encryptEcb("{}", "54321abcdefghij"),
        )
    }

    @Test fun ecb_hello_world_object() {
        assertEquals(
            "89cVkWoRhgAde2v747IFHu10+wP0P0TR2GZQbITDHSs=012345678901234",
            Aes.encryptEcb("""{"hello":"world"}""", "012345678901234"),
        )
    }

    @Test fun ecb_complex_object() {
        assertEquals(
            "ZaRxy38GOtvregJblcR1iqMZSDK2AgT6ee2EFy/DeEg=987abc123def456",
            Aes.encryptEcb("""{"a":1,"b":"two","c":[1,2,3]}""", "987abc123def456"),
        )
    }

    @Test fun ecb_round_trip() {
        val plain = """{"long":"string with non-ASCII chars: éñüç","n":42}"""
        val cipher = Aes.encryptEcb(plain)
        assertEquals(plain, Aes.decryptEcb(cipher))
    }

    // ---- CTR v1 (deterministic via MD5(derivedKey) IV with fixed pseudo-key) ----

    @Test fun ctrV1_short_text() {
        assertEquals(
            "vz6qgHFoApr1U/w=54321abcdefghij",
            Aes.encryptCtrV1("hello world", "54321abcdefghij"),
        )
    }

    @Test fun ctrV1_json_fragment() {
        assertEquals(
            "ynBAsWRGzwL/txFlRuI=012345678901234",
            Aes.encryptCtrV1("""{"params":"x"}""", "012345678901234"),
        )
    }

    @Test fun ctrV1_long_repeated_payload() {
        val plain = "a".repeat(64)
        assertEquals(
            "mG2rSmWLOXHVOpkVkLYe+rLkkVMbQjbeE1M//6PSakOcweX5TpEgZtAGdTwjx0eOdIxDub+iD5yxf8tsxGzA4Q==987abc123def456",
            Aes.encryptCtrV1(plain, "987abc123def456"),
        )
    }

    @Test fun ctrV1_round_trip() {
        val plain = "round-trip with unicode: 你好, 🎉"
        val cipher = Aes.encryptCtrV1(plain)
        assertEquals(plain, Aes.decryptCtrV1(cipher))
    }

    // ---- CTR v2 (random key/iv) ----

    @Test fun ctrV2_decrypts_python_output_empty_object() {
        // Python encrypted with key=00..1f (32B) and iv=a0..af (16B)
        val keyB64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        val ivB64 = "oKGio6SlpqeoqaqrrK2urw=="
        val cipherB64 = "p+I="
        assertEquals("{}", Aes.decryptCtrV2(cipherB64, keyB64, ivB64))
    }

    @Test fun ctrV2_decrypts_python_output_short_object() {
        val keyB64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        val ivB64 = "oKGio6SlpqeoqaqrrK2urw=="
        val cipherB64 = "p71530yJUC92adKnf3yDwpw="
        assertEquals("""{"x":1,"y":"two"}""", Aes.decryptCtrV2(cipherB64, keyB64, ivB64))
    }

    @Test fun ctrV2_decrypts_python_output_long_payload() {
        val keyB64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        val ivB64 = "oKGio6SlpqeoqaqrrK2urw=="
        val cipherB64 = "sPBzmBuYFX18PoWlZ2SehYzxgmEPNPgIgOWK7woQuu/qnomChAjrbxiOafDemu5Ro01PLrKBbaoYnBfofCweJrw3b8gzGMy6hIqVx+hWUG/c8ZK4jYnh5imeeH4ZT2i6"
        val expected = "lorem ipsum ".repeat(8)
        assertEquals(expected, Aes.decryptCtrV2(cipherB64, keyB64, ivB64))
    }

    @Test fun ctrV2_round_trip_random() {
        val plain = """{"hello":"world","arr":[1,2,3]}"""
        val r = Aes.encryptCtrV2(plain)
        assertEquals(plain, Aes.decryptCtrV2(r.cipherB64, r.keyB64, r.ivB64))
    }
}
