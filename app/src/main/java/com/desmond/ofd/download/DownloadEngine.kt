package com.desmond.ofd.download

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import com.desmond.ofd.firmware.validateFirmwareSize
import com.desmond.ofd.http.BROWSER_USER_AGENT
import com.desmond.ofd.http.parseContentRange
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
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
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
 *      Chunks share one SAF file descriptor and use positional writes. Some providers behave
 *      poorly when the same document is opened for write many times concurrently; actual write
 *      throughput can still be provider-limited even while network reads remain parallel.
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
        validateFirmwareSize(totalSize, expectedSize)?.let { problem ->
            return DownloadOutcome.IoError(formatMessage("download size probe", targetUri, problem))
        }
        if (totalSize <= 0) {
            return singleThreadedDownload(
                downloadId, url, contentResolver, targetUri, expectedSize, onProgress,
            )
        }
        if (!probe.acceptsRanges) {
            return singleThreadedDownload(
                downloadId, url, contentResolver, targetUri, expectedSize, onProgress, knownSize = totalSize,
            )
        }

        val threadCount = fixedThreadCount(totalSize)
        if (threadCount <= 1) {
            return singleThreadedDownload(
                downloadId, url, contentResolver, targetUri, expectedSize, onProgress, knownSize = totalSize,
            )
        }

        val downloaded = AtomicLong(0L)
        try {
            val pfd = withContext(workerDispatcher) {
                contentResolver.openFileDescriptor(targetUri, "rw")
                    ?: throw IOException("cannot open Uri for parallel SAF write")
            }
            pfd.use { fd ->
                FileOutputStream(fd.fileDescriptor).use { output ->
                    try {
                        preallocate(output.channel, totalSize)
                    } catch (e: IOException) {
                        // Pre-allocation isn't strictly required; positional writes will extend the file.
                        Log.w(TAG, "Target pre-allocation failed; continuing with positional writes", e)
                    } catch (e: SecurityException) {
                        Log.w(TAG, "Target pre-allocation was denied; continuing with positional writes", e)
                    }
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
                                        targetUri = targetUri,
                                        outputChannel = output.channel,
                                        chunk = chunk,
                                        totalSize = totalSize,
                                        downloaded = downloaded,
                                    )
                                }
                            }.awaitAll()
                        } finally {
                            ticker.cancel()
                        }
                        val finalBytes = downloaded.get()
                        // Defensive invariant: every chunk only returns after writing its full range.
                        if (finalBytes != totalSize) {
                            throw IOException("Downloaded byte count mismatch: got $finalBytes, expected $totalSize")
                        }
                        // Final progress emit so the UI snaps to 100 %.
                        onProgress(finalBytes, totalSize, 0L)
                    }
                    output.fd.sync()
                }
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
            .header("User-Agent", BROWSER_USER_AGENT)
            .tag(downloadId)
            .build()
        val call = httpClient.newCall(req)
        val unregister = registerCall(downloadId, call)
        try {
            runCatching {
                call.await().use { resp ->
                    when {
                        resp.code == 206 -> parseContentRange(resp.header("Content-Range"))
                            ?.let { SizeProbe(it.totalSize, acceptsRanges = true) }
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

    @Throws(IOException::class)
    private fun preallocate(channel: FileChannel, size: Long) {
        if (size > 0) {
            writeFullyAt(channel, ByteArray(1), length = 1, position = size - 1)
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
     * Wraps [downloadChunk] with in-task resume. On transient I/O failure, a chunk keeps the
     * bytes already written and requests only the remaining range. Only consecutive failures
     * that make no forward progress count against [maxStalledAttempts].
     */
    private suspend fun downloadChunkWithRetry(
        downloadId: String,
        url: String,
        targetUri: Uri,
        outputChannel: FileChannel,
        chunk: Chunk,
        totalSize: Long,
        downloaded: AtomicLong,
        maxStalledAttempts: Int = 3,
    ) {
        var bytesDone = 0L
        var stalledAttempts = 0
        var lastError: IOException? = null
        while (bytesDone < chunk.length) {
            // Honour cancellation BEFORE another retry — when the coordinator cancels the
            // download, we want chunks to bail out instead of opening fresh connections.
            if (!currentCoroutineContext().isActive) {
                throw CancellationException("Chunk ${chunk.index} cancelled before retry")
            }
            if (stalledAttempts >= maxStalledAttempts) break
            val attemptStartBytes = bytesDone
            try {
                downloadChunk(
                    downloadId = downloadId,
                    url = url,
                    targetUri = targetUri,
                    outputChannel = outputChannel,
                    chunk = chunk,
                    startOffset = chunk.start + bytesDone,
                    totalSize = totalSize,
                    onChunkBytes = { delta ->
                        bytesDone += delta
                        downloaded.addAndGet(delta)
                    },
                )
                // Defensive invariant: downloadChunk only returns after reading the requested range.
                if (bytesDone != chunk.length) {
                    throw IOException("Chunk ${chunk.index} stopped at $bytesDone of ${chunk.length} bytes")
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (!currentCoroutineContext().isActive) {
                    // Re-classify socket-closed-from-cancel as cancellation so the parent
                    // coroutineScope sees a normal cancel, not a child failure.
                    throw CancellationException("Chunk ${chunk.index} cancelled mid-read").apply { initCause(e) }
                }
                lastError = e
                if (bytesDone > attemptStartBytes) {
                    stalledAttempts = 0
                } else {
                    stalledAttempts += 1
                    if (stalledAttempts < maxStalledAttempts) {
                        delay(500L * stalledAttempts) // 500 ms, 1000 ms
                    }
                }
            }
        }
        throw lastError ?: IOException("Chunk ${chunk.index} failed at $bytesDone of ${chunk.length} bytes")
    }

    private suspend fun downloadChunk(
        downloadId: String,
        url: String,
        targetUri: Uri,
        outputChannel: FileChannel,
        chunk: Chunk,
        startOffset: Long,
        totalSize: Long,
        onChunkBytes: (Long) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$startOffset-${chunk.end}")
            .header("User-Agent", BROWSER_USER_AGENT)
            .tag(downloadId)
            .build()
        val call = httpClient.newCall(request)
        val unregister = registerCall(downloadId, call)
        try {
            call.await().use { resp ->
                if (resp.code != 206) {
                    throw IOException("Chunk ${chunk.index} HTTP ${resp.code}")
                }
                val contentRange = parseContentRange(resp.header("Content-Range"))
                    ?: throw IOException("Chunk ${chunk.index}: missing/invalid Content-Range")
                if (contentRange.start != startOffset ||
                    contentRange.end != chunk.end ||
                    contentRange.totalSize != totalSize
                ) {
                    throw IOException(
                        "Chunk ${chunk.index}: unexpected Content-Range " +
                            "${resp.header("Content-Range")} for bytes=$startOffset-${chunk.end}/$totalSize",
                    )
                }
                try {
                    val expectedBytes = contentRange.length
                    val buf = ByteArray(BUFFER_SIZE)
                    var bytesRead = 0L
                    val body = resp.body ?: throw IOException("Chunk ${chunk.index}: empty response body")
                    body.byteStream().use { input ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n == -1) break
                            if (bytesRead + n > expectedBytes) {
                                throw IOException(
                                    "Chunk ${chunk.index} exceeded expected length: " +
                                        "${bytesRead + n} > $expectedBytes",
                                )
                            }
                            writeFullyAt(outputChannel, buf, n, startOffset + bytesRead)
                            bytesRead += n
                            onChunkBytes(n.toLong())
                        }
                    }
                    if (bytesRead != expectedBytes) {
                        throw IOException("Chunk ${chunk.index} incomplete: got $bytesRead, expected $expectedBytes")
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
        expectedSize: Long,
        onProgress: suspend (Long, Long, Long) -> Unit,
        knownSize: Long = -1L,
    ): DownloadOutcome = withContext(workerDispatcher) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_USER_AGENT)
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
                else resp.body?.contentLength() ?: -1L
                validateFirmwareSize(total, expectedSize)?.let { problem ->
                    return@withContext DownloadOutcome.IoError(
                        formatMessage("single download size", targetUri, problem),
                    )
                }
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
                            val body = resp.body ?: return@withContext DownloadOutcome.IoError(
                                formatMessage("single download body", targetUri, "empty response body"),
                            )
                            body.byteStream().use { input ->
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
                            if (total > 0 && totalRead != total) {
                                return@withContext DownloadOutcome.IoError(
                                    formatMessage(
                                        "single download read",
                                        targetUri,
                                        "Downloaded byte count mismatch: got $totalRead, expected $total",
                                    ),
                                )
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

    private fun writeFullyAt(
        channel: FileChannel,
        bytes: ByteArray,
        length: Int,
        position: Long,
    ) {
        val buffer = ByteBuffer.wrap(bytes, 0, length)
        var offset = position
        while (buffer.hasRemaining()) {
            val written = channel.write(buffer, offset)
            if (written <= 0) throw IOException("FileChannel wrote $written bytes")
            offset += written
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
        private const val TAG = "OFD-DownloadEngine"
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
