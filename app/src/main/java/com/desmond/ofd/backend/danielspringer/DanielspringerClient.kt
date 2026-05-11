package com.desmond.ofd.backend.danielspringer

import com.desmond.ofd.backend.realmeota.data.Region
import com.desmond.ofd.firmware.FirmwareUrlProbe
import com.desmond.ofd.firmware.FirmwareUrlProbeResult
import com.desmond.ofd.http.BROWSER_USER_AGENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Live URL fetch from danielspringer.at. Each call creates its own session
 * (fresh OkHttpClient + cookie jar) so concurrent calls don't trample each other.
 *
 * Two-step flow per session:
 *   1. GET `/index.php?view=ota` to seed `PHPSESSID`.
 *   2. POST `device=...&region=...&version_index=...` (auto-follows the 302 → result page).
 *      Then parse `<a id="downloadBtn" href=...>` for the S3 URL, HEAD it to read size + md5.
 */
class DanielspringerClient {

    /** Fetch and parse the device → region → versions catalog. Cached by caller, not here. */
    suspend fun fetchCatalog(): DanielspringerCatalog = withContext(Dispatchers.IO) {
        val client = newScrapeClient()
        val req = Request.Builder()
            .url(FORM_URL)
            .header("User-Agent", BROWSER_USER_AGENT)
            .build()
        val html = client.newCall(req).await().use { resp ->
            check(resp.isSuccessful) { "GET $FORM_URL returned HTTP ${resp.code}" }
            resp.body?.string().orEmpty()
        }
        DanielspringerCatalog.parse(html)
    }

    /**
     * Resolve the latest (`version_index=0`) firmware URL for the given site labels.
     * Pass the labels straight from [DanielspringerCatalog.siteForModel].
     */
    suspend fun fetchLatestUrl(
        siteDevice: String,
        siteRegion: String,
        versionIndex: Int = 0,
    ): DanielspringerResult = withContext(Dispatchers.IO) {
        val client = newScrapeClient()

        // 1. seed PHPSESSID
        client.newCall(Request.Builder().url(FORM_URL).header("User-Agent", BROWSER_USER_AGENT).build())
            .await().close()

        // 2. POST + auto-follow 302 → result page
        val body = FormBody.Builder()
            .add("device", siteDevice)
            .add("region", siteRegion)
            .add("version_index", versionIndex.toString())
            .build()
        val postReq = Request.Builder()
            .url(FORM_URL)
            .post(body)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Origin", BASE_URL)
            .header("Referer", FORM_URL)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        val resultHtml = client.newCall(postReq).await().use { resp ->
            check(resp.isSuccessful) { "POST returned HTTP ${resp.code}" }
            resp.body?.string().orEmpty()
        }

        val parsed = ResultParser.parseResultHtml(resultHtml, versionIndex)
        val downloadUrl = parsed.downloadUrl
            ?: error("Site returned no download URL — region/version may be empty for this device.")
        val displayName = parsed.displayName ?: "(unknown)"
        val expires = ResultParser.parseExpiresEpochSeconds(downloadUrl) ?: 0L

        // 3. Range GET for accurate size + md5. AWS pre-signed URLs are sometimes signed
        //    only for GET; HEAD then returns 403 with a tiny error-page Content-Length
        //    that gets misread as the real file size. Range bytes=0-0 always yields 206
        //    Partial Content with `Content-Range: bytes 0-0/<TOTAL>`.
        val (size, md5) = when (val probe = FirmwareUrlProbe(client).probe(downloadUrl)) {
            is FirmwareUrlProbeResult.Success -> probe.totalSize to probe.md5
            is FirmwareUrlProbeResult.Failure -> {
                throw IOException("Download URL probe failed: ${probe.detail}")
            }
        }

        DanielspringerResult(
            downloadUrl = downloadUrl,
            sizeBytes = size,
            md5 = md5,
            displayName = displayName,
            realOtaVersion = parsed.realOtaVersion,
            securityPatch = parsed.securityPatch,
            manualOnly = parsed.manualOnly,
            expiresAtEpochSeconds = expires,
        )
    }

    /** Convenience wrapper: resolve via realme-ota's [Region] + a model code. */
    suspend fun fetchLatestUrlForModel(
        catalog: DanielspringerCatalog,
        model: String,
        region: Region,
        versionIndex: Int = 0,
    ): DanielspringerResult? {
        val (siteDevice, siteRegion) = catalog.siteForModel(model, region) ?: return null
        return fetchLatestUrl(siteDevice, siteRegion, versionIndex)
    }

    private fun newScrapeClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .cookieJar(SimpleCookieJar())
        .build()

    companion object {
        const val BASE_URL = "https://roms.danielspringer.at"
        const val FORM_URL = "$BASE_URL/index.php?view=ota"
    }
}

/** Per-call in-memory cookie jar — fresh state per [DanielspringerClient.fetchLatestUrl]. */
private class SimpleCookieJar : CookieJar {
    private val store = mutableMapOf<String, Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            store["${c.name}|${c.domain}|${c.path}"] = c
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store.values.filter { it.matches(url) }
}

/** Bridges OkHttp's enqueue() to a suspend function. */
private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** HTML / URL parsing helpers, separated for testability without HTTP. */
object ResultParser {

    data class Parsed(
        val downloadUrl: String?,
        val displayName: String?,
        val realOtaVersion: String?,
        val securityPatch: String?,
        val manualOnly: Boolean,
    )

    /**
     * Parse the post-POST result HTML once and return everything we care about.
     * The site has at least two layouts depending on the CDN origin (OPPO allawnfs vs.
     * Google googleapis). The `<div id="resultBox" data-url="...">` is canonical and
     * present in both; `<a id="downloadBtn">` is only present in the OPPO-CDN layout.
     */
    fun parseResultHtml(html: String, versionIndex: Int): Parsed {
        val doc = Jsoup.parse(html)

        // 1. Download URL — try canonical resultBox[data-url] first, then anchor fallback.
        val downloadUrl = doc.selectFirst("#resultBox[data-url]")
            ?.attr("data-url")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("a#downloadBtn[href]")
                ?.attr("href")
                ?.takeIf { it.isNotBlank() }

        // 2. Display name (from version dropdown).
        val versionSelect = doc.selectFirst("select#version")
        val displayName = versionSelect?.selectFirst("option[selected]")?.text()?.trim()
            ?: versionSelect?.selectFirst("option[value=$versionIndex]")?.text()?.trim()

        // 3. Chips inside the result box — pick out the OTA version + security patch.
        val chips = doc.select(".ota-chip").map { it.text().trim() }
        val realOtaVersion = chips.firstOrNull { OTA_TIMESTAMP_RE.containsMatchIn(it) }
        val securityPatch = chips
            .firstOrNull { it.startsWith("Sec. Patch:", ignoreCase = true) }
            ?.substringAfter(":")
            ?.trim()
        val manualOnly = chips.any { it.equals("manual-only", ignoreCase = true) }

        return Parsed(
            downloadUrl = downloadUrl,
            displayName = displayName,
            realOtaVersion = realOtaVersion,
            securityPatch = securityPatch,
            manualOnly = manualOnly,
        )
    }

    /** Backwards-compat shim used by older tests. */
    fun extractDownloadUrl(html: String): String? = parseResultHtml(html, 0).downloadUrl
    fun extractSelectedVersionName(html: String, versionIndex: Int): String? =
        parseResultHtml(html, versionIndex).displayName

    /** Parse `Expires=<unix-seconds>` from an AWS pre-signed URL's query string. */
    fun parseExpiresEpochSeconds(url: String): Long? = runCatching {
        url.toHttpUrl().queryParameter("Expires")?.toLongOrNull()
    }.getOrNull()

    private val OTA_TIMESTAMP_RE = Regex("""_\d{12}\b""")
}
