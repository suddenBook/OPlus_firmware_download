package com.desmond.ofd.download

import android.net.Uri

/** Inputs for one download. Created when the user picks a save location via SAF. */
data class DownloadParams(
    val url: String,
    val targetUri: Uri,
    val displayName: String,
    val expectedSize: Long,
    val expectedMd5: String?,
)
