package com.desmond.ofd.firmware

import com.desmond.ofd.http.BROWSER_USER_AGENT
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareUrlProbeTest {

    @Test fun succeeds_from_content_range() = runBlocking {
        var userAgent: String? = null
        val client = clientReturning { request ->
            userAgent = request.header("User-Agent")
            response(
                request = request,
                code = 206,
                headers = mapOf(
                    "Content-Range" to "bytes 0-0/$TWO_GIB",
                    "x-amz-meta-filemd5" to "abc123",
                ),
            )
        }

        val result = FirmwareUrlProbe(client).probe("https://example.com/firmware.zip")

        assertTrue(result is FirmwareUrlProbeResult.Success)
        result as FirmwareUrlProbeResult.Success
        assertEquals(TWO_GIB, result.totalSize)
        assertEquals("abc123", result.md5)
        assertEquals(BROWSER_USER_AGENT, userAgent)
    }

    @Test fun rejects_small_content_range_as_retryable_failure() = runBlocking {
        val client = clientReturning { request ->
            response(
                request = request,
                code = 206,
                headers = mapOf("Content-Range" to "bytes 0-0/49"),
            )
        }

        val result = FirmwareUrlProbe(client).probe("https://example.com/firmware.zip")

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertEquals(49L, result.observedSize)
        assertTrue(result.retryable)
    }

    @Test fun rejects_small_content_length_as_retryable_failure() = runBlocking {
        val client = clientReturning { request ->
            response(
                request = request,
                code = 200,
                headers = mapOf("Content-Length" to "49"),
            )
        }

        val result = FirmwareUrlProbe(client).probe("https://example.com/firmware.zip")

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertEquals(49L, result.observedSize)
        assertTrue(result.retryable)
    }

    @Test fun rejects_invalid_content_range() = runBlocking {
        val client = clientReturning { request ->
            response(
                request = request,
                code = 206,
                headers = mapOf("Content-Range" to "bytes */49"),
            )
        }

        val result = FirmwareUrlProbe(client).probe("https://example.com/firmware.zip")

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertTrue(result.retryable)
        assertTrue(result.detail.contains("Content-Range"))
    }

    @Test fun treats_forbidden_as_retryable_url_failure() = runBlocking {
        val client = clientReturning { request ->
            response(request = request, code = 403, message = "Forbidden")
        }

        val result = FirmwareUrlProbe(client).probe("https://example.com/firmware.zip")

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertEquals(403, result.httpCode)
        assertTrue(result.retryable)
    }

    @Test fun treats_not_found_as_non_retryable_url_failure() = runBlocking {
        val client = clientReturning { request ->
            response(request = request, code = 404, message = "Not Found")
        }

        val result = FirmwareUrlProbe(client).probe("https://example.com/firmware.zip")

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertEquals(404, result.httpCode)
        assertTrue(!result.retryable)
    }

    @Test fun rejects_expected_size_mismatch() = runBlocking {
        val client = clientReturning { request ->
            response(
                request = request,
                code = 206,
                headers = mapOf("Content-Range" to "bytes 0-0/$TWO_GIB"),
            )
        }

        val result = FirmwareUrlProbe(client).probe(
            url = "https://example.com/firmware.zip",
            expectedSize = TWO_GIB + 1,
        )

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertEquals(TWO_GIB, result.observedSize)
        assertTrue(result.retryable)
        assertTrue(result.detail.contains("Size mismatch"))
    }

    @Test fun rejects_invalid_url_without_retry() = runBlocking {
        val result = FirmwareUrlProbe(OkHttpClient()).probe("not a url")

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertTrue(!result.retryable)
        assertTrue(result.detail.contains("Invalid download URL"))
    }

    private fun clientReturning(block: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain -> block(chain.request()) })
            .build()

    private fun response(
        request: Request,
        code: Int,
        message: String = "OK",
        headers: Map<String, String> = emptyMap(),
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body("x".toResponseBody("text/plain".toMediaType()))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private companion object {
        private const val TWO_GIB = 2L * 1024L * 1024L * 1024L
    }
}
