package com.desmond.ofd.firmware

import com.desmond.ofd.http.FIRMWARE_USER_AGENT
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
        assertEquals(FIRMWARE_USER_AGENT, userAgent)
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
        assertEquals(null, result.rejectionCode)
    }

    @Test fun parses_antileech_response_code_from_small_body() = runBlocking {
        val rejection = """{"body":null,"errMsg":"2306","responseCode":2306}"""
        val client = clientReturning { request ->
            response(
                request = request,
                code = 200,
                headers = mapOf("Content-Length" to rejection.length.toString()),
                body = rejection,
                bodyMediaType = "application/json",
            )
        }

        val result = FirmwareUrlProbe(client).probe("https://example.com/firmware.zip")

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertEquals(rejection.length.toLong(), result.observedSize)
        assertEquals("2306", result.rejectionCode)
        assertTrue(result.retryable)
        assertTrue(result.detail.contains("2306"))
    }

    @Test fun resolves_download_check_gate_before_range_probe() = runBlocking {
        val finalUrl = "https://gauss-compotaauto-cn.allawnfs.com/component-ota/file.zip"
        val seen = mutableListOf<Pair<String, String?>>()
        val client = clientReturning { request ->
            seen += request.url.encodedPath to request.header("Range")
            when (request.url.encodedPath) {
                "/downloadCheck" -> response(
                    request = request,
                    code = 302,
                    message = "Found",
                    headers = mapOf("Location" to finalUrl),
                    body = "",
                )
                "/component-ota/file.zip" -> response(
                    request = request,
                    code = 206,
                    headers = mapOf("Content-Range" to "bytes 0-0/$TWO_GIB"),
                )
                else -> error("Unexpected URL ${request.url}")
            }
        }

        val result = FirmwareUrlProbe(client).probe(
            "https://component-ota-cn.allawntech.com/downloadCheck?id=abc",
        )

        assertTrue(result is FirmwareUrlProbeResult.Success)
        result as FirmwareUrlProbeResult.Success
        assertEquals(finalUrl, result.resolvedUrl)
        assertEquals(TWO_GIB, result.totalSize)
        assertEquals(listOf("/downloadCheck" to null, "/component-ota/file.zip" to "bytes=0-0"), seen)
    }

    @Test fun reports_download_check_antileech_without_range_probe() = runBlocking {
        val rejection = """{"errMsg":"2306","responseCode":2306}"""
        var rangeHeader: String? = "not-called"
        val client = clientReturning { request ->
            rangeHeader = request.header("Range")
            response(
                request = request,
                code = 200,
                headers = mapOf("Content-Length" to rejection.length.toString()),
                body = rejection,
                bodyMediaType = "application/json",
            )
        }

        val result = FirmwareUrlProbe(client).probe(
            "https://component-ota-cn.allawntech.com/downloadCheck?id=abc",
        )

        assertTrue(result is FirmwareUrlProbeResult.Failure)
        result as FirmwareUrlProbeResult.Failure
        assertEquals(null, rangeHeader)
        assertEquals("2306", result.rejectionCode)
        assertTrue(result.detail.contains("2306"))
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
        body: String = "x",
        bodyMediaType: String = "text/plain",
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(message)
            .body(body.toResponseBody(bodyMediaType.toMediaType()))
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private companion object {
        private const val TWO_GIB = 2L * 1024L * 1024L * 1024L
    }
}
