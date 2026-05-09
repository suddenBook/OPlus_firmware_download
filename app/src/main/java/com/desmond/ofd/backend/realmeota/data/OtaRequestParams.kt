package com.desmond.ofd.backend.realmeota.data

/**
 * All inputs needed to build a `realme-ota` request. Mirrors the CLI argument set
 * of the Python tool.
 *
 * `reqVersion` defaults to 2 when RUI ≥ 2 (the modern protocol with RSA-OAEP wrapped
 * AES-CTR keys); pass 1 to force the legacy AES-ECB path.
 */
data class OtaRequestParams(
    val model: String,
    val otaVersion: String,
    val ruiVersion: Int,
    val nvIdentifier: String? = null,
    val region: Region = Region.GL,
    val deviceId: String? = null,
    val imei0: String? = null,
    val imei1: String? = null,
    val beta: Boolean = false,
    val language: String? = null,
    val reqVersion: Int = if (ruiVersion >= 2) 2 else 1,
)
