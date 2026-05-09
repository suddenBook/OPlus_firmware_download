package com.desmond.ofd.download

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max

/**
 * Multi-threaded HTTP downloader writing to a SAF [Uri] via random-offset writes.
 *
 *  Flow:
 *   1. Probe the URL with `Range: bytes=0-0` to learn the total size.
 *   2. Pre-allocate the output file by writing one byte at the last offset.
 *   3. Split the file into N chunks ([autoThreadCount]) and download them concurrently.
 *      Each chunk opens its own [ParcelFileDescriptor], seeks to the chunk's start,
 *      and writes its bytes — Linux/Android FS handles concurrent positional writes fine.
 *   4. A ticker emits aggregated progress every 250 ms.
 *   5. Cancellation: the parent coroutine's [job.invokeOnCompletion] cancels every active
 *      OkHttp [Call], which forces in-flight reads to throw `IOException("Canceled")` →
 *      bubbles up as a [CancellationException] to the orchestrator.
 */
class DownloadEngine(private val httpClient: OkHttpClient) {

    suspend fun download(
        url: String,
        contentResolver: ContentResolver,
        targetUri: Uri,
        expectedSize: Long,
        threadCountOverride: Int? = null,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long, speedBps: Long) -> Unit,
    ): DownloadOutcome = withContext(Dispatchers.IO) {
        val probe = probeSize(url)
        val totalSize = probe.totalSize.takeIf { it > 0 } ?: expectedSize
        if (totalSize <= 0) {
            return@withContext singleThreadedDownload(
                url, contentResolver, targetUri, onProgress,
            )
        }
        if (!probe.acceptsRanges) {
            return@withContext singleThreadedDownload(
                url, contentResolver, targetUri, onProgress, knownSize = totalSize,
            )
        }

        val threadCount = threadCountOverride
            ?.coerceIn(1, 32)
            ?: autoThreadCount(totalSize)
        if (threadCount <= 1) {
            return@withContext singleThreadedDownload(
                url, contentResolver, targetUri, onProgress, knownSize = totalSize,
            )
        }

        try {
            preallocate(contentResolver, targetUri, totalSize)
        } catch (t: Throwable) {
            // Pre-allocation isn't strictly required; positional writes will extend the file.
        }

        val downloaded = AtomicLong(0L)
        try {
            coroutineScope {
                val ticker = launch { tickProgress(downloaded, totalSize, onProgress) }
                try {
                    val chunks = splitChunks(totalSize, threadCount)
                    chunks.map { chunk ->
                        async(Dispatchers.IO) {
                            downloadChunk(
                                url = url,
                                contentResolver = contentResolver,
                                targetUri = targetUri,
                                chunk = chunk,
                                onChunkBytes = { delta -> downloaded.addAndGet(delta) },
                            )
                        }
                    }.awaitAll()
                } finally {
                    ticker.cancel()
                }
                // Final progress emit so the UI snaps to 100 %.
                onProgress(downloaded.get(), totalSize, 0L)
            }
        } catch (e: IOException) {
            return@withContext DownloadOutcome.IoError(e.message ?: "unknown")
        }
        DownloadOutcome.Success(totalSize)
    }

    private suspend fun probeSize(url: String): SizeProbe = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .build()
        runCatching {
            httpClient.newCall(req).await().use { resp ->
                when {
                    resp.code == 206 -> resp.header("Content-Range")
                        ?.substringAfter('/')
                        ?.toLongOrNull()
                        ?.let { SizeProbe(it, acceptsRanges = true) }
                        ?: SizeProbe.Unknown
                    resp.isSuccessful -> SizeProbe(
                        totalSize = resp.body?.contentLength() ?: -1L,
                        acceptsRanges = false,
                    )
                    else -> SizeProbe.Unknown
                }
            }
        }.getOrElse {
            if (it is CancellationException) throw it
            SizeProbe.Unknown
        }
    }

    private fun autoThreadCount(totalSize: Long): Int {
        val cores = max(1, Runtime.getRuntime().availableProcessors())
        val sizeMb = totalSize / (1L shl 20)
        return when {
            sizeMb < 16 -> 1               // tiny files: single conn is faster
            sizeMb < 128 -> minOf(4, cores)
            sizeMb < 1024 -> minOf(8, cores * 2)
            else -> minOf(16, cores * 2)   // large files: up to 16 parallel chunks
        }.coerceAtLeast(1)
    }

    private fun splitChunks(totalSize: Long, threadCount: Int): List<Chunk> {
        val baseChunkSize = totalSize / threadCount
        return (0 until threadCount).map { i ->
            val start = i * baseChunkSize
            val end = if (i == threadCount - 1) totalSize - 1 else start + baseChunkSize - 1
            Chunk(i, start, end)
        }
    }

    private fun preallocate(contentResolver: ContentResolver, uri: Uri, size: Long) {
        contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
            FileOutputStream(pfd.fileDescriptor).use { fos ->
                if (size > 0) {
                    fos.channel.position(size - 1)
                    fos.write(0)
                    fos.fd.sync()
                }
            }
        }
    }

    private suspend fun CoroutineScope.tickProgress(
        downloaded: AtomicLong,
        totalSize: Long,
        onProgress: suspend (Long, Long, Long) -> Unit,
    ) {
        var lastBytes = 0L
        var lastTimeMs = System.currentTimeMillis()
        while (isActive) {
            delay(250)
            val now = System.currentTimeMillis()
            val current = downloaded.get()
            val deltaB = current - lastBytes
            val deltaT = (now - lastTimeMs).coerceAtLeast(1)
            val speed = deltaB * 1000L / deltaT
            onProgress(current, totalSize, speed)
            lastBytes = current
            lastTimeMs = now
        }
    }

    private suspend fun downloadChunk(
        url: String,
        contentResolver: ContentResolver,
        targetUri: Uri,
        chunk: Chunk,
        onChunkBytes: (Long) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=${chunk.start}-${chunk.end}")
            .build()
        val call = httpClient.newCall(request)

        // Whenever the surrounding coroutine is canceled, force-cancel the OkHttp call so
        // any in-flight blocking read throws IOException and unblocks the chunk worker.
        val onCompletion = kotlin.coroutines.coroutineContext.job.invokeOnCompletion {
            if (it != null) runCatching { call.cancel() }
        }
        try {
            call.await().use { resp ->
                if (resp.code != 206) {
                    throw IOException("Chunk ${chunk.index} HTTP ${resp.code}")
                }
                val pfd = contentResolver.openFileDescriptor(targetUri, "rw")
                    ?: throw IOException("Chunk ${chunk.index}: cannot open Uri")
                pfd.use { fd ->
                    FileOutputStream(fd.fileDescriptor).use { fos ->
                        fos.channel.position(chunk.start)
                        val buf = ByteArray(BUFFER_SIZE)
                        resp.body!!.byteStream().use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n == -1) break
                                fos.write(buf, 0, n)
                                onChunkBytes(n.toLong())
                            }
                        }
                        fos.fd.sync()
                    }
                }
            }
        } catch (e: IOException) {
            if (call.isCanceled()) throw downloadCanceled(e)
            throw e
        } finally {
            onCompletion.dispose()
        }
    }

    private suspend fun singleThreadedDownload(
        url: String,
        contentResolver: ContentResolver,
        targetUri: Uri,
        onProgress: suspend (Long, Long, Long) -> Unit,
        knownSize: Long = -1L,
    ): DownloadOutcome {
        val request = Request.Builder().url(url).build()
        val call = httpClient.newCall(request)
        val onCompletion = kotlin.coroutines.coroutineContext.job.invokeOnCompletion {
            if (it != null) runCatching { call.cancel() }
        }
        return try {
            call.await().use { resp ->
                if (!resp.isSuccessful) {
                    return DownloadOutcome.HttpError(resp.code, resp.message)
                }
                val total = if (knownSize > 0) knownSize
                else resp.body?.contentLength()?.takeIf { it > 0 } ?: -1L
                val pfd = contentResolver.openFileDescriptor(targetUri, "rw")
                    ?: return DownloadOutcome.IoError("cannot open Uri")
                pfd.use { fd ->
                    FileOutputStream(fd.fileDescriptor).use { fos ->
                        fos.channel.position(0)
                        val buf = ByteArray(BUFFER_SIZE)
                        var totalRead = 0L
                        var lastReportBytes = 0L
                        var lastReportTime = System.currentTimeMillis()
                        resp.body!!.byteStream().use { input ->
                            while (true) {
                                val n = input.read(buf)
                                if (n == -1) break
                                fos.write(buf, 0, n)
                                totalRead += n
                                val now = System.currentTimeMillis()
                                if (now - lastReportTime >= 250) {
                                    val deltaB = totalRead - lastReportBytes
                                    val deltaT = (now - lastReportTime).coerceAtLeast(1)
                                    onProgress(totalRead, total, deltaB * 1000 / deltaT)
                                    lastReportBytes = totalRead
                                    lastReportTime = now
                                }
                            }
                        }
                        onProgress(totalRead, total, 0L)
                        fos.fd.sync()
                    }
                }
                DownloadOutcome.Success(knownSize)
            }
        } catch (e: IOException) {
            if (call.isCanceled()) throw downloadCanceled(e)
            DownloadOutcome.IoError(e.message ?: "unknown")
        } finally {
            onCompletion.dispose()
        }
    }

    /** Stream-hash the target file with MD5 (lower-case hex). Null on failure. */
    suspend fun computeMd5(contentResolver: ContentResolver, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val md = MessageDigest.getInstance("MD5")
                contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        md.update(buf, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }

    sealed interface DownloadOutcome {
        data class Success(val totalSize: Long) : DownloadOutcome
        data class HttpError(val code: Int, val message: String) : DownloadOutcome
        data class IoError(val message: String) : DownloadOutcome
    }

    private data class Chunk(val index: Int, val start: Long, val end: Long) {
        val length: Long get() = end - start + 1
    }

    private data class SizeProbe(val totalSize: Long, val acceptsRanges: Boolean) {
        companion object {
            val Unknown = SizeProbe(-1L, acceptsRanges = false)
        }
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isActive) return
            if (call.isCanceled()) {
                cont.resumeWithException(downloadCanceled(e))
            } else {
                cont.resumeWithException(e)
            }
        }
        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

private fun downloadCanceled(cause: Throwable): CancellationException =
    CancellationException("Download canceled").apply { initCause(cause) }
