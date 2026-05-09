package com.desmond.ofd.backend.danielspringer

/** Outcome of a successful live URL fetch from danielspringer.at. */
data class DanielspringerResult(
    /** Resolved download URL — could be OPPO's S3 (`*.allawnfs.com`) or Google's OTA CDN. */
    val downloadUrl: String,
    /** Total file size in bytes (from `Content-Length`/`Content-Range`); -1 when unknown. */
    val sizeBytes: Long,
    /** Optional file MD5 (from `x-amz-meta-filemd5`); danielspringer's S3 sets it. */
    val md5: String?,
    /** Display name from the catalog at this `version_index` slot, e.g. `PLK110_16.0.7.206(CN01)`. */
    val displayName: String,
    /** Full OTA version chip from the result HTML (e.g. `CPH2655_11.C.65_1651_202507071509`); null if absent. */
    val realOtaVersion: String? = null,
    /** Security patch chip (e.g. `2025-07-01`); null if absent. */
    val securityPatch: String? = null,
    /** True when the site flagged the URL as "manual-only" (browser download blocked). */
    val manualOnly: Boolean = false,
    /** Epoch seconds — parsed from the URL's `Expires=...` query param, 0 if not present. */
    val expiresAtEpochSeconds: Long = 0L,
)
