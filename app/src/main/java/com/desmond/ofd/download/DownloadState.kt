package com.desmond.ofd.download

sealed interface DownloadState {
    data object Idle : DownloadState

    data class Active(
        val params: DownloadParams,
        val bytesDownloaded: Long,
        val totalBytes: Long,
        /** Bytes per second over the last sample window. */
        val speedBps: Long,
    ) : DownloadState {
        val progress: Float
            get() = if (totalBytes > 0) (bytesDownloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
        val etaSeconds: Long
            get() = if (speedBps > 0 && totalBytes > 0) (totalBytes - bytesDownloaded) / speedBps else 0L
    }

    data class Verifying(val params: DownloadParams) : DownloadState

    data class Completed(
        val params: DownloadParams,
        /** True/false when an expected md5 was supplied; null when no check ran. */
        val md5Matches: Boolean?,
    ) : DownloadState

    data class Failed(val params: DownloadParams, val error: String) : DownloadState
}
