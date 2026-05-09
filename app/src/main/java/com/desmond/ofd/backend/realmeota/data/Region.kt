package com.desmond.ofd.backend.realmeota.data

/**
 * Sale region. Note that NA shares the OPPO update endpoint with GL (`code = 0`) — but
 * danielspringer.at exposes NA as a distinct site-region, so we keep them separate here.
 * Use [endpointRegion] when routing to OPPO's `update/v3` endpoint.
 */
enum class Region(val code: Int, val label: String) {
    GL(0, "GL"),
    CN(1, "CN"),
    IN(2, "IN"),
    EU(3, "EU"),
    NA(0, "NA");

    /** Region used for OPPO endpoint routing. NA folds to GL; everyone else maps to itself. */
    val endpointRegion: Region get() = if (this == NA) GL else this

    companion object {
        fun fromCode(code: Int): Region = entries.firstOrNull { it.code == code } ?: GL
    }
}
