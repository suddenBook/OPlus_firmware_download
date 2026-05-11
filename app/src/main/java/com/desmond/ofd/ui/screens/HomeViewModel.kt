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
                if (params.otaVersion.isBlank()) {
                    BackendOutcome.Skipped(backendMessage(R.string.backend_skip_ota_required_realme_ota))
                } else {
                    runRealmeOta(params.copy(beta = false))
                }
            }
            val betaDeferred = async {
                when {
                    params.imei0.isNullOrBlank() ->
                        BackendOutcome.Skipped(backendMessage(R.string.backend_skip_beta_imei_required))
                    params.otaVersion.isBlank() ->
                        BackendOutcome.Skipped(backendMessage(R.string.backend_skip_ota_required_realme_ota_beta))
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
        var lastLinkFailure: BackendOutcome.Failure? = null
        repeat(REALME_OTA_LINK_CHECK_ATTEMPTS) { attempt ->
            when (val r = realmeOtaClient.query(params)) {
                is OtaResult.Success -> {
                    when (val checked = verifyRealmeOtaSuccess(r)) {
                        is CheckedRealmeOta.Success -> return checked.outcome
                        is CheckedRealmeOta.Failure -> {
                            lastLinkFailure = checked.outcome
                            if (!checked.retryable || attempt == REALME_OTA_LINK_CHECK_ATTEMPTS - 1) {
                                return checked.outcome
                            }
                            delay(REALME_OTA_LINK_CHECK_RETRY_DELAYS_MS[attempt])
                        }
                    }
                }
                is OtaResult.HttpError -> return BackendOutcome.Failure(
                    backendMessage(
                        R.string.backend_error_http,
                        r.code.toString(),
                        r.errMsg ?: getApplication<Application>().getString(R.string.backend_error_no_message),
                    ),
                )
                is OtaResult.NetworkError -> return BackendOutcome.Failure(
                    backendMessage(
                        R.string.backend_error_network,
                        r.cause.message ?: r.cause::class.simpleName ?: getApplication<Application>().getString(R.string.backend_error_unknown),
                    ),
                )
                is OtaResult.CryptoError -> return BackendOutcome.Failure(
                    backendMessage(
                        R.string.backend_error_crypto,
                        r.cause.message ?: r.cause::class.simpleName ?: getApplication<Application>().getString(R.string.backend_error_unknown),
                    ),
                )
                is OtaResult.ContentError -> return BackendOutcome.Failure(
                    backendMessage(R.string.backend_error_content, r.checkFailReason),
                )
            }
        }
        return lastLinkFailure ?: BackendOutcome.Failure(backendMessage(R.string.backend_error_unknown))
    }

    private suspend fun verifyRealmeOtaSuccess(result: OtaResult.Success): CheckedRealmeOta {
        val packet = result.response.components.firstOrNull()?.componentPackets
            ?: return CheckedRealmeOta.Failure(
                BackendOutcome.Failure(backendMessage(R.string.backend_error_no_download_packet)),
                retryable = false,
            )
        val downloadUrl = packet.url.takeIf { it.isNotBlank() }
            ?: packet.manualUrl?.takeIf { it.isNotBlank() }
            ?: return CheckedRealmeOta.Failure(
                BackendOutcome.Failure(backendMessage(R.string.backend_error_no_download_url)),
                retryable = false,
            )

        val apiSize = packet.size.toLongOrNull()?.takeIf { it > 0 } ?: -1L

        return when (val probe = firmwareUrlProbe.probe(downloadUrl, expectedSize = apiSize)) {
            is FirmwareUrlProbeResult.Success -> CheckedRealmeOta.Success(
                BackendOutcome.Success(
                    versionName = result.response.versionName ?: result.response.realOtaVersion ?: "(unknown)",
                    realOtaVersion = result.response.realOtaVersion,
                    downloadUrl = downloadUrl,
                    sizeBytes = probe.totalSize,
                    md5 = packet.md5.takeIf { it.isNotBlank() } ?: probe.md5,
                    securityPatch = result.response.securityPatch,
                ),
            )
            is FirmwareUrlProbeResult.Failure -> CheckedRealmeOta.Failure(
                outcome = BackendOutcome.Failure(
                    if (probe.observedSize != null) {
                        backendMessage(
                            R.string.backend_error_invalid_firmware_size,
                            formatFirmwareBytes(probe.observedSize),
                        )
                    } else {
                        backendMessage(R.string.backend_error_download_link_unverified, probe.detail)
                    },
                ),
                retryable = probe.retryable,
            )
        }
    }

    private fun invalidRealmeSize(sizeBytes: Long, retryable: Boolean): CheckedRealmeOta.Failure =
        CheckedRealmeOta.Failure(
            outcome = BackendOutcome.Failure(
                backendMessage(
                    R.string.backend_error_invalid_firmware_size,
                    formatFirmwareBytes(sizeBytes),
                ),
            ),
            retryable = retryable,
        )

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
        private const val REALME_OTA_LINK_CHECK_ATTEMPTS = 3
        private val REALME_OTA_LINK_CHECK_RETRY_DELAYS_MS = longArrayOf(500L, 1500L)

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

private sealed interface CheckedRealmeOta {
    data class Success(val outcome: BackendOutcome.Success) : CheckedRealmeOta
    data class Failure(
        val outcome: BackendOutcome.Failure,
        val retryable: Boolean,
    ) : CheckedRealmeOta
}
