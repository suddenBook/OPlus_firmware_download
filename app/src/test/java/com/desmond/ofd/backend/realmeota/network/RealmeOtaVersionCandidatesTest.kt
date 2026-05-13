package com.desmond.ofd.backend.realmeota.network

import org.junit.Assert.assertEquals
import org.junit.Test

class RealmeOtaVersionCandidatesTest {

    @Test fun tries_user_input_then_same_branch_baseline() {
        assertEquals(
            listOf(
                "PLK110_11.A.63_0630_202605061316",
                "PLK110_11.A.00_0001_100000000000",
            ),
            RealmeOtaVersionCandidates.versions(
                model = "PLK110",
                otaVersion = "PLK110_11.A.63_0630_202605061316",
            ),
        )
    }

    @Test fun blank_oneplus_input_uses_common_baselines() {
        assertEquals(
            listOf(
                "PLK110_11.A.00_0001_100000000000",
                "PLK110_11.C.00_0001_100000000000",
                "PLK110_11.F.00_0001_100000000000",
                "PLK110_11.B.00_0001_100000000000",
            ),
            RealmeOtaVersionCandidates.versions(model = "PLK110", otaVersion = ""),
        )
    }

    @Test fun blank_realme_input_prefers_f_branch() {
        assertEquals(
            listOf(
                "RMX3630_11.F.00_0001_100000000000",
                "RMX3630_11.A.00_0001_100000000000",
                "RMX3630_11.C.00_0001_100000000000",
                "RMX3630_11.B.00_0001_100000000000",
            ),
            RealmeOtaVersionCandidates.versions(model = "RMX3630", otaVersion = ""),
        )
    }

    @Test fun invalid_input_is_kept_first_then_baselines_are_tried() {
        assertEquals(
            listOf(
                "nonsense",
                "RMX3630_11.F.00_0001_100000000000",
                "RMX3630_11.A.00_0001_100000000000",
                "RMX3630_11.C.00_0001_100000000000",
                "RMX3630_11.B.00_0001_100000000000",
            ),
            RealmeOtaVersionCandidates.versions(model = "RMX3630", otaVersion = "nonsense"),
        )
    }

    @Test fun partial_branch_input_prioritizes_that_branch() {
        assertEquals(
            listOf(
                "RMX3630_11.C",
                "RMX3630_11.C.00_0001_100000000000",
                "RMX3630_11.F.00_0001_100000000000",
                "RMX3630_11.A.00_0001_100000000000",
                "RMX3630_11.B.00_0001_100000000000",
            ),
            RealmeOtaVersionCandidates.versions(model = "RMX3630", otaVersion = "RMX3630_11.C"),
        )
    }
}
