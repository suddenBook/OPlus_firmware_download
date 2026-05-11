package com.desmond.ofd.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Singleton orchestrating concurrent downloads. Each call to [start] returns a unique id;
 * the user-visible state is exposed as [jobs] (a list of `DownloadJob` items) so the UI can
 * show one card per active download.
 *
 * Concurrency rules:
 *  - Multiple distinct files may download concurrently, each with its own chunk pool.
 *  - A second download is rejected only when an active download already targets the same
 *    SAF Uri — that's the case where the original "permission denied" race appeared.
 *
 * Per-download lifecycle: Active → Verifying → Completed/Failed. Cancelling moves the entry
 * to Idle which removes it from [jobs]; dismissing a terminal state also removes it.
 */
object DownloadCoordinator {

    private const val MAX_MD5_RETRIES = 2
    private const val MAX_NETWORK_RETRIES = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workerThreadId = AtomicInteger()
    private val downloadDispatcher = Executors.newFixedThreadPool(DownloadEngine.MAX_CONCURRENT_CALLS) { runnable ->
        Thread(runnable, "OFD-download-${workerThreadId.incrementAndGet()}").apply {
            isDaemon = true
        }
    }.asCoroutineDispatcher()
    private val dispatcher: Dispatcher = Dispatcher().apply {
        // Bound global download concurrency. Extra downloads queue here instead of
        // consuming app-wide IO resources or opening unlimited sockets.
        maxRequests = DownloadEngine.MAX_CONCURRENT_CALLS
        maxRequestsPerHost = DownloadEngine.MAX_CONCURRENT_CALLS
    }
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        // Force HTTP/1.1: under HTTP/2, all chunk requests multiplex over one TCP connection,
        // sharing one congestion window. HTTP/1.1 gives one TCP per request — OPPO CDN throttles
        // ~0.5 MiB/s per connection, so per-chunk parallelism scales aggregate throughput.
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectionPool(ConnectionPool(DownloadEngine.MAX_CONCURRENT_CALLS * 2, 5, TimeUnit.MINUTES))
        .connectTimeout(15, TimeUnit.SECONDS)
        // In-task resume makes retries cheap, but Android can deprioritize background reads
        // long enough that 30 s produces false stalls. Prefer fewer false retries here; truly
        // dead connections still fail through the per-chunk stall counter and outer retries.
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val engine = DownloadEngine(httpClient, downloadDispatcher)

    data class DownloadJob(val id: String, val state: DownloadState)

    private val _jobs = MutableStateFlow<Map<String, DownloadJob>>(emptyMap())
    val jobs: StateFlow<Map<String, DownloadJob>> = _jobs.asStateFlow()

    private val coroutineJobs = ConcurrentHashMap<String, Job>()
    private val activeParams = ConcurrentHashMap<String, DownloadParams>()
    private val cancelledIds = ConcurrentHashMap.newKeySet<String>()
    private var appContext: Context? = null

    /**
     * Schedule a new download. Returns the new id, or `null` when an existing active download
     * is for the same firmware (matching MD5 when both provide it, otherwise matching
     * `displayName + expectedSize`) or targets the same SAF Uri (race-safety net).
     *
     * The signed download URL changes on every check, so URL-based dedup is useless — we key
     * on firmware identity instead.
     */
    fun start(context: Context, params: DownloadParams): String? {
        val collidingId = activeParams.entries.firstOrNull { (_, active) ->
            sameFirmware(active, params) || active.targetUri == params.targetUri
        }
        if (collidingId != null) return null

        val app = context.applicationContext
        appContext = app
        val id = UUID.randomUUID().toString()
        // TEMP diagnostic: capture which CDN host serves which firmware so we can document
        // the speed-by-host pattern. Remove once Manual page copy is finalised.
        val host = runCatching { java.net.URI(params.url).host }.getOrNull()
        android.util.Log.d("OFD-DL", "host=$host file=${params.displayName}")
        cancelledIds.remove(id)
        activeParams[id] = params
        update(id, DownloadState.Active(params, 0L, params.expectedSize, 0L))
        startService(app)

        coroutineJobs[id] = scope.launch {
            try {
                runWithRetries(
                    context = app,
                    id = id,
                    params = params,
                    md5RetriesLeft = MAX_MD5_RETRIES,
                    networkRetriesLeft = MAX_NETWORK_RETRIES,
                )
            } catch (e: CancellationException) {
                deletePartialFile(app, params.targetUri)
                remove(id)
                throw e
            } catch (t: Throwable) {
                deletePartialFile(app, params.targetUri)
                if (isActive) {
                    update(
                        id,
                        DownloadState.Failed(
                            params,
                            formatThrowable("download coordinator", params.targetUri, t),
                        ),
                    )
                }
            } finally {
                coroutineJobs.remove(id)
                activeParams.remove(id)
                cancelledIds.remove(id)
            }
        }
        return id
    }

    /** Cancel an in-flight download by id. */
    fun cancel(id: String) {
        val job = coroutineJobs[id]
        val params = activeParams[id] ?: activeParamsOf(_jobs.value[id]?.state)
        android.util.Log.d("OFD-DL", "cancel id=${id.take(8)} jobActive=${job?.isActive}")
        cancelledIds += id
        engine.cancel(id)
        job?.cancel(CancellationException("User cancelled"))
        remove(id)
        // Best-effort: if there are no other active downloads, evict idle pooled sockets
        // so the OS network indicator clears quickly. Active calls were closed above.
        if (coroutineJobs.entries.none { (otherId, otherJob) -> otherId != id && otherJob.isActive }) {
            runCatching { httpClient.connectionPool.evictAll() }
        }
        if (job == null) {
            params?.let { p -> appContext?.let { ctx -> deletePartialFile(ctx, p.targetUri) } }
            activeParams.remove(id)
            cancelledIds.remove(id)
        }
    }

    /** Remove a terminal (Completed/Failed) entry from the visible list. */
    fun dismiss(id: String) {
        val state = _jobs.value[id]?.state ?: return
        if (state is DownloadState.Completed || state is DownloadState.Failed) {
            remove(id)
        }
    }

    private suspend fun runWithRetries(
        context: Context,
        id: String,
        params: DownloadParams,
        md5RetriesLeft: Int,
        networkRetriesLeft: Int,
    ) {
        val outcome = engine.download(
            downloadId = id,
            url = params.url,
            contentResolver = context.contentResolver,
            targetUri = params.targetUri,
            expectedSize = params.expectedSize,
            onProgress = { bytes, total, bps ->
                val effectiveTotal = if (total > 0) total else params.expectedSize
                update(id, DownloadState.Active(params, bytes, effectiveTotal, bps))
            },
        )
        when (outcome) {
            is DownloadEngine.DownloadOutcome.Success -> {
                if (params.expectedMd5.isNullOrBlank()) {
                    update(id, DownloadState.Completed(params, md5Matches = null))
                    return
                }
                update(id, DownloadState.Verifying(params))
                val computed = engine.computeMd5(context.contentResolver, params.targetUri)
                val matches = computed != null && computed.equals(params.expectedMd5, ignoreCase = true)
                if (matches) {
                    update(id, DownloadState.Completed(params, md5Matches = true))
                    return
                }
                if (md5RetriesLeft <= 0) {
                    deletePartialFile(context, params.targetUri)
                    update(
                        id,
                        DownloadState.Failed(
                            params,
                            "MD5 mismatch after $MAX_MD5_RETRIES retries (got ${computed ?: "null"}, expected ${params.expectedMd5})",
                        ),
                    )
                    return
                }
                val resetError = resetPartialFile(context, params.targetUri, "reset before MD5 retry")
                if (resetError != null) {
                    update(
                        id,
                        DownloadState.Failed(
                            params,
                            "Could not reset target file before MD5 retry.\n" +
                                resetError +
                                "\nPrevious MD5 result: got ${computed ?: "null"}, expected ${params.expectedMd5}",
                        ),
                    )
                    return
                }
                update(id, DownloadState.Active(params, 0L, params.expectedSize, 0L))
                runWithRetries(context, id, params, md5RetriesLeft - 1, networkRetriesLeft)
            }
            is DownloadEngine.DownloadOutcome.IoError -> {
                val active = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true
                android.util.Log.d("OFD-DL", "IoError id=${id.take(8)} retriesLeft=$networkRetriesLeft isActive=$active")
                if (!active) {
                    throw CancellationException("Download cancelled")
                }
                if (networkRetriesLeft <= 0) {
                    deletePartialFile(context, params.targetUri)
                    update(id, DownloadState.Failed(params, "I/O after retries: ${outcome.message}"))
                    return
                }
                val resetError = resetPartialFile(context, params.targetUri, "reset before I/O retry")
                if (resetError != null) {
                    update(
                        id,
                        DownloadState.Failed(
                            params,
                            "Could not reset target file before retry.\n" +
                                resetError +
                                "\nPrevious error:\n${outcome.message}",
                        ),
                    )
                    return
                }
                val attempt = MAX_NETWORK_RETRIES - networkRetriesLeft + 1
                delay(1000L * attempt) // 1 s, 2 s
                update(id, DownloadState.Active(params, 0L, params.expectedSize, 0L))
                runWithRetries(context, id, params, md5RetriesLeft, networkRetriesLeft - 1)
            }
            is DownloadEngine.DownloadOutcome.HttpError -> {
                val transient = outcome.code in 500..599 ||
                    outcome.code == 408 || outcome.code == 429
                val active = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isActive == true
                if (!active) {
                    throw CancellationException("Download cancelled")
                }
                if (transient && networkRetriesLeft > 0 && active) {
                    val resetError = resetPartialFile(context, params.targetUri, "reset before HTTP retry")
                    if (resetError != null) {
                        update(
                            id,
                            DownloadState.Failed(
                                params,
                                "Could not reset target file before HTTP retry.\n" +
                                    resetError +
                                    "\nPrevious HTTP error: ${outcome.code} ${outcome.message}",
                            ),
                        )
                        return
                    }
                    val attempt = MAX_NETWORK_RETRIES - networkRetriesLeft + 1
                    delay(2000L * attempt) // 2 s, 4 s
                    update(id, DownloadState.Active(params, 0L, params.expectedSize, 0L))
                    runWithRetries(context, id, params, md5RetriesLeft, networkRetriesLeft - 1)
                } else {
                    deletePartialFile(context, params.targetUri)
                    val hint = if (outcome.code == 403 || outcome.code == 410) {
                        " (URL may have expired, re-run Check for firmware)"
                    } else ""
                    update(id, DownloadState.Failed(params, "HTTP ${outcome.code}: ${outcome.message}$hint"))
                }
            }
        }
    }

    private fun update(id: String, state: DownloadState) {
        if (id in cancelledIds) return
        _jobs.update { current ->
            current + (id to DownloadJob(id, state))
        }
        if (state !is DownloadState.Active) {
            android.util.Log.d("OFD-DL", "transition id=${id.take(8)} state=${state::class.simpleName}")
        }
    }

    private fun remove(id: String) {
        _jobs.update { it - id }
    }

    private fun startService(context: Context) {
        // minSdk = 33; startForegroundService is required.
        context.startForegroundService(Intent(context, DownloadService::class.java))
    }

    private fun deletePartialFile(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val deleted = runCatching { resolver.delete(uri, null, null) }.getOrDefault(0)
        if (deleted <= 0) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        }
    }

    private fun resetPartialFile(context: Context, uri: Uri, stage: String): String? {
        val resolver = context.contentResolver
        return runCatching {
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { fos ->
                    fos.channel.truncate(0)
                    fos.fd.sync()
                }
            } ?: error("No file descriptor")
        }.exceptionOrNull()?.let { formatThrowable(stage, uri, it) }
    }

    private fun activeParamsOf(state: DownloadState?): DownloadParams? = when (state) {
        is DownloadState.Active -> state.params
        is DownloadState.Verifying -> state.params
        else -> null
    }

    private fun sameFirmware(a: DownloadParams, b: DownloadParams): Boolean {
        // Strongest signal: matching server-supplied MD5.
        if (!a.expectedMd5.isNullOrBlank() && !b.expectedMd5.isNullOrBlank()) {
            return a.expectedMd5.equals(b.expectedMd5, ignoreCase = true)
        }
        // Fallback: matching display name (model + version) and expected file size.
        return a.displayName == b.displayName && a.expectedSize == b.expectedSize
    }

    // Tiny inline util because kotlinx.coroutines.flow.update isn't imported above; we want
    // the same atomic semantics without bringing in the full imports.
    private inline fun <T> MutableStateFlow<T>.update(block: (T) -> T) {
        while (true) {
            val current = value
            if (compareAndSet(current, block(current))) return
        }
    }
}
