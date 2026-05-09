package com.desmond.ofd.backend.realmeota.network

import com.desmond.ofd.backend.realmeota.crypto.Aes
import com.desmond.ofd.backend.realmeota.crypto.Hash
import com.desmond.ofd.backend.realmeota.crypto.Rsa
import com.desmond.ofd.backend.realmeota.data.OtaRequestParams
import com.desmond.ofd.backend.realmeota.data.RealmeOtaConfig
import com.desmond.ofd.backend.realmeota.data.Region
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** All artefacts needed to fire one HTTP request and decode its response. */
data class BuiltRequest(
    val url: String,
    val bodyJson: String,
    val headers: Map<String, String>,
    val plainBodyJson: String,
    val storedAesKeyB64: String?,
)

/**
 * Builds the encrypted POST body and headers for an OPPO `update/v3` query. Mirrors
 * `realme-ota`'s `Request.set_vars` + `set_body_headers` (`request.py:88–178`).
 */
object RealmeOtaRequest {

    private val json = Json {
        encodeDefaults = true
        prettyPrint = false
    }

    fun build(params: OtaRequestParams, nowMs: Long = System.currentTimeMillis()): BuiltRequest {
        require(params.ruiVersion in 1..7) { "ruiVersion must be 1..7, got ${params.ruiVersion}" }
        val derived = derive(params, nowMs)
        val plainBody = buildBody(derived)
        val plainBodyJson = json.encodeToString(JsonObject.serializer(), plainBody)
        val headers = buildHeaders(derived).toMutableMap()
        val url = pickUrl(params)

        return when (params.reqVersion) {
            2 -> buildV2(plainBodyJson, headers, url, params.region, nowMs)
            1 -> buildV1(plainBodyJson, headers, url, params.ruiVersion)
            else -> error("Unsupported reqVersion: ${params.reqVersion}")
        }
    }

    private fun buildV2(
        plainBodyJson: String,
        headers: MutableMap<String, String>,
        url: String,
        region: Region,
        nowMs: Long,
    ): BuiltRequest {
        val ctr = Aes.encryptCtrV2(plainBodyJson)
        val cipherIvObj = buildJsonObject {
            put("cipher", ctr.cipherB64)
            put("iv", ctr.ivB64)
        }
        val cipherIvString = json.encodeToString(JsonObject.serializer(), cipherIvObj)
        val outerObj = buildJsonObject { put("params", cipherIvString) }
        val outerBodyJson = json.encodeToString(JsonObject.serializer(), outerObj)

        val serverParams = RealmeOtaConfig.serverParamsByRegion.getValue(region.endpointRegion)
        val protectedKey = Rsa.wrapAesKey(ctr.keyB64, serverParams.pubKeyB64)
        // 1 day in the future, mirroring Python's (time + 86400 * 1000) for the version field.
        val versionTs = (nowMs + 86_400_000L).toString()
        val protectedKeyObj = buildJsonObject {
            putJsonObject("SCENE_1") {
                put("protectedKey", protectedKey)
                put("version", versionTs)
                put("negotiationVersion", serverParams.negotiationVersion)
            }
        }
        headers["version"] = "2"
        headers["protectedKey"] = json.encodeToString(JsonObject.serializer(), protectedKeyObj)

        return BuiltRequest(
            url = url,
            bodyJson = outerBodyJson,
            headers = headers,
            plainBodyJson = plainBodyJson,
            storedAesKeyB64 = ctr.keyB64,
        )
    }

    private fun buildV1(
        plainBodyJson: String,
        headers: MutableMap<String, String>,
        url: String,
        ruiVersion: Int,
    ): BuiltRequest {
        val cipher = if (ruiVersion == 1) Aes.encryptEcb(plainBodyJson)
        else Aes.encryptCtrV1(plainBodyJson)
        val outerObj = buildJsonObject { put("params", cipher) }
        val outerBodyJson = json.encodeToString(JsonObject.serializer(), outerObj)
        return BuiltRequest(
            url = url,
            bodyJson = outerBodyJson,
            headers = headers,
            plainBodyJson = plainBodyJson,
            storedAesKeyB64 = null,
        )
    }

    private fun pickUrl(params: OtaRequestParams): String {
        if (params.model in RealmeOtaConfig.ONEPLUS_MODEL_TOKENS) {
            return RealmeOtaConfig.ONEPLUS_URL
        }
        // OPPO's endpoint set has no separate NA — fold NA → GL for routing.
        val endpointRegion = params.region.endpointRegion
        return when {
            params.ruiVersion >= 2 && params.reqVersion == 2 ->
                RealmeOtaConfig.serverParamsByRegion.getValue(endpointRegion).serverURL
            params.ruiVersion >= 2 ->
                RealmeOtaConfig.urlsV2Old.getValue(endpointRegion)
            else ->
                RealmeOtaConfig.urlsV1.getValue(endpointRegion)
        }
    }

    private fun derive(params: OtaRequestParams, nowMs: Long): DerivedProps {
        // Use endpointRegion so NA-region devices send "GL" to OPPO (their endpoint
        // doesn't recognize NA as a region tag).
        val endpointRegion = params.region.endpointRegion
        val androidVersion = "Android${10 + params.ruiVersion - 1}.0"
        val colorOSVersion =
            if (params.ruiVersion == 1) "ColorOS7"
            else "ColorOS${11 + params.ruiVersion - 2}"
        val nvCarrier = params.nvIdentifier?.takeIf { it != "0" }
            ?: when (endpointRegion) {
                Region.CN -> "10010111"
                Region.EU -> "01000100"
                else -> "00011011"
            }
        val isRealme = if ("RMX" in params.model) "1" else "0"
        val otaPrefix = params.otaVersion.split("_").take(2).joinToString("_")
        val language = params.language ?: if (endpointRegion == Region.CN) "zh-CN" else "en-EN"
        val deviceIdSource = params.deviceId ?: params.imei0 ?: DEFAULT_IMEI
        val deviceId = Hash.sha256Upper(deviceIdSource)
        return DerivedProps(
            model = params.model,
            otaVersion = params.otaVersion,
            ruiVersion = params.ruiVersion,
            androidVersion = androidVersion,
            colorOSVersion = colorOSVersion,
            nvCarrier = nvCarrier,
            isRealme = isRealme,
            otaPrefix = otaPrefix,
            language = language,
            trackRegion = endpointRegion.label,
            deviceId = deviceId,
            imei = params.imei0 ?: DEFAULT_IMEI,
            imei1 = params.imei1 ?: DEFAULT_IMEI,
            time = nowMs.toString(),
            beta = params.beta,
        )
    }

    private fun buildBody(d: DerivedProps): JsonObject = buildJsonObject {
        put("language", d.language)
        put("romVersion", d.otaPrefix)
        put("otaVersion", d.otaVersion)
        put("androidVersion", d.androidVersion)
        put("colorOSVersion", d.colorOSVersion)
        put("model", d.model)
        put("productName", d.model)
        put("operator", "unknown")
        put("uRegion", d.trackRegion)
        put("trackRegion", d.trackRegion)
        put("imei", d.imei)
        put("imei1", d.imei1)
        put("mode", if (d.beta) "1" else "0")
        put("registrationId", "unknown")
        put("deviceId", d.deviceId)
        put("version", "3")
        put("type", "1")
        put("otaPrefix", d.otaPrefix)
        put("isRealme", d.isRealme)
        put("time", d.time)
        put("canCheckSelf", "0")
    }

    private fun buildHeaders(d: DerivedProps): Map<String, String> = linkedMapOf(
        "language" to d.language,
        "romVersion" to d.otaPrefix,
        "otaVersion" to d.otaVersion,
        "androidVersion" to d.androidVersion,
        "colorOSVersion" to d.colorOSVersion,
        "model" to d.model,
        "infVersion" to "1",
        "operator" to "unknown",
        "nvCarrier" to d.nvCarrier,
        "uRegion" to d.trackRegion,
        "trackRegion" to d.trackRegion,
        "imei" to d.imei,
        "imei1" to d.imei1,
        "deviceId" to d.deviceId,
        "mode" to "client_auto",
        "channel" to "pc",
        "version" to "1",
        "Accept" to "application/json",
        "Content-Type" to "application/json",
        "User-Agent" to "NULL",
    )

    private const val DEFAULT_IMEI = "000000000000000"

    private data class DerivedProps(
        val model: String,
        val otaVersion: String,
        val ruiVersion: Int,
        val androidVersion: String,
        val colorOSVersion: String,
        val nvCarrier: String,
        val isRealme: String,
        val otaPrefix: String,
        val language: String,
        val trackRegion: String,
        val deviceId: String,
        val imei: String,
        val imei1: String,
        val time: String,
        val beta: Boolean,
    )
}
