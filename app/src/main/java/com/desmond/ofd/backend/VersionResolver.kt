package com.desmond.ofd.backend

/**
 * Compares two OPPO/OnePlus firmware version names like
 *   `PLK110_16.0.7.206(CN01)`  vs  `PLK110_16.0.5.702(CN01)`.
 *
 *  - Drops everything after `(` (e.g. `(CN01)` / `(EX01)` / `(IN01)` regional tag).
 *  - Drops the leading `MODEL_` prefix (everything up to and including the first `_`).
 *  - Splits the remaining `16.0.7.206` on `.` and lex-compares as integers, with
 *    a longer tail winning ties (more granular ⇒ newer).
 *
 *  - Returns negative if `a < b`, positive if `a > b`, zero if equal.
 *  - Unparseable inputs sort *below* anything parseable; both unparseable → string compare.
 */
object VersionResolver {

    fun compare(a: String?, b: String?): Int {
        val partsA = a?.let(::parse)
        val partsB = b?.let(::parse)
        return when {
            partsA == null && partsB == null -> compareValues(a, b)
            partsA == null -> -1
            partsB == null -> 1
            else -> compareLists(partsA, partsB)
        }
    }

    /** Returns the higher of `a`/`b`, or whichever is non-null if one's missing. */
    fun pickNewer(a: String?, b: String?): String? = when {
        a == null -> b
        b == null -> a
        compare(a, b) >= 0 -> a
        else -> b
    }

    /** Strip prefix + suffix and parse the numeric core into an `IntArray`. */
    internal fun parse(versionName: String): IntArray? {
        // "PLK110_16.0.7.206(CN01)" → "16.0.7.206"
        val core = versionName
            .substringBefore('(')
            .substringAfter('_', missingDelimiterValue = "")
            .trim()
        if (core.isEmpty()) return null
        val parts = core.split('.').map { it.toIntOrNull() ?: return null }
        return parts.toIntArray()
    }

    private fun compareLists(a: IntArray, b: IntArray): Int {
        val maxLen = maxOf(a.size, b.size)
        for (i in 0 until maxLen) {
            val pa = a.getOrElse(i) { 0 }
            val pb = b.getOrElse(i) { 0 }
            if (pa != pb) return pa.compareTo(pb)
        }
        // All compared parts equal: prefer the version with more granular components.
        return a.size.compareTo(b.size)
    }
}
