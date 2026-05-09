package com.desmond.ofd.backend.realmeota.crypto

import java.security.SecureRandom

/**
 * Mirrors `realme-ota`'s `crypto.getKey()` and pseudo-key generators in `crypto.py:30–34, 78–79, 94–95`.
 *
 * The "real" 16-byte AES key for the legacy ECB / CTR-v1 paths is derived deterministically
 * from a 15-character pseudo-key:
 *   real_key = keysTable[int(pseudo[0])] + pseudo[4..11]   // 8 + 8 = 16 bytes UTF-8
 * The pseudo-key is appended verbatim as a suffix to the base64 ciphertext, so the server
 * can reconstruct the same real key during decryption.
 *
 * AES-CTR-v1 pseudo-keys are 1 random digit + 14 random digits.
 * AES-ECB pseudo-keys are 1 random digit + 14 random alphanumerics.
 */
object KeyDerivation {
    /** Hardcoded 10-element table from `crypto.py:30–31`. Do not change — server has the same table. */
    val keysTable: List<String> = listOf(
        "oppo1997", "baed2017", "java7865", "231uiedn", "09e32ji6",
        "0oiu3jdy", "0pej387l", "2dkliuyt", "20odiuye", "87j3id7w",
    )

    private const val DIGITS = "0123456789"
    private const val ALPHANUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** Pseudo-key length expected by [derive]: 1 + 14 = 15 chars. */
    const val PSEUDO_KEY_LEN: Int = 15

    fun derive(pseudoKey: String): ByteArray {
        require(pseudoKey.length >= 12) { "pseudoKey must be >= 12 chars, got ${pseudoKey.length}" }
        val firstDigit = pseudoKey[0].digitToInt()
        val mixed = pseudoKey.substring(4, 12)
        return (keysTable[firstDigit] + mixed).toByteArray(Charsets.UTF_8)
    }

    fun randomCtrPseudoKey(random: SecureRandom = SecureRandom()): String =
        randomString(random, DIGITS, 1) + randomString(random, DIGITS, 14)

    fun randomEcbPseudoKey(random: SecureRandom = SecureRandom()): String =
        randomString(random, DIGITS, 1) + randomString(random, ALPHANUM, 14)

    private fun randomString(random: SecureRandom, alphabet: String, length: Int): String =
        buildString(length) {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
}
