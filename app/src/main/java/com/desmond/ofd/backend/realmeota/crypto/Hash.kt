package com.desmond.ofd.backend.realmeota.crypto

import java.security.MessageDigest

object Hash {
    /** SHA-256, returned as **uppercase** hex — matches `realme-ota`'s `crypto.sha256()`. */
    fun sha256Upper(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .toHexUpper()

    /** Raw MD5 bytes (16 B). Used to derive AES-CTR-v1 IVs from a derived key. */
    fun md5(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(bytes)

    private fun ByteArray.toHexUpper(): String =
        joinToString("") { "%02X".format(it) }
}
