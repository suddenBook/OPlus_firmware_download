package com.desmond.ofd.backend.realmeota.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Decrypted server response. Many fields are optional; mark all nullable for safety. */
@Serializable
data class OtaResponseDto(
    val realOtaVersion: String? = null,
    val versionName: String? = null,
    val realOsVersion: String? = null,
    val osVersion: String? = null,
    val androidVersion: String? = null,
    val realAndroidVersion: String? = null,
    val securityPatch: String? = null,
    val securityPatchVendor: String? = null,
    val components: List<Component> = emptyList(),
    val componentAssembleType: Boolean? = null,
    val panelUrl: String? = null,
    val isRecruit: Boolean? = null,
    val checkFailReason: String? = null,
    val publishedTime: Long? = null,
    val rid: String? = null,
    val versionCode: Int? = null,
    val nvId16: String? = null,
    val aid: String? = null,
)

@Serializable
data class Component(
    val componentName: String,
    val componentVersion: String? = null,
    val componentId: String? = null,
    val componentPackets: ComponentPacket,
)

@Serializable
data class ComponentPacket(
    val url: String,
    val manualUrl: String? = null,
    val md5: String,
    val size: String,
    val type: String? = null,
    val id: String? = null,
    val vabInfo: JsonElement? = null,
)
