package com.desmond.ofd.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Singleton orchestrating the active download. Decides thread count from [DownloadPrefs],
 * spawns a foreground [DownloadService] so backgrounding doesn't kill connections, and
 * handles MD5-mismatch retries + cleanup on failure or cancel.
 */
object DownloadCoordinator {

    private const val MAX_MD5_RETRIES = 2

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val engine = DownloadEngine(httpClient)

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private var currentJob: Job? = null
    private var appContext: Context? = null
    @Volatile private var activeRunId = 0L

    fun start(context: Context, params: DownloadParams) {
        val app = context.applicationContext
        val oldParams = cancellableParamsOf(_state.value)
        val runId = nextRunId()
        currentJob?.cancel()
        currentJob = null
        oldParams?.let { deletePartialFile(app, it.targetUri) }

        appContext = app
        publish(runId, DownloadState.Active(params, 0L, params.expectedSize, 0L))
        startService(app)

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                runWithRetries(app, params, retriesLeft = MAX_MD5_RETRIES, runId = runId)
            } catch (e: CancellationException) {
                deletePartialFile(app, params.targetUri)
                publish(runId, DownloadState.Idle)
                throw e
            } catch (t: Throwable) {
                deletePartialFile(app, params.targetUri)
                if (isCurrent(runId) && isActive) {
                    _state.value = DownloadState.Failed(
                        params,
                        t.message ?: t::class.simpleName ?: "unknown",
                    )
                }
            } finally {
                if (isCurrent(runId)) currentJob = null
            }
        }
        currentJob = job
        job.start()
    }

    private suspend fun runWithRetries(
        context: Context,
        params: DownloadParams,
        retriesLeft: Int,
        runId: Long,
    ) {
        val prefs = DownloadPrefs(context)
        val configured = prefs.threadCount.value
        val threadOverride = if (configured == DownloadPrefs.AUTO) null else configured

        val outcome = engine.download(
            url = params.url,
            contentResolver = context.contentResolver,
            targetUri = params.targetUri,
            expectedSize = params.expectedSize,
            threadCountOverride = threadOverride,
            onProgress = { bytes, total, bps ->
                val effectiveTotal = if (total > 0) total else params.expectedSize
                publish(runId, DownloadState.Active(params, bytes, effectiveTotal, bps))
            },
        )
        when (outcome) {
            is DownloadEngine.DownloadOutcome.Success -> {
                if (params.expectedMd5.isNullOrBlank()) {
                    publish(runId, DownloadState.Completed(params, md5Matches = null))
                    return
                }
                publish(runId, DownloadState.Verifying(params))
                val computed = engine.computeMd5(context.contentResolver, params.targetUri)
                val matches = computed != null &&
                    computed.equals(params.expectedMd5, ignoreCase = true)
                if (matches) {
                    publish(runId, DownloadState.Completed(params, md5Matches = true))
                    return
                }
                // The retry rewrites the same SAF Uri; deleting it here can invalidate the handle.
                if (retriesLeft <= 0) {
                    deletePartialFile(context, params.targetUri)
                    publish(
                        runId,
                        DownloadState.Failed(
                            params,
                            "MD5 mismatch after $MAX_MD5_RETRIES retries (got ${computed ?: "null"}, expected ${params.expectedMd5})",
                        ),
                    )
                    return
                }
                publish(runId, DownloadState.Active(params, 0L, params.expectedSize, 0L))
                runWithRetries(context, params, retriesLeft - 1, runId)
            }
            is DownloadEngine.DownloadOutcome.HttpError -> {
                deletePartialFile(context, params.targetUri)
                publish(runId, DownloadState.Failed(params, "HTTP ${outcome.code}: ${outcome.message}"))
            }
            is DownloadEngine.DownloadOutcome.IoError -> {
                deletePartialFile(context, params.targetUri)
                publish(runId, DownloadState.Failed(params, "I/O: ${outcome.message}"))
            }
        }
    }

    fun cancel() {
        val params = cancellableParamsOf(_state.value)
        val context = appContext
        nextRunId()
        val job = currentJob
        currentJob = null
        _state.value = DownloadState.Idle
        job?.cancel()
        params?.let { p -> context?.let { ctx -> deletePartialFile(ctx, p.targetUri) } }
    }

    fun dismiss() {
        if (_state.value is DownloadState.Completed || _state.value is DownloadState.Failed) {
            _state.value = DownloadState.Idle
        }
    }

    private fun startService(context: Context) {
        val intent = Intent(context, DownloadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun deletePartialFile(context: Context, uri: Uri) {
        val resolver = context.contentResolver
        val deleted = runCatching { resolver.delete(uri, null, null) }.getOrDefault(0)
        if (deleted <= 0) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        }
    }

    @Synchronized
    private fun nextRunId(): Long {
        activeRunId += 1
        return activeRunId
    }

    private fun isCurrent(runId: Long): Boolean = activeRunId == runId

    private fun publish(runId: Long, state: DownloadState) {
        if (isCurrent(runId)) _state.value = state
    }

    private fun cancellableParamsOf(state: DownloadState): DownloadParams? = when (state) {
        is DownloadState.Active -> state.params
        is DownloadState.Verifying -> state.params
        else -> null
    }
}
