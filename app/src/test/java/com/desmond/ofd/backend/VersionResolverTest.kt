package com.desmond.ofd.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionResolverTest {

    @Test fun newer_is_greater() {
        assertTrue(VersionResolver.compare(
            "PLK110_16.0.7.206(CN01)",
            "PLK110_16.0.5.702(CN01)",
        ) > 0)
    }

    @Test fun older_is_less() {
        assertTrue(VersionResolver.compare(
            "PLK110_16.0.5.701(CN01)",
            "PLK110_16.0.5.702(CN01)",
        ) < 0)
    }

    @Test fun equal_versions_match() {
        assertEquals(0, VersionResolver.compare(
            "PLK110_16.0.7.206(CN01)",
            "PLK110_16.0.7.206(CN01)",
        ))
    }

    @Test fun different_regional_tags_dont_break_compare() {
        // EX01 and CN01 should compare on the numeric body alone.
        assertEquals(0, VersionResolver.compare(
            "CPH2747_16.0.5.703(EX01)",
            "PLK110_16.0.5.703(CN01)",
        ))
    }

    @Test fun longer_tail_wins_when_prefix_equal() {
        // 16.0.5 == 16.0.5.0, so 16.0.5.1 > 16.0.5.
        assertTrue(VersionResolver.compare(
            "PLK110_16.0.5.1(CN01)",
            "PLK110_16.0.5(CN01)",
        ) > 0)
    }

    @Test fun pickNewer_returns_higher() {
        assertEquals(
            "PLK110_16.0.7.206(CN01)",
            VersionResolver.pickNewer(
                "PLK110_16.0.5.702(CN01)",
                "PLK110_16.0.7.206(CN01)",
            ),
        )
    }

    @Test fun pickNewer_handles_one_null() {
        val v = "PLK110_16.0.7.206(CN01)"
        assertEquals(v, VersionResolver.pickNewer(null, v))
        assertEquals(v, VersionResolver.pickNewer(v, null))
    }

    @Test fun pickNewer_both_null() {
        assertEquals(null, VersionResolver.pickNewer(null, null))
    }

    @Test fun unparseable_sorts_below_parseable() {
        assertTrue(VersionResolver.compare("garbage", "PLK110_16.0.7.206(CN01)") < 0)
        assertTrue(VersionResolver.compare("PLK110_16.0.7.206(CN01)", "garbage") > 0)
    }

    @Test fun unparseable_vs_unparseable_falls_back_to_string_compare() {
        assertTrue(VersionResolver.compare("aaa", "bbb") < 0)
        assertEquals(0, VersionResolver.compare("xxx", "xxx"))
    }

    @Test fun parse_drops_prefix_and_suffix() {
        assertEquals(
            listOf(16, 0, 7, 206),
            VersionResolver.parse("PLK110_16.0.7.206(CN01)")?.toList(),
        )
    }

    @Test fun parse_returns_null_on_non_numeric_segments() {
        assertEquals(null, VersionResolver.parse("PLK110_alpha.beta(CN01)"))
    }

    @Test fun major_minor_compare_dominates_patch() {
        // 17.x > 16.x regardless of the rest.
        assertTrue(VersionResolver.compare(
            "PLK110_17.0.0.1(CN01)",
            "PLK110_16.0.7.206(CN01)",
        ) > 0)
    }
}
