package com.desmond.ofd.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Polls the GitHub Releases API for the upstream repository and compares the latest tag
 * with [BuildConfig.VERSION_NAME]. Returns one of three terminal results.
 *
 * Stateless and uses its own [OkHttpClient] (separate from the firmware downloader's
 * high-concurrency client) — small timeouts, no special interceptors.
 */
object UpdateChecker {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/suddenBook/OPlus_firmware_download/releases/latest"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json: Json = Json { ignoreUnknownKeys = true }

    sealed interface Result {
        data class UpToDate(val current: String) : Result
        data class Newer(
            val latestTag: String,
            val htmlUrl: String,
            val notes: String?,
        ) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun check(currentVersion: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.Failed("HTTP ${resp.code}")
                }
                val body = resp.body?.string()
                    ?: return@withContext Result.Failed("Empty response")
                val release = json.decodeFromString<GitHubRelease>(body)
                if (release.draft || release.prerelease) {
                    return@withContext Result.UpToDate(currentVersion)
                }
                val latest = release.tagName.removePrefix("v")
                if (compareVersions(latest, currentVersion) > 0) {
                    Result.Newer(
                        latestTag = latest,
                        htmlUrl = release.htmlUrl,
                        notes = release.body?.takeIf { it.isNotBlank() } ?: release.name,
                    )
                } else {
                    Result.UpToDate(currentVersion)
                }
            }
        }.getOrElse { t ->
            Result.Failed(t.message ?: t::class.simpleName ?: "unknown")
        }
    }

    /**
     * Compare two dotted-numeric version strings (e.g. "1.2.0" vs "1.10"). Non-numeric segments
     * fall back to lexicographic compare so unusual tags like "1.1-rc1" sort sensibly.
     */
    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.', '-')
        val right = b.split('.', '-')
        val n = maxOf(left.size, right.size)
        for (i in 0 until n) {
            val l = left.getOrNull(i).orEmpty()
            val r = right.getOrNull(i).orEmpty()
            val li = l.toIntOrNull()
            val ri = r.toIntOrNull()
            val cmp = when {
                li != null && ri != null -> li.compareTo(ri)
                else -> l.compareTo(r)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }

    @Serializable
    private data class GitHubRelease(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String,
        val name: String? = null,
        val body: String? = null,
        val prerelease: Boolean = false,
        val draft: Boolean = false,
    )
}
