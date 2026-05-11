package com.desmond.ofd.firmware

import com.desmond.ofd.http.BROWSER_USER_AGENT
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
            val builder = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0")
                .header("User-Agent", BROWSER_USER_AGENT)
            if (tag != null) builder.tag(tag)

            val call = httpClient.newCall(builder.build())
            call.await().use { resp ->
                parseResponse(resp, expectedSize)
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

    private fun parseResponse(resp: Response, expectedSize: Long): FirmwareUrlProbeResult {
        val totalSize = when {
            resp.code == 206 -> {
                parseContentRange(resp.header("Content-Range"))?.totalSize
                    ?: return failure("Invalid Content-Range: ${resp.header("Content-Range") ?: "(missing)"}")
            }
            resp.isSuccessful -> {
                resp.header("Content-Length")?.toLongOrNull()
                    ?: resp.body?.contentLength()?.takeIf { it >= 0 }
                    ?: return failure("Missing Content-Length")
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
                detail = problem,
                retryable = true,
                observedSize = totalSize,
            )
        }

        return FirmwareUrlProbeResult.Success(
            totalSize = totalSize,
            acceptsRanges = resp.code == 206,
            md5 = resp.header("x-amz-meta-filemd5"),
        )
    }

    private fun failure(detail: String): FirmwareUrlProbeResult.Failure =
        FirmwareUrlProbeResult.Failure(detail = detail, retryable = true)

    private companion object {
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
    ) : FirmwareUrlProbeResult

    data class Failure(
        val detail: String,
        val retryable: Boolean,
        val observedSize: Long? = null,
        val httpCode: Int? = null,
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
