package com.desmond.ofd.http

internal data class ContentRange(
    val start: Long,
    val end: Long,
    val totalSize: Long,
) {
    val length: Long get() = end - start + 1
}

internal fun parseContentRange(value: String?): ContentRange? {
    if (value.isNullOrBlank()) return null
    val match = CONTENT_RANGE_RE.matchEntire(value.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    if (start < 0 || end < start || total <= 0 || end >= total) return null
    return ContentRange(start, end, total)
}

private val CONTENT_RANGE_RE = Regex("""bytes(?:\s+|=)(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)
