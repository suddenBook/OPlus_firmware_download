package com.desmond.ofd.backend.realmeota.crypto

import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

/**
 * RSA-OAEP wrapping of the AES-CTR-v2 key for the `protectedKey` HTTP header (req-version 2 only).
 *
 * Critical detail: PyCryptodome's `PKCS1_OAEP.new(key)` defaults to **SHA-1** for both the OAEP
 * digest *and* the MGF1 hash. The JCE name `OAEPWithSHA-1AndMGF1Padding` only forces the OAEP
 * digest; some providers default MGF1 to SHA-256. We pass an explicit [OAEPParameterSpec] so
 * MGF1=SHA-1 is locked in and we stay byte-compatible with the Python output.
 */
object Rsa {

    private val b64 = Base64.getDecoder()

    fun encryptOaepSha1(plaintext: ByteArray, pubKeyB64: String): ByteArray {
        val der = b64.decode(pubKeyB64)
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        val oaep = OAEPParameterSpec(
            "SHA-1",
            "MGF1",
            MGF1ParameterSpec.SHA1,
            PSource.PSpecified.DEFAULT,
        )
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").apply {
            init(Cipher.ENCRYPT_MODE, publicKey, oaep)
        }
        return cipher.doFinal(plaintext)
    }

    /** Build the `protectedKey` header value: base64(RSA-OAEP-SHA1(aesKeyB64 UTF-8 bytes)). */
    fun wrapAesKey(aesKeyB64: String, pubKeyB64: String): String {
        val ciphertext = encryptOaepSha1(aesKeyB64.toByteArray(Charsets.UTF_8), pubKeyB64)
        return Base64.getEncoder().encodeToString(ciphertext)
    }
}
