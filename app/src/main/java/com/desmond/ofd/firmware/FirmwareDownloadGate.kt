package com.desmond.ofd.firmware

import com.desmond.ofd.http.FIRMWARE_USER_AGENT
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal object FirmwareDownloadGate {
    fun isOplusDownloadGate(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.encodedPath == "/downloadCheck" ||
            parsed.encodedPath.endsWith("/downloadCheck")
    }

    suspend fun resolve(
        url: String,
        httpClient: OkHttpClient,
        tag: Any? = null,
    ): FirmwareDownloadGateResult {
        if (!isOplusDownloadGate(url)) {
            return FirmwareDownloadGateResult.Success(url)
        }

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", FIRMWARE_USER_AGENT)
            .header("Accept", "*/*")
        if (tag != null) builder.tag(tag)

        val noRedirectClient = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        return try {
            noRedirectClient.newCall(builder.build()).await().use { resp ->
                parseGateResponse(resp)
            }
        } catch (e: IOException) {
            FirmwareDownloadGateResult.Failure(
                detail = "Download gate request failed: ${e.message ?: e::class.simpleName ?: "IOException"}",
                retryable = true,
            )
        } catch (e: IllegalArgumentException) {
            FirmwareDownloadGateResult.Failure(
                detail = "Invalid download gate URL: ${e.message ?: "(no message)"}",
                retryable = false,
            )
        }
    }

    private fun parseGateResponse(resp: Response): FirmwareDownloadGateResult {
        if (resp.code in 300..399) {
            val location = resp.header("Location")
                ?: return FirmwareDownloadGateResult.Failure(
                    detail = "Download gate returned HTTP ${resp.code} without Location",
                    retryable = true,
                    httpCode = resp.code,
                )
            val resolved = resp.request.url.resolve(location)
                ?: return FirmwareDownloadGateResult.Failure(
                    detail = "Download gate returned invalid Location",
                    retryable = false,
                    httpCode = resp.code,
                )
            return FirmwareDownloadGateResult.Success(resolved.toString())
        }

        if (resp.isSuccessful) {
            val contentLength = resp.header("Content-Length")?.toLongOrNull()
                ?: resp.body?.contentLength()?.takeIf { it >= 0 }
            val rejectionCode = if (contentLength != null && contentLength in 1..MAX_REJECTION_BODY_BYTES) {
                decodeRejectionCode(resp)
            } else {
                null
            }
            return FirmwareDownloadGateResult.Failure(
                detail = rejectionCode?.let { "Anti-leech rejection (responseCode: $it)" }
                    ?: "Download gate did not redirect to a firmware file",
                retryable = true,
                httpCode = resp.code,
                rejectionCode = rejectionCode,
            )
        }

        return FirmwareDownloadGateResult.Failure(
            detail = "Download gate returned HTTP ${resp.code}: ${resp.message}",
            retryable = resp.code == 403 ||
                resp.code == 410 ||
                resp.code == 408 ||
                resp.code == 429 ||
                resp.code in 500..599,
            httpCode = resp.code,
        )
    }

    private fun decodeRejectionCode(resp: Response): String? = runCatching {
        val text = resp.peekBody(MAX_REJECTION_BODY_BYTES).string()
        REJECTION_CODE_RE.find(text)?.groupValues?.get(1)
    }.getOrNull()

    private const val MAX_REJECTION_BODY_BYTES = 2048L
    private val REJECTION_CODE_RE = Regex(""""responseCode"\s*:\s*"?(\d+)"?""")
}

internal sealed interface FirmwareDownloadGateResult {
    data class Success(val resolvedUrl: String) : FirmwareDownloadGateResult

    data class Failure(
        val detail: String,
        val retryable: Boolean,
        val httpCode: Int? = null,
        val rejectionCode: String? = null,
    ) : FirmwareDownloadGateResult
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
