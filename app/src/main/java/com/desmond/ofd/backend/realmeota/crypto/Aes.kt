package com.desmond.ofd.backend.realmeota.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES variants used by `realme-ota`'s three crypto paths.
 *
 *  - [encryptEcb] / [decryptEcb] — AES/ECB/PKCS7 (RUI 1, req-version 1).
 *    Output = base64(cipher) + 15-char pseudo-key suffix.
 *  - [encryptCtrV1] / [decryptCtrV1] — AES/CTR/NoPadding (RUI ≥ 2, req-version 1).
 *    IV is deterministic = MD5(derivedKey). Output format same as ECB.
 *  - [encryptCtrV2] / [decryptCtrV2] — AES/CTR/NoPadding with truly random 32-byte key + 16-byte IV
 *    (RUI ≥ 2, req-version 2). Cipher, key, IV are returned/consumed as separate base64 strings.
 */
object Aes {

    private val b64Encoder: Base64.Encoder = Base64.getEncoder()
    private val b64Decoder: Base64.Decoder = Base64.getDecoder()

    // ---- ECB (RUI 1) ----

    fun encryptEcb(plaintext: String): String =
        encryptEcb(plaintext, KeyDerivation.randomEcbPseudoKey())

    fun encryptEcb(plaintext: String, pseudoKey: String): String {
        val key = KeyDerivation.derive(pseudoKey)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        val out = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return b64Encoder.encodeToString(out) + pseudoKey
    }

    fun decryptEcb(payload: String): String {
        require(payload.length > KeyDerivation.PSEUDO_KEY_LEN) { "payload too short for ECB" }
        val cipherB64 = payload.dropLast(KeyDerivation.PSEUDO_KEY_LEN)
        val pseudoKey = payload.takeLast(KeyDerivation.PSEUDO_KEY_LEN)
        val key = KeyDerivation.derive(pseudoKey)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        val plain = cipher.doFinal(b64Decoder.decode(cipherB64))
        return String(plain, Charsets.UTF_8)
    }

    // ---- CTR v1 (RUI ≥ 2 with req_version=1) ----

    fun encryptCtrV1(plaintext: String): String =
        encryptCtrV1(plaintext, KeyDerivation.randomCtrPseudoKey())

    fun encryptCtrV1(plaintext: String, pseudoKey: String): String {
        val key = KeyDerivation.derive(pseudoKey)
        val iv = Hash.md5(key)
        val out = aesCtr(Cipher.ENCRYPT_MODE, plaintext.toByteArray(Charsets.UTF_8), key, iv)
        return b64Encoder.encodeToString(out) + pseudoKey
    }

    fun decryptCtrV1(payload: String): String {
        require(payload.length > KeyDerivation.PSEUDO_KEY_LEN) { "payload too short for CTR v1" }
        val cipherB64 = payload.dropLast(KeyDerivation.PSEUDO_KEY_LEN)
        val pseudoKey = payload.takeLast(KeyDerivation.PSEUDO_KEY_LEN)
        val key = KeyDerivation.derive(pseudoKey)
        val iv = Hash.md5(key)
        val plain = aesCtr(Cipher.DECRYPT_MODE, b64Decoder.decode(cipherB64), key, iv)
        return String(plain, Charsets.UTF_8)
    }

    // ---- CTR v2 (RUI ≥ 2 with req_version=2) ----

    data class CtrV2Result(val cipherB64: String, val keyB64: String, val ivB64: String)

    fun encryptCtrV2(plaintext: String, random: SecureRandom = SecureRandom()): CtrV2Result {
        val key = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(16).also(random::nextBytes)
        val cipher = aesCtr(Cipher.ENCRYPT_MODE, plaintext.toByteArray(Charsets.UTF_8), key, iv)
        return CtrV2Result(
            cipherB64 = b64Encoder.encodeToString(cipher),
            keyB64 = b64Encoder.encodeToString(key),
            ivB64 = b64Encoder.encodeToString(iv),
        )
    }

    fun decryptCtrV2(cipherB64: String, keyB64: String, ivB64: String): String {
        val plain = aesCtr(
            mode = Cipher.DECRYPT_MODE,
            data = b64Decoder.decode(cipherB64),
            key = b64Decoder.decode(keyB64),
            iv = b64Decoder.decode(ivB64),
        )
        return String(plain, Charsets.UTF_8)
    }

    private fun aesCtr(mode: Int, data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
        return cipher.doFinal(data)
    }
}
