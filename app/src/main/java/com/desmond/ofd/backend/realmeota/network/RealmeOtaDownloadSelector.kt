package com.desmond.ofd.backend.realmeota.network

import com.desmond.ofd.backend.realmeota.data.OtaResponseDto
import com.desmond.ofd.firmware.validateFirmwareSize

internal object RealmeOtaDownloadSelector {
    fun select(response: OtaResponseDto): RealmeOtaDownloadSelection {
        val packet = response.components.firstOrNull()?.componentPackets
            ?: return RealmeOtaDownloadSelection.Failure(RealmeOtaDownloadFailure.NO_DOWNLOAD_PACKET)

        val downloadUrls = listOfNotNull(
            packet.manualUrl?.takeIf { it.isNotBlank() },
            packet.url.takeIf { it.isNotBlank() },
        ).distinct()
        if (downloadUrls.isEmpty()) {
            return RealmeOtaDownloadSelection.Failure(RealmeOtaDownloadFailure.NO_DOWNLOAD_URL)
        }

        val sizeBytes = packet.size.toLongOrNull()?.takeIf { it > 0 }
            ?: return RealmeOtaDownloadSelection.Failure(
                reason = RealmeOtaDownloadFailure.INVALID_FIRMWARE_SIZE,
                observedSize = -1L,
            )
        validateFirmwareSize(sizeBytes, expectedSize = -1L)?.let {
            return RealmeOtaDownloadSelection.Failure(
                reason = RealmeOtaDownloadFailure.INVALID_FIRMWARE_SIZE,
                observedSize = sizeBytes,
            )
        }

        return RealmeOtaDownloadSelection.Success(
            versionName = response.versionName ?: response.realOtaVersion ?: "(unknown)",
            realOtaVersion = response.realOtaVersion,
            downloadUrls = downloadUrls,
            sizeBytes = sizeBytes,
            md5 = packet.md5.takeIf { it.isNotBlank() },
            securityPatch = response.securityPatch,
        )
    }
}

internal sealed interface RealmeOtaDownloadSelection {
    data class Success(
        val versionName: String,
        val realOtaVersion: String?,
        val downloadUrls: List<String>,
        val sizeBytes: Long,
        val md5: String?,
        val securityPatch: String?,
    ) : RealmeOtaDownloadSelection

    data class Failure(
        val reason: RealmeOtaDownloadFailure,
        val observedSize: Long? = null,
    ) : RealmeOtaDownloadSelection
}

internal enum class RealmeOtaDownloadFailure {
    NO_DOWNLOAD_PACKET,
    NO_DOWNLOAD_URL,
    INVALID_FIRMWARE_SIZE,
}
