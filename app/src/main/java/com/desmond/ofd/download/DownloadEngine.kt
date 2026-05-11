package com.desmond.ofd.download

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Multi-threaded HTTP downloader writing to a SAF [Uri] via random-offset writes.
 *
 *  Flow:
 *   1. Probe the URL with `Range: bytes=0-0` to learn the total size.
 *   2. Pre-allocate the output file by writing one byte at the last offset.
 *   3. Split the file into up to [CHUNKS_PER_DOWNLOAD] chunks and download them concurrently.
 *      Each chunk opens its own [ParcelFileDescriptor], seeks to the chunk's start,
 *      and writes its bytes — Linux/Android FS handles concurrent positional writes fine.
 *   4. A ticker emits aggregated progress every 250 ms.
 *   5. Cancellation: [cancel] closes every active OkHttp [Call] for the download id,
 *      including calls whose response body is already being read by coroutine workers.
 */
class DownloadEngine(
    private val httpClient: OkHttpClient,
    private val workerDispatcher: CoroutineDispatcher,
) {

    private val callsByDownload = ConcurrentHashMap<String, MutableSet<Call>>()

    fun cancel(downloadId: String) {
        callsByDownload[downloadId]?.forEach { call ->
            runCatching { call.cancel() }
        }
    }

    suspend fun download(
        downloadId: String,
        url: String,
        contentResolver: ContentResolver,
        targetUri: Uri,
        expectedSize: Long,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long, speedBps: Long) -> Unit,
    ): DownloadOutcome {
        val probe = probeSize(downloadId, url)
        val totalSize = probe.totalSize.takeIf { it > 0 } ?: expectedSize
        if (totalSize <= 0) {
            return singleThreadedDownload(
                downloadId, url, contentResolver, targetUri, onProgress,
            )
        }
        if (!probe.acceptsRanges) {
            return singleThreadedDownload(
                downloadId, url, contentResolver, targetUri, onProgress, knownSize = totalSize,
            )
        }

        val threadCount = fixedThreadCount(totalSize)
        if (threadCount <= 1) {
            return singleThreadedDownload(
                downloadId, url, contentResolver, targetUri, onProgress, knownSize = totalSize,
            )
        }

        try {
            withContext(workerDispatcher) {
                preallocate(contentResolver, targetUri, totalSize)
            }
        } catch (t: Throwable) {
            // Pre-allocation isn't strictly required; positional writes will extend the file.
        }

        val downloaded = AtomicLong(0L)
        try {
            coroutineScope {
                // Keep progress reporting independent from the bounded download worker pool.
                val ticker = launch(Dispatchers.Default) {
                    tickProgress(downloaded, totalSize, onProgress)
                }
                try {
                    val chunks = splitChunks(totalSize, threadCount)
                    chunks.map { chunk ->
                        async(workerDispatcher) {
                            downloadChunkWithRetry(
                                downloadId = downloadId,
                                url = url,
                                contentResolver = contentResolver,
                                targetUri = targetUri,
                                chunk = chunk,
                                downloaded = downloaded,
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
            return DownloadOutcome.IoError(formatThrowable("parallel download", targetUri, e))
        } catch (e: SecurityException) {
            return DownloadOutcome.IoError(formatThrowable("parallel download", targetUri, e))
        }
        return DownloadOutcome.Success(totalSize)
    }

    private suspend fun probeSize(downloadId: String, url: String): SizeProbe = withContext(workerDispatcher) {
        val req = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .tag(downloadId)
            .build()
        val call = httpClient.newCall(req)
        val unregister = registerCall(downloadId, call)
        try {
            runCatching {
                call.await().use { resp ->
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
        } finally {
            unregister()
        }
    }

    private fun fixedThreadCount(totalSize: Long): Int {
        val sizeMb = totalSize / (1L shl 20)
        return when {
            sizeMb < 16 -> 1
            sizeMb < 128 -> 4
            sizeMb < 1024 -> 16
            else -> CHUNKS_PER_DOWNLOAD
        }.coerceAtMost(totalSize.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .coerceAtLeast(1)
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

    /**
     * Plain (non-receiver) suspend function so `isActive` unambiguously refers to the calling
     * coroutine's job, not whichever `CoroutineScope` happens to be in implicit scope.
     * `delay(...)` itself throws on cancellation, so the loop naturally exits when the
     * surrounding `launch` is cancelled in the `finally` block of [download].
     */
    private suspend fun tickProgress(
        downloaded: AtomicLong,
        totalSize: Long,
        onProgress: suspend (Long, Long, Long) -> Unit,
    ) {
        var lastBytes = 0L
        var lastTimeMs = System.currentTimeMillis()
        while (currentCoroutineContext().isActive) {
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

    /**
     * Wraps [downloadChunk] with retry logic. A single chunk's transient I/O failure (e.g.
     * intermittent connectivity) no longer aborts the whole download. Cancellation still
     * propagates because we re-throw `CancellationException` and check `isActive`.
     */
    private suspend fun downloadChunkWithRetry(
        downloadId: String,
        url: String,
        contentResolver: ContentResolver,
        targetUri: Uri,
        chunk: Chunk,
        downloaded: AtomicLong,
        maxAttempts: Int = 3,
    ) {
        var attempt = 0
        var lastError: IOException? = null
        // Track this chunk's contribution so a retry can rewind the global counter.
        var contributed = 0L
        while (attempt < maxAttempts) {
            // Honour cancellation BEFORE another retry — when the coordinator cancels the
            // download, we want chunks to bail out instead of opening fresh connections.
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("Chunk ${chunk.index} cancelled before retry")
            }
            try {
                downloadChunk(
                    downloadId = downloadId,
                    url = url,
                    contentResolver = contentResolver,
                    targetUri = targetUri,
                    chunk = chunk,
                    onChunkBytes = { delta ->
                        downloaded.addAndGet(delta)
                        contributed += delta
                    },
                )
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive) {
                    // Re-classify socket-closed-from-cancel as cancellation so the parent
                    // coroutineScope sees a normal cancel, not a child failure.
                    throw CancellationException("Chunk ${chunk.index} cancelled mid-read").apply { initCause(e) }
                }
                // Rewind global counter so the retry doesn't double-count this chunk's bytes.
                downloaded.addAndGet(-contributed)
                contributed = 0L
                lastError = e
                attempt += 1
                if (attempt < maxAttempts) delay(500L * attempt) // 500 ms, 1000 ms
            }
        }
        throw lastError ?: IOException("Chunk ${chunk.index} failed without exception")
    }

    private suspend fun downloadChunk(
        downloadId: String,
        url: String,
        contentResolver: ContentResolver,
        targetUri: Uri,
        chunk: Chunk,
        onChunkBytes: (Long) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=${chunk.start}-${chunk.end}")
            .tag(downloadId)
            .build()
        val call = httpClient.newCall(request)
        val unregister = registerCall(downloadId, call)
        try {
            call.await().use { resp ->
                if (resp.code != 206) {
                    throw IOException("Chunk ${chunk.index} HTTP ${resp.code}")
                }
                try {
                    val pfd = contentResolver.openFileDescriptor(targetUri, "rw")
                        ?: throw IOException("Chunk ${chunk.index}: cannot open Uri")
                    pfd.use { fd ->
                        FileOutputStream(fd.fileDescriptor).use { fos ->
                            fos.channel.position(chunk.start)
                            val buf = ByteArray(BUFFER_SIZE)
                            resp.body!!.byteStream().use { input ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buf)
                                    if (n == -1) break
                                    fos.write(buf, 0, n)
                                    onChunkBytes(n.toLong())
                                }
                            }
                            fos.fd.sync()
                        }
                    }
                } catch (e: SecurityException) {
                    throw IOException(formatThrowable("chunk ${chunk.index} SAF write", targetUri, e), e)
                }
            }
        } catch (e: IOException) {
            if (call.isCanceled()) throw downloadCanceled(e)
            throw e
        } finally {
            unregister()
        }
    }

    private suspend fun singleThreadedDownload(
        downloadId: String,
        url: String,
        contentResolver: ContentResolver,
        targetUri: Uri,
        onProgress: suspend (Long, Long, Long) -> Unit,
        knownSize: Long = -1L,
    ): DownloadOutcome = withContext(workerDispatcher) {
        val request = Request.Builder()
            .url(url)
            .tag(downloadId)
            .build()
        val call = httpClient.newCall(request)
        val unregister = registerCall(downloadId, call)
        try {
            call.await().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext DownloadOutcome.HttpError(resp.code, resp.message)
                }
                val total = if (knownSize > 0) knownSize
                else resp.body?.contentLength()?.takeIf { it > 0 } ?: -1L
                try {
                    val pfd = contentResolver.openFileDescriptor(targetUri, "rw")
                        ?: return@withContext DownloadOutcome.IoError(
                            formatMessage("single download SAF open", targetUri, "cannot open Uri"),
                        )
                    pfd.use { fd ->
                        FileOutputStream(fd.fileDescriptor).use { fos ->
                            fos.channel.position(0)
                            val buf = ByteArray(BUFFER_SIZE)
                            var totalRead = 0L
                            var lastReportBytes = 0L
                            var lastReportTime = System.currentTimeMillis()
                            resp.body!!.byteStream().use { input ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
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
                } catch (e: SecurityException) {
                    return@withContext DownloadOutcome.IoError(formatThrowable("single download SAF write", targetUri, e))
                }
                DownloadOutcome.Success(total)
            }
        } catch (e: IOException) {
            if (call.isCanceled()) throw downloadCanceled(e)
            DownloadOutcome.IoError(formatThrowable("single download", targetUri, e))
        } catch (e: SecurityException) {
            DownloadOutcome.IoError(formatThrowable("single download", targetUri, e))
        } finally {
            unregister()
        }
    }

    /** Stream-hash the target file with MD5 (lower-case hex). Null on failure. */
    suspend fun computeMd5(contentResolver: ContentResolver, uri: Uri): String? =
        withContext(workerDispatcher) {
            runCatching {
                val md = MessageDigest.getInstance("MD5")
                contentResolver.openInputStream(uri)?.use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n == -1) break
                        md.update(buf, 0, n)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }

    private fun registerCall(downloadId: String, call: Call): () -> Unit {
        val calls = callsByDownload.computeIfAbsent(downloadId) {
            ConcurrentHashMap.newKeySet()
        }
        calls += call
        return {
            calls -= call
            if (calls.isEmpty()) {
                callsByDownload.remove(downloadId, calls)
            }
        }
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
        // 64 chunks per large download, with enough download-only workers for two full-speed
        // downloads at once. Extra downloads queue/share this pool without starving app IO.
        const val CHUNKS_PER_DOWNLOAD = 64
        const val MAX_CONCURRENT_CALLS = CHUNKS_PER_DOWNLOAD * 2
        private const val BUFFER_SIZE = 256 * 1024
    }
}

internal fun formatThrowable(stage: String, uri: Uri, throwable: Throwable): String =
    formatMessage(
        stage = stage,
        uri = uri,
        detail = "${throwable::class.simpleName ?: "Throwable"}: ${throwable.message ?: "(no message)"}",
    )

internal fun formatMessage(stage: String, uri: Uri, detail: String): String =
    "Stage: $stage\n" +
        "Uri: ${uri.scheme ?: "unknown"}://${uri.authority ?: "unknown"}\n" +
        detail

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
            if (cont.isActive) {
                cont.resume(response)
            } else {
                response.close()
            }
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

private fun downloadCanceled(cause: Throwable): CancellationException =
    CancellationException("Download canceled").apply { initCause(cause) }
