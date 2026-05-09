package com.desmond.ofd.backend.realmeota.crypto

import com.desmond.ofd.backend.realmeota.data.RealmeOtaConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class RsaTest {

    @Test fun production_pubKeys_load_correctly() {
        // All four region pubKeys must parse without throwing.
        for ((region, params) in RealmeOtaConfig.serverParamsByRegion) {
            val der = Base64.getDecoder().decode(params.pubKeyB64)
            val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
            assert(key.algorithm == "RSA") { "Region $region produced ${key.algorithm}" }
        }
    }

    @Test fun oaep_round_trip_with_self_generated_keypair() {
        // Generate a 2048-bit RSA keypair, then encrypt with our [Rsa.encryptOaepSha1] and
        // decrypt with the matching private key using identical OAEP parameters.
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val kp = kpg.generateKeyPair()
        val pubB64 = Base64.getEncoder().encodeToString(kp.public.encoded)

        val plaintext = "hello world".toByteArray(Charsets.UTF_8)
        val ciphertext = Rsa.encryptOaepSha1(plaintext, pubB64)

        val oaep = OAEPParameterSpec(
            "SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT,
        )
        val decrypter = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").apply {
            init(Cipher.DECRYPT_MODE, kp.private, oaep)
        }
        val decrypted = decrypter.doFinal(ciphertext)
        assertEquals("hello world", String(decrypted, Charsets.UTF_8))
    }

    @Test fun wrap_aes_key_with_real_pubKey_produces_344_byte_b64() {
        // 2048-bit RSA OAEP output is 256 bytes -> base64 length 344 (with two '=' padding).
        val aesKeyB64 = Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
        val pubKey = RealmeOtaConfig.serverParamsByRegion[
            com.desmond.ofd.backend.realmeota.data.Region.CN
        ]!!.pubKeyB64
        val wrapped = Rsa.wrapAesKey(aesKeyB64, pubKey)
        assertEquals(344, wrapped.length)
    }
}
