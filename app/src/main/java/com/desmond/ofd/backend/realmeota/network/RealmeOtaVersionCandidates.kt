package com.desmond.ofd.backend.realmeota.network

internal object RealmeOtaVersionCandidates {
    fun versions(model: String, otaVersion: String): List<String> {
        val result = linkedSetOf<String>()
        val userInput = otaVersion.trim()
        if (userInput.isNotBlank()) {
            result += userInput
        }

        baselineVersion(userInput)?.let { result += it }

        if (result.none(::isBaselineVersion)) {
            val branchFromInput = branchOf(userInput)
            val branches = fallbackBranches(model, branchFromInput)
            for (branch in branches) {
                result += "${model}_11.$branch.00_0001_100000000000"
            }
        }

        return result.toList()
    }

    private fun baselineVersion(otaVersion: String): String? {
        val match = BASELINE_OTA_PREFIX.find(otaVersion) ?: return null
        return "${match.groupValues[1]}.00_0001_100000000000"
    }

    private fun branchOf(otaVersion: String): String? =
        OTA_BRANCH.find(otaVersion)?.groupValues?.get(1)

    private fun fallbackBranches(model: String, preferred: String?): List<String> {
        val defaults = if (model.startsWith("RMX")) {
            listOf("F", "A", "C", "B")
        } else {
            listOf("A", "C", "F", "B")
        }
        return listOfNotNull(preferred) + defaults.filterNot { it == preferred }
    }

    private fun isBaselineVersion(otaVersion: String): Boolean =
        otaVersion.endsWith(".00_0001_100000000000")

    private val BASELINE_OTA_PREFIX = Regex("""^(.+_11\.[^.]+)\..+$""")
    private val OTA_BRANCH = Regex("""(?:^|_)11\.([A-Z])(?:\.|$)""")
}
