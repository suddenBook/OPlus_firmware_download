package com.desmond.ofd.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.desmond.ofd.backend.VersionResolver
import com.desmond.ofd.backend.danielspringer.DanielspringerCatalog
import com.desmond.ofd.backend.danielspringer.DanielspringerClient
import com.desmond.ofd.backend.realmeota.data.OtaRequestParams
import android.net.Uri
import com.desmond.ofd.backend.realmeota.network.OtaResult
import com.desmond.ofd.backend.realmeota.network.RealmeOtaClient
import com.desmond.ofd.catalog.DeviceCatalog
import com.desmond.ofd.device.DeviceProps
import com.desmond.ofd.device.DeviceSnapshot
import com.desmond.ofd.download.DownloadCoordinator
import com.desmond.ofd.download.DownloadParams
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    data class Failure(val message: String) : BackendOutcome
    /** Backend wasn't called because a prerequisite (OTA version, IMEI, …) was missing. */
    data class Skipped(val reason: String) : BackendOutcome
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
    private val getSnapshot: () -> DeviceSnapshot = { DeviceProps.snapshot() },
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<HomeUiState>(HomeUiState.Idle)
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    fun checkAuto(imei: String? = null) {
        if (_state.value is HomeUiState.Loading) return
        viewModelScope.launch {
            _state.value = HomeUiState.Loading
            val snapshot = runCatching { getSnapshot() }.getOrNull()
                ?: return@launch fail("Couldn't read device properties.")
            val ota = snapshot.otaVersion
                ?: return@launch fail("Couldn't read OTA version. Try Manual mode.")
            val params = OtaRequestParams(
                model = snapshot.productName,
                otaVersion = ota,
                ruiVersion = snapshot.ruiVersion,
                nvIdentifier = snapshot.nvId,
                region = snapshot.region,
                imei0 = imei,
            )
            runCheck(params)
        }
    }

    fun checkManual(params: OtaRequestParams) {
        if (_state.value is HomeUiState.Loading) return
        viewModelScope.launch {
            _state.value = HomeUiState.Loading
            runCheck(params)
        }
    }

    fun reset() {
        _state.value = HomeUiState.Idle
    }

    fun startDownload(targetUri: Uri, outcome: BackendOutcome.Success, displayName: String) {
        DownloadCoordinator.start(
            context = getApplication(),
            params = DownloadParams(
                url = outcome.downloadUrl,
                targetUri = targetUri,
                displayName = displayName,
                expectedSize = outcome.sizeBytes,
                expectedMd5 = outcome.md5,
            ),
        )
    }

    private suspend fun runCheck(params: OtaRequestParams) {
        val (stable, beta, springer) = coroutineScope {
            val stableDeferred = async {
                if (params.otaVersion.isBlank()) {
                    BackendOutcome.Skipped("OTA version required for realme-ota")
                } else {
                    runRealmeOta(params.copy(beta = false))
                }
            }
            val betaDeferred = async {
                when {
                    params.imei0.isNullOrBlank() ->
                        BackendOutcome.Skipped("Add IMEI to try the beta channel")
                    params.otaVersion.isBlank() ->
                        BackendOutcome.Skipped("OTA version required for realme-ota beta")
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

    private fun fail(message: String) {
        _state.value = HomeUiState.Error(message)
    }

    private suspend fun runRealmeOta(params: OtaRequestParams): BackendOutcome {
        return when (val r = realmeOtaClient.query(params)) {
            is OtaResult.Success -> {
                val packet = r.response.components.firstOrNull()?.componentPackets
                    ?: return BackendOutcome.Failure("realme-ota returned no download packet")
                val downloadUrl = packet.url.takeIf { it.isNotBlank() }
                    ?: packet.manualUrl?.takeIf { it.isNotBlank() }
                    ?: return BackendOutcome.Failure("realme-ota returned no download URL")
                BackendOutcome.Success(
                    versionName = r.response.versionName ?: r.response.realOtaVersion ?: "(unknown)",
                    realOtaVersion = r.response.realOtaVersion,
                    downloadUrl = downloadUrl,
                    sizeBytes = packet.size.toLongOrNull() ?: 0L,
                    md5 = packet.md5,
                    securityPatch = r.response.securityPatch,
                )
            }
            is OtaResult.HttpError -> BackendOutcome.Failure("HTTP ${r.code}: ${r.errMsg ?: "(no msg)"}")
            is OtaResult.NetworkError -> BackendOutcome.Failure("Network: ${r.cause.message ?: r.cause::class.simpleName}")
            is OtaResult.CryptoError -> BackendOutcome.Failure("Crypto: ${r.cause.message ?: r.cause::class.simpleName}")
            is OtaResult.ContentError -> BackendOutcome.Failure("Content: ${r.checkFailReason}")
        }
    }

    private suspend fun runDanielspringer(params: OtaRequestParams): BackendOutcome {
        return runCatching {
            val catalog = catalogCache ?: danielspringerClient.fetchCatalog().also { catalogCache = it }
            val res = danielspringerClient.fetchLatestUrlForModel(
                catalog,
                model = params.model,
                region = params.region,
            ) ?: return BackendOutcome.Failure("Model not in danielspringer catalog")
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
        }.getOrElse { BackendOutcome.Failure(it.message ?: it::class.simpleName ?: "unknown") }
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
