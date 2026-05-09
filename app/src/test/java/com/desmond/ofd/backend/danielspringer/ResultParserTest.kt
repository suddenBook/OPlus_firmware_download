package com.desmond.ofd.backend.danielspringer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultParserTest {

    private val resultHtml: String =
        ResultParserTest::class.java
            .getResource("/danielspringer/after_post.html")!!
            .readText(Charsets.UTF_8)

    @Test fun extracts_download_url_from_downloadBtn() {
        val url = ResultParser.extractDownloadUrl(resultHtml)
        assertNotNull(url)
        assertTrue(
            "expected a gauss-compotaauto S3 URL, got: $url",
            url!!.contains("gauss-compotaauto-c-cn.allawnfs.com"),
        )
        assertTrue(url.contains(".zip?"))
        assertTrue(url.contains("AWSAccessKeyId="))
    }

    @Test fun extracts_selected_version_name() {
        val name = ResultParser.extractSelectedVersionName(resultHtml, 0)
        assertEquals("PLK110_16.0.7.206(CN01)", name)
    }

    @Test fun parses_expires_epoch_seconds_from_url() {
        val url =
            "https://gauss-compotaauto-c-cn.allawnfs.com/x/y/z.zip" +
                "?sign=abc&t=def&AWSAccessKeyId=key&Expires=1778275440&Signature=sig"
        assertEquals(1778275440L, ResultParser.parseExpiresEpochSeconds(url))
    }

    @Test fun returns_null_for_url_without_expires() {
        val url = "https://example.com/path?foo=bar"
        assertNull(ResultParser.parseExpiresEpochSeconds(url))
    }

    @Test fun returns_null_when_downloadBtn_missing() {
        assertNull(ResultParser.extractDownloadUrl("<html><body>nope</body></html>"))
    }
}
