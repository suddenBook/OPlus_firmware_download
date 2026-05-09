package com.desmond.ofd.device

/** Brand grouping used by the picker / catalog. */
enum class Brand(val display: String) {
    OnePlus("OnePlus"),
    OPPO("OPPO"),
    Realme("realme"),
    Other("Other");

    companion object {
        /** Map a `Build.BRAND` / `ro.product.brand` string to a [Brand]. */
        fun fromBuildBrand(raw: String?): Brand = when (raw?.lowercase()) {
            "oneplus" -> OnePlus
            "oppo" -> OPPO
            "realme" -> Realme
            else -> Other
        }

        /** Best-effort detection from a marketing-name string. */
        fun detect(marketingName: String): Brand = when {
            marketingName.startsWith("OnePlus", ignoreCase = true) -> OnePlus
            marketingName.startsWith("OPPO", ignoreCase = true) -> OPPO
            marketingName.startsWith("realme", ignoreCase = true) -> Realme
            else -> Other
        }
    }
}
