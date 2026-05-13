package com.desmond.ofd.firmware

import com.desmond.ofd.http.FIRMWARE_USER_AGENT
import com.desmond.ofd.http.parseContentRange
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class FirmwareUrlProbe(
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    suspend fun probe(
        url: String,
        expectedSize: Long = -1L,
        tag: Any? = null,
    ): FirmwareUrlProbeResult {
        return try {
            val resolvedUrl = when (val gate = FirmwareDownloadGate.resolve(url, httpClient, tag)) {
                is FirmwareDownloadGateResult.Success -> gate.resolvedUrl
                is FirmwareDownloadGateResult.Failure -> return FirmwareUrlProbeResult.Failure(
                    detail = gate.detail,
                    retryable = gate.retryable,
                    httpCode = gate.httpCode,
                    rejectionCode = gate.rejectionCode,
                )
            }
            val builder = Request.Builder()
                .url(resolvedUrl)
                .header("Range", "bytes=0-0")
                .header("User-Agent", FIRMWARE_USER_AGENT)
                .header("Accept", "*/*")
            if (tag != null) builder.tag(tag)

            val call = httpClient.newCall(builder.build())
            call.await().use { resp ->
                parseResponse(resp, expectedSize, resolvedUrl)
            }
        } catch (e: IOException) {
            FirmwareUrlProbeResult.Failure(
                detail = "Network probe failed: ${e.message ?: e::class.simpleName ?: "IOException"}",
                retryable = true,
            )
        } catch (e: IllegalArgumentException) {
            FirmwareUrlProbeResult.Failure(
                detail = "Invalid download URL: ${e.message ?: "(no message)"}",
                retryable = false,
            )
        }
    }

    private fun parseResponse(resp: Response, expectedSize: Long, resolvedUrl: String): FirmwareUrlProbeResult {
        var rejectionCode: String? = null
        val totalSize = when {
            resp.code == 206 -> {
                parseContentRange(resp.header("Content-Range"))?.totalSize
                    ?: return failure("Invalid Content-Range: ${resp.header("Content-Range") ?: "(missing)"}")
            }
            resp.isSuccessful -> {
                val cl = resp.header("Content-Length")?.toLongOrNull()
                    ?: resp.body?.contentLength()?.takeIf { it >= 0 }
                    ?: return failure("Missing Content-Length")
                // OPPO's downloadCheck gate returns HTTP 200 with a tiny JSON body
                // (~49 B) like {"body":null,"errMsg":"2306","responseCode":2306} when
                // it rejects a request. Peek the body so we can surface the real cause.
                if (cl in 1..MAX_REJECTION_BODY_BYTES) {
                    rejectionCode = decodeRejectionCode(resp)
                }
                cl
            }
            else -> {
                return FirmwareUrlProbeResult.Failure(
                    detail = "HTTP ${resp.code}: ${resp.message}",
                    retryable = resp.code == 403 ||
                        resp.code == 410 ||
                        resp.code == 408 ||
                        resp.code == 429 ||
                        resp.code in 500..599,
                    httpCode = resp.code,
                )
            }
        }

        validateFirmwareSize(totalSize, expectedSize)?.let { problem ->
            return FirmwareUrlProbeResult.Failure(
                detail = rejectionCode?.let { "Anti-leech rejection (responseCode: $it)" } ?: problem,
                retryable = true,
                observedSize = totalSize,
                rejectionCode = rejectionCode,
            )
        }

        return FirmwareUrlProbeResult.Success(
            totalSize = totalSize,
            acceptsRanges = resp.code == 206,
            md5 = resp.header("x-amz-meta-filemd5"),
            resolvedUrl = resolvedUrl,
        )
    }

    private fun decodeRejectionCode(resp: Response): String? = runCatching {
        val text = resp.peekBody(MAX_REJECTION_BODY_BYTES).string()
        REJECTION_CODE_RE.find(text)?.groupValues?.get(1)
    }.getOrNull()

    private fun failure(detail: String): FirmwareUrlProbeResult.Failure =
        FirmwareUrlProbeResult.Failure(detail = detail, retryable = true)

    private companion object {
        const val MAX_REJECTION_BODY_BYTES = 2048L
        val REJECTION_CODE_RE = Regex(""""responseCode"\s*:\s*"?(\d+)"?""")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

internal sealed interface FirmwareUrlProbeResult {
    data class Success(
        val totalSize: Long,
        val acceptsRanges: Boolean,
        val md5: String?,
        val resolvedUrl: String,
    ) : FirmwareUrlProbeResult

    data class Failure(
        val detail: String,
        val retryable: Boolean,
        val observedSize: Long? = null,
        val httpCode: Int? = null,
        /** OPPO `downloadCheck` `responseCode` when the body looks like an anti-leech rejection. */
        val rejectionCode: String? = null,
    ) : FirmwareUrlProbeResult
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) {
                cont.resume(response)
            } else {
                response.close()
            }
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
