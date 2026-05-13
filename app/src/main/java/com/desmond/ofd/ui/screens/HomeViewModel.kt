package com.desmond.ofd.ui.screens

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.desmond.ofd.R
import com.desmond.ofd.backend.VersionResolver
import com.desmond.ofd.backend.danielspringer.DanielspringerCatalog
import com.desmond.ofd.backend.danielspringer.DanielspringerClient
import com.desmond.ofd.backend.realmeota.data.OtaRequestParams
import com.desmond.ofd.backend.realmeota.network.OtaResult
import com.desmond.ofd.backend.realmeota.network.RealmeOtaDownloadFailure
import com.desmond.ofd.backend.realmeota.network.RealmeOtaDownloadSelection
import com.desmond.ofd.backend.realmeota.network.RealmeOtaDownloadSelector
import com.desmond.ofd.backend.realmeota.network.RealmeOtaVersionCandidates
import com.desmond.ofd.backend.realmeota.network.RealmeOtaClient
import com.desmond.ofd.catalog.CatalogRepository
import com.desmond.ofd.catalog.DeviceCatalog
import com.desmond.ofd.catalog.DeviceEntry
import com.desmond.ofd.device.DeviceProps
import com.desmond.ofd.device.DeviceSnapshot
import com.desmond.ofd.download.DownloadCoordinator
import com.desmond.ofd.download.DownloadParams
import com.desmond.ofd.firmware.FirmwareUrlProbe
import com.desmond.ofd.firmware.FirmwareUrlProbeResult
import com.desmond.ofd.firmware.formatFirmwareBytes
import com.desmond.ofd.firmware.parseFirmwareUrlExpiresEpochSeconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface BackendMessage {
    data class Resource(
        @param:StringRes val resId: Int,
        val args: List<String> = emptyList(),
    ) : BackendMessage
    data class Raw(val value: String) : BackendMessage
}

sealed interface BackendOutcome {
    data class Success(
        /** Comparable display version, e.g. `PLK110_16.0.7.206(CN01)` (numeric, dotted). */
        val versionName: String,
        val downloadUrl: String,
        val sizeBytes: Long,
        val md5: String?,
        val securityPatch: String? = null,
        val expiresAtEpochSeconds: Long? = null,
        /**
         * Optional internal OPPO OTA version like `PLK110_11.A.63_0630_202605061316` —
         * carries the build timestamp but contains letters so it's NOT comparable. Used
         * for build-date extraction and display only.
         */
        val realOtaVersion: String? = null,
    ) : BackendOutcome
    data class Failure(val message: BackendMessage) : BackendOutcome
    /** Backend wasn't called because a prerequisite (OTA version, IMEI, …) was missing. */
    data class Skipped(val reason: BackendMessage) : BackendOutcome
    data object NotAttempted : BackendOutcome
}

sealed interface HomeUiState {
    data object Idle : HomeUiState
    data object Loading : HomeUiState
    data class Result(
        val marketingName: String,
        val realmeOtaStable: BackendOutcome,
        val realmeOtaBeta: BackendOutcome,
        val danielspringer: BackendOutcome,
        /** Identifier of the source that won the version comparison; null if no Success. */
        val winnerLabel: String?,
        val winnerOutcome: BackendOutcome.Success?,
    ) : HomeUiState
    data class Error(val message: String) : HomeUiState
}

object BackendLabels {
    const val DANIELSPRINGER = "danielspringer.at"
    const val REALME_OTA_STABLE = "realme-ota (stable)"
    const val REALME_OTA_BETA = "realme-ota (beta)"
}

class HomeViewModel(
    application: Application,
    private val realmeOtaClient: RealmeOtaClient = RealmeOtaClient(),
    private val danielspringerClient: DanielspringerClient = DanielspringerClient(),
    private val getSnapshot: () -> DeviceSnapshot = { DeviceProps.snapshot(useShellFallback = true) },
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()
    private val firmwareUrlProbe = FirmwareUrlProbe()

    private val _catalog = MutableStateFlow<List<DeviceEntry>>(emptyList())
    val catalog: StateFlow<List<DeviceEntry>> = _catalog.asStateFlow()

    /**
     * Tracks the in-flight check coroutine so [reset] (e.g. on Auto/Manual tab switch) can
     * cancel it before it overwrites the freshly-cleared state with a stale Result.
     */
    private var checkJob: Job? = null

    init {
        viewModelScope.launch {
            _catalog.value = CatalogRepository(getApplication()).allDevices()
        }
    }

    fun checkAuto(imei: String? = null) {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _state.value = HomeUiState.Loading
            val snapshot = readAutoSnapshot()
                ?: return@launch fail(R.string.error_read_device_properties)
            val params = OtaRequestParams(
                model = snapshot.productName,
                otaVersion = snapshot.otaVersion.orEmpty(),
                ruiVersion = snapshot.ruiVersion,
                nvIdentifier = snapshot.nvId,
                region = snapshot.region,
                imei0 = imei,
            )
            runCheck(params)
        }
    }

    fun checkManual(params: OtaRequestParams) {
        checkJob?.cancel()
        checkJob = viewModelScope.launch {
            _state.value = HomeUiState.Loading
            runCheck(params)
        }
    }

    fun reset() {
        checkJob?.cancel()
        checkJob = null
        _state.value = HomeUiState.Idle
    }

    /**
     * Returns the new download id when scheduled, or `null` when an active download already
     * matches this firmware (same MD5, or same `displayName + size` when MD5 is unavailable).
     */
    fun startDownload(
        targetUri: Uri,
        outcome: BackendOutcome.Success,
        displayName: String,
    ): String? = DownloadCoordinator.start(
        context = getApplication(),
        params = DownloadParams(
            url = outcome.downloadUrl,
            targetUri = targetUri,
            displayName = displayName,
            expectedSize = outcome.sizeBytes,
            expectedMd5 = outcome.md5,
        ),
    )

    private suspend fun runCheck(params: OtaRequestParams) {
        val (stable, beta, springer) = coroutineScope {
            val stableDeferred = async {
                runRealmeOta(params.copy(beta = false))
            }
            val betaDeferred = async {
                when {
                    params.imei0.isNullOrBlank() ->
                        BackendOutcome.Skipped(backendMessage(R.string.backend_skip_beta_imei_required))
                    else -> runRealmeOta(params.copy(beta = true))
                }
            }
            val springerDeferred = async { runDanielspringer(params) }
            Triple(stableDeferred.await(), betaDeferred.await(), springerDeferred.await())
        }
        val marketingName = DeviceCatalog.marketingName(getApplication(), params.model)
            ?: params.model
        val winner = pickWinner(
            listOf(
                BackendLabels.DANIELSPRINGER to springer,
                BackendLabels.REALME_OTA_STABLE to stable,
                BackendLabels.REALME_OTA_BETA to beta,
            )
        )
        _state.value = HomeUiState.Result(
            marketingName = marketingName,
            realmeOtaStable = stable,
            realmeOtaBeta = beta,
            danielspringer = springer,
            winnerLabel = winner?.first,
            winnerOutcome = winner?.second,
        )
    }

    private suspend fun readAutoSnapshot(): DeviceSnapshot? {
        var last: DeviceSnapshot? = null
        repeat(AUTO_SNAPSHOT_ATTEMPTS) { attempt ->
            val snapshot = withContext(Dispatchers.IO) {
                runCatching { getSnapshot() }.getOrNull()
            }
            if (snapshot != null) {
                last = snapshot
                if (!snapshot.otaVersion.isNullOrBlank()) return snapshot
            }
            if (attempt < AUTO_SNAPSHOT_ATTEMPTS - 1) {
                delay(AUTO_SNAPSHOT_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return last
    }

    private fun fail(@StringRes messageResId: Int) {
        _state.value = HomeUiState.Error(getApplication<Application>().getString(messageResId))
    }

    private suspend fun runRealmeOta(params: OtaRequestParams): BackendOutcome {
        var bestOutcome: BackendOutcome? = null
        for (candidate in realmeOtaQueryCandidates(params)) {
            val outcome = runRealmeOtaOnce(candidate)
            if (outcome is BackendOutcome.Success) return outcome
            bestOutcome = preferRealmeFailure(bestOutcome, outcome)
        }
        return bestOutcome ?: BackendOutcome.Failure(backendMessage(R.string.backend_error_unknown))
    }

    private suspend fun runRealmeOtaOnce(params: OtaRequestParams): BackendOutcome =
        when (val r = realmeOtaClient.query(params)) {
            is OtaResult.Success -> preflightRealmeOtaSuccess(r)
            is OtaResult.HttpError -> BackendOutcome.Failure(
                backendMessage(
                    R.string.backend_error_http,
                    r.code.toString(),
                    r.errMsg ?: getApplication<Application>().getString(R.string.backend_error_no_message),
                ),
            )
            is OtaResult.NetworkError -> BackendOutcome.Failure(
                backendMessage(
                    R.string.backend_error_network,
                    r.cause.message ?: r.cause::class.simpleName ?: getApplication<Application>().getString(R.string.backend_error_unknown),
                ),
            )
            is OtaResult.CryptoError -> BackendOutcome.Failure(
                backendMessage(
                    R.string.backend_error_crypto,
                    r.cause.message ?: r.cause::class.simpleName ?: getApplication<Application>().getString(R.string.backend_error_unknown),
                ),
            )
            is OtaResult.ContentError -> BackendOutcome.Failure(
                backendMessage(R.string.backend_error_content, r.checkFailReason),
            )
        }

    private suspend fun preflightRealmeOtaSuccess(result: OtaResult.Success): BackendOutcome {
        return when (val selected = RealmeOtaDownloadSelector.select(result.response)) {
            is RealmeOtaDownloadSelection.Success -> preflightSelectedRealmeOta(selected)
            is RealmeOtaDownloadSelection.Failure -> mapRealmeSelectionFailure(selected)
        }
    }

    private suspend fun preflightSelectedRealmeOta(selected: RealmeOtaDownloadSelection.Success): BackendOutcome {
        var lastFailure: FirmwareUrlProbeResult.Failure? = null
        for (url in selected.downloadUrls) {
            when (val probe = firmwareUrlProbe.probe(url, expectedSize = selected.sizeBytes)) {
                is FirmwareUrlProbeResult.Success -> return BackendOutcome.Success(
                    versionName = selected.versionName,
                    realOtaVersion = selected.realOtaVersion,
                    downloadUrl = probe.resolvedUrl,
                    sizeBytes = probe.totalSize,
                    md5 = selected.md5 ?: probe.md5,
                    securityPatch = selected.securityPatch,
                    expiresAtEpochSeconds = parseFirmwareUrlExpiresEpochSeconds(probe.resolvedUrl),
                )
                is FirmwareUrlProbeResult.Failure -> lastFailure = probe
            }
        }
        val failure = lastFailure
            ?: return BackendOutcome.Failure(backendMessage(R.string.backend_error_no_download_url))
        return BackendOutcome.Failure(
            when {
                failure.rejectionCode != null -> backendMessage(
                    R.string.backend_error_antileech_rejected,
                    failure.rejectionCode,
                )
                failure.observedSize != null -> backendMessage(
                    R.string.backend_error_invalid_firmware_size,
                    formatFirmwareBytes(failure.observedSize),
                )
                else -> backendMessage(
                    R.string.backend_error_download_link_unverified,
                    failure.detail,
                )
            },
        )
    }

    private fun mapRealmeSelectionFailure(selected: RealmeOtaDownloadSelection.Failure): BackendOutcome.Failure =
        BackendOutcome.Failure(
            when (selected.reason) {
                RealmeOtaDownloadFailure.NO_DOWNLOAD_PACKET ->
                    backendMessage(R.string.backend_error_no_download_packet)
                RealmeOtaDownloadFailure.NO_DOWNLOAD_URL ->
                    backendMessage(R.string.backend_error_no_download_url)
                RealmeOtaDownloadFailure.INVALID_FIRMWARE_SIZE ->
                    backendMessage(
                        R.string.backend_error_invalid_firmware_size,
                        formatFirmwareBytes(selected.observedSize ?: -1L),
                    )
            },
        )

    private fun realmeOtaQueryCandidates(params: OtaRequestParams): List<OtaRequestParams> {
        return RealmeOtaVersionCandidates.versions(params.model, params.otaVersion)
            .map { params.copy(otaVersion = it) }
    }

    private fun preferRealmeFailure(current: BackendOutcome?, candidate: BackendOutcome): BackendOutcome {
        if (current !is BackendOutcome.Failure) return candidate
        if (candidate !is BackendOutcome.Failure) return current
        return if (realmeFailurePriority(candidate) > realmeFailurePriority(current)) candidate else current
    }

    private fun realmeFailurePriority(outcome: BackendOutcome.Failure): Int {
        val message = outcome.message
        if (message is BackendMessage.Resource) {
            return when (message.resId) {
                R.string.backend_error_antileech_rejected -> 60
                R.string.backend_error_download_link_unverified -> 50
                R.string.backend_error_invalid_firmware_size -> 45
                R.string.backend_error_network -> 40
                R.string.backend_error_crypto -> 35
                R.string.backend_error_no_download_packet,
                R.string.backend_error_no_download_url
                -> 30
                R.string.backend_error_content -> 20
                R.string.backend_error_http -> {
                    val code = message.args.firstOrNull()
                    val detail = message.args.getOrNull(1).orEmpty()
                    if (code == "2004" && detail.contains("Result is empty", ignoreCase = true)) 10 else 25
                }
                else -> 20
            }
        }
        return 20
    }

    private suspend fun runDanielspringer(params: OtaRequestParams): BackendOutcome {
        return runCatching {
            val catalog = catalogCache ?: danielspringerClient.fetchCatalog().also { catalogCache = it }
            val res = danielspringerClient.fetchLatestUrlForModel(
                catalog,
                model = params.model,
                region = params.region,
            ) ?: return BackendOutcome.Failure(backendMessage(R.string.backend_error_model_not_in_catalog))
            BackendOutcome.Success(
                // Use displayName (comparable dotted-numeric format) for VersionResolver.
                versionName = res.displayName,
                realOtaVersion = res.realOtaVersion,
                downloadUrl = res.downloadUrl,
                sizeBytes = res.sizeBytes,
                md5 = res.md5,
                securityPatch = res.securityPatch,
                expiresAtEpochSeconds = res.expiresAtEpochSeconds.takeIf { it > 0 },
            )
        }.getOrElse {
            BackendOutcome.Failure(
                BackendMessage.Raw(it.message ?: it::class.simpleName ?: getApplication<Application>().getString(R.string.backend_error_unknown)),
            )
        }
    }

    private fun pickWinner(
        candidates: List<Pair<String, BackendOutcome>>,
    ): Pair<String, BackendOutcome.Success>? {
        val successes = candidates.mapNotNull { (label, outcome) ->
            (outcome as? BackendOutcome.Success)?.let { label to it }
        }
        if (successes.isEmpty()) return null
        return successes.reduce { acc, cand ->
            if (VersionResolver.compare(cand.second.versionName, acc.second.versionName) > 0) cand
            else acc
        }
    }

    @Volatile private var catalogCache: DanielspringerCatalog? = null

    companion object {
        private const val AUTO_SNAPSHOT_ATTEMPTS = 3
        private const val AUTO_SNAPSHOT_RETRY_DELAY_MS = 150L

        private fun backendMessage(@StringRes resId: Int, vararg args: String): BackendMessage =
            BackendMessage.Resource(resId, args.toList())

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
                val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("HomeViewModel requires Application")
                return HomeViewModel(app) as T
            }
        }
    }
}
