package com.desmond.ofd.backend.realmeota.network

import com.desmond.ofd.backend.realmeota.crypto.Aes
import com.desmond.ofd.backend.realmeota.data.OtaResponseDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Validates the HTTP envelope `{responseCode, errMsg, body|resps}` and decrypts the inner payload.
 */
class RealmeOtaDecoder(
    private val ruiVersion: Int,
    private val reqVersion: Int,
    private val storedAesKeyB64: String?,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** Returns null if `responseCode == 200` (or absent), else returns the failure message. */
    fun envelopeError(responseBody: String): EnvelopeError? {
        val obj = runCatching { json.parseToJsonElement(responseBody).jsonObject }.getOrNull()
            ?: return EnvelopeError(0, "Response is not valid JSON")
        val code = obj["responseCode"]?.jsonPrimitive?.intOrNull ?: 200
        if (code == 200) return null
        val msg = obj["errMsg"]?.jsonPrimitive?.contentOrNull
        return EnvelopeError(code, msg ?: "(no message)")
    }

    /** Decrypt + parse a successful response body. Throws on mismatched payload shape. */
    fun decode(responseBody: String): OtaResponseDto {
        val envelope = json.parseToJsonElement(responseBody).jsonObject
        val respKey = if (ruiVersion == 1) "resps" else "body"
        val payload = envelope[respKey]?.jsonPrimitive?.contentOrNull
            ?: error("Response missing '$respKey' field or value is null")

        val plain = when {
            reqVersion == 2 -> {
                val payloadObj = json.parseToJsonElement(payload).jsonObject
                val cipher = payloadObj["cipher"]?.jsonPrimitive?.content
                    ?: error("Response payload missing cipher field")
                val iv = payloadObj["iv"]?.jsonPrimitive?.content
                    ?: error("Response payload missing iv field")
                val key = storedAesKeyB64
                    ?: error("storedAesKeyB64 required for reqVersion=2 decode")
                Aes.decryptCtrV2(cipher, key, iv)
            }
            ruiVersion == 1 -> Aes.decryptEcb(payload)
            else -> Aes.decryptCtrV1(payload)
        }

        return json.decodeFromString(OtaResponseDto.serializer(), plain)
    }
}

data class EnvelopeError(val responseCode: Int, val errMsg: String)
