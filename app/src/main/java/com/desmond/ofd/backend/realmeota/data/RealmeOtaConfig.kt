package com.desmond.ofd.backend.realmeota.data

/**
 * Static OPPO update endpoint configuration, ported from realme-ota's `data.py`.
 *
 * - [serverParamsByRegion] — req-version 2 (modern, RSA-OAEP-wrapped) endpoints.
 * - [urlsV1] — RUI 1 / req-version 1 (legacy AES-ECB) endpoints.
 * - [urlsV2Old] — RUI ≥ 2 / req-version 1 (legacy AES-CTR) endpoints.
 * - [ONEPLUS_URL] — special endpoint when `model in {OnePlus, oneplus, Oneplus}`.
 * - [ZERO_OTA_SUFFIX] — fallback suffix retried once if the first request is rejected.
 */
object RealmeOtaConfig {
    data class ServerParams(
        val serverURL: String,
        val pubKeyB64: String,
        val negotiationVersion: String,
    )

    val serverParamsByRegion: Map<Region, ServerParams> = mapOf(
        Region.GL to ServerParams(
            serverURL = "https://component-otapc-sg.allawnos.com/update/v3",
            pubKeyB64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAkA980wxi+eTGcFDiw2I6" +
                    "RrUeO4jL/Aj3Yw4dNuW7tYt+O1sRTHgrzxPD9SrOqzz7G0KgoSfdFHe3JVLPN+U1" +
                    "waK+T0HfLusVJshDaMrMiQFDUiKajb+QKr+bXQhVofH74fjat+oRJ8vjXARSpFk4" +
                    "/41x5j1Bt/2bHoqtdGPcUizZ4whMwzap+hzVlZgs7BNfepo24PWPRujsN3uopl+8" +
                    "u4HFpQDlQl7GdqDYDj2zNOHdFQI2UpSf0aIeKCKOpSKF72KDEESpJVQsqO4nxMwE" +
                    "i2jMujQeCHyTCjBZ+W35RzwT9+0pyZv8FB3c7FYY9FdF/+lvfax5mvFEBd9jO+dp" +
                    "MQIDAQAB",
            negotiationVersion = "1615895993238",
        ),
        Region.CN to ServerParams(
            serverURL = "https://component-otapc-cn.allawntech.com/update/v3",
            pubKeyB64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApXYGXQpNL7gmMzzvajHa" +
                    "oZIHQQvBc2cOEhJc7/tsaO4sT0unoQnwQKfNQCuv7qC1Nu32eCLuewe9LSYhDXr9" +
                    "KSBWjOcCFXVXteLO9WCaAh5hwnUoP/5/Wz0jJwBA+yqs3AaGLA9wJ0+B2lB1vLE4" +
                    "FZNE7exUfwUc03fJxHG9nCLKjIZlrnAAHjRCd8mpnADwfkCEIPIGhnwq7pdkbamZ" +
                    "coZfZud1+fPsELviB9u447C6bKnTU4AaMcR9Y2/uI6TJUTcgyCp+ilgU0JxemrSI" +
                    "PFk3jbCbzamQ6Shkw/jDRzYoXpBRg/2QDkbq+j3ljInu0RHDfOeXf3VBfHSnQ66H" +
                    "CwIDAQAB",
            negotiationVersion = "1615879139745",
        ),
        Region.IN to ServerParams(
            serverURL = "https://component-otapc-in.allawnos.com/update/v3",
            pubKeyB64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAwYtghkzeStC9YvAwOQmW" +
                    "ylbp74Tj8hhi3f9IlK7A/CWrGbLgzz/BeKxNb45zBN8pgaaEOwAJ1qZQV5G4nPro" +
                    "WCPOP1ro1PkemFJvw/vzOOT5uN0ADnHDzZkZXCU/knxqUSfLcwQlHXsYhNsAm7uO" +
                    "KjY9YXF4zWzYN0eFPkML3Pj/zg7hl/ov9clB2VeyI1/blMHFfcNA/fvqDTENXcNB" +
                    "IhgJvXiCpLcZqp+aLZPC5AwY/sCb3j5jTWer0Rk0ZjQBZE1AncwYvUx4mA65U59c" +
                    "WpTyl4c47J29MsQ66hqWv6eBHlDNZSEsQpHePUqgsf7lmO5Wd7teB8ugQki2oz1Y" +
                    "5QIDAQAB",
            negotiationVersion = "1615896309308",
        ),
        Region.EU to ServerParams(
            serverURL = "https://component-otapc-eu.allawnos.com/update/v3",
            pubKeyB64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAh8/EThsK3f0WyyPgrtXb" +
                    "/D0Xni6UZNppaQHUqHWo976cybl92VxmehE0ISObnxERaOtrlYmTPIxkVC9MMueD" +
                    "vTwZ1l0KxevZVKU0sJRxNR9AFcw6D7k9fPzzpNJmhSlhpNbt3BEepdgibdRZbacF" +
                    "3NWy3ejOYWHgxC+I/Vj1v7QU5gD+1OhgWeRDcwuV4nGY1ln2lvkRj8EiJYXfkSq/" +
                    "wUI5AvPdNXdEqwou4FBcf6mD84G8pKDyNTQwwuk9lvFlcq4mRqgYaFg9DAgpDgqV" +
                    "K4NTJWM7tQS1GZuRA6PhupfDqnQExyBFhzCefHkEhcFywNyxlPe953NWLFWwbGvF" +
                    "KwIDAQAB",
            negotiationVersion = "1615897067573",
        ),
    )

    /** RUI 1 / req-version 1 endpoints (legacy AES-ECB). */
    val urlsV1: Map<Region, String> = mapOf(
        Region.GL to "https://ifota.realmemobile.com/post/Query_Update",
        Region.CN to "https://iota.coloros.com/post/Query_Update",
        Region.IN to "https://ifota-in.realmemobile.com/post/Query_Update",
        Region.EU to "https://ifota-eu.realmemobile.com/post/Query_Update",
    )

    /** RUI ≥ 2 / req-version 1 endpoints (legacy AES-CTR with deterministic IV). */
    val urlsV2Old: Map<Region, String> = mapOf(
        Region.GL to "https://component-ota-f.coloros.com/update/v3",
        Region.CN to "https://component-ota.coloros.com/update/v3",
        Region.IN to "https://component-ota-in.coloros.com/update/v3",
        Region.EU to "https://component-ota-eu.coloros.com/update/v3",
    )

    const val ONEPLUS_URL = "https://otag.h2os.com/post/Query_Update"
    const val ZERO_OTA_SUFFIX = "_0001_000000000001"
    val ONEPLUS_MODEL_TOKENS = setOf("OnePlus", "oneplus", "Oneplus")
}
