package com.desmond.ofd.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.desmond.ofd.R
import com.desmond.ofd.catalog.DeviceCatalog
import com.desmond.ofd.device.DeviceProps
import com.desmond.ofd.device.DeviceSnapshot
import kotlinx.coroutines.launch

private data class PendingDownload(
    val outcome: BackendOutcome.Success,
    val displayName: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    var mode by remember { mutableIntStateOf(0) }
    val modes = listOf(
        stringResource(R.string.mode_auto),
        stringResource(R.string.mode_manual),
    )
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val downloadStartedMessage = stringResource(R.string.download_started)
    val downloadInProgressMessage = stringResource(R.string.download_already_in_progress)
    val savePermissionFailedMessage = stringResource(R.string.save_location_permission_failed)
    val copiedMessagePattern = stringResource(R.string.copied_to_clipboard, "%s")

    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()

    var autoImei by remember { mutableStateOf("") }
    val snapshot = remember { DeviceProps.snapshot() }
    val autoSuggest = remember(catalog, snapshot) {
        catalog.firstOrNull { it.model == snapshot.productName }
    }

    LaunchedEffect(mode) { vm.reset() }

    var pending by remember { mutableStateOf<PendingDownload?>(null) }
    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val pendingDownload = pending
        pending = null
        if (pendingDownload == null || result.resultCode != Activity.RESULT_OK) {
            return@rememberLauncherForActivityResult
        }
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val grantFlags = result.data?.flags ?: 0
        if (!persistAndVerifySaveUri(ctx, uri, grantFlags)) {
            scope.launch { snackbarHostState.showSnackbar(savePermissionFailedMessage) }
            return@rememberLauncherForActivityResult
        }
        val newId = vm.startDownload(uri, pendingDownload.outcome, pendingDownload.displayName)
        scope.launch {
            snackbarHostState.showSnackbar(
                if (newId != null) downloadStartedMessage else downloadInProgressMessage,
            )
        }
    }

    // POST_NOTIFICATIONS is needed for the FGS notification on Android 13+. We ask only
    // when the user kicks off their first download — Google's recommended just-in-time
    // pattern, not the cold-start prompt.
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // Regardless of grant, proceed to the SAF picker — the download itself doesn't
        // require notification permission, the user just won't see a system notification.
        pending?.let { savePicker.launch(createFirmwareDocumentIntent(it.displayName)) }
    }

    fun launchDownloadFlow(intent: PendingDownload) {
        pending = intent
        // minSdk = 33; POST_NOTIFICATIONS is always required.
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            savePicker.launch(createFirmwareDocumentIntent(intent.displayName))
        } else {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Top,
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, label ->
                    SegmentedButton(
                        modifier = Modifier.weight(1f),
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        onClick = { mode = index },
                        selected = mode == index,
                        label = { Text(label) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            when (mode) {
                0 -> DetectedDeviceCard(
                    snapshot = snapshot,
                    isLoading = state is HomeUiState.Loading,
                    imei = autoImei,
                    onImeiChange = { autoImei = it },
                    onCheckClick = { vm.checkAuto(imei = autoImei.ifBlank { null }) },
                )
                else -> ManualForm(
                    catalog = catalog,
                    initialDevice = autoSuggest,
                    initialOtaVersion = "",
                    initialRegion = autoSuggest?.regions?.firstOrNull() ?: snapshot.region,
                    initialNvId = "",
                    initialRuiVersion = snapshot.ruiVersion,
                    isLoading = state is HomeUiState.Loading,
                    onSubmit = { params -> vm.checkManual(params) },
                )
            }

            when (val s = state) {
                HomeUiState.Idle, HomeUiState.Loading -> Unit
                is HomeUiState.Result -> {
                    Spacer(Modifier.height(16.dp))
                    ResultCard(
                        state = s,
                        onDownloadClick = {
                            val winner = s.winnerOutcome ?: return@ResultCard
                            val name = suggestedFilename(s.marketingName, winner.versionName)
                            launchDownloadFlow(PendingDownload(winner, name))
                        },
                        onCopied = { label ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    copiedMessagePattern.format(label),
                                )
                            }
                        },
                    )
                }
                is HomeUiState.Error -> {
                    Spacer(Modifier.height(16.dp))
                    ErrorCard(s.message)
                }
            }

        }
    }
}

private fun suggestedFilename(marketingName: String, versionName: String): String {
    val core = versionName.substringBefore('(').trim('_')
    val safe = "${marketingName}_$core".replace(Regex("[^A-Za-z0-9._-]"), "_")
    return "$safe.zip"
}

private fun createFirmwareDocumentIntent(displayName: String): Intent =
    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "application/zip"
        putExtra(Intent.EXTRA_TITLE, displayName)
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }

private fun persistAndVerifySaveUri(context: Context, uri: Uri, resultFlags: Int): Boolean {
    val requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    val takeFlags = resultFlags and requiredFlags
    if (takeFlags != requiredFlags) return false

    val resolver = context.applicationContext.contentResolver
    return runCatching {
        resolver.takePersistableUriPermission(uri, takeFlags)
        resolver.openFileDescriptor(uri, "rw")?.use { } ?: error("No file descriptor")
    }.isSuccess
}

@Composable
private fun DetectedDeviceCard(
    snapshot: DeviceSnapshot,
    isLoading: Boolean,
    imei: String,
    onImeiChange: (String) -> Unit,
    onCheckClick: () -> Unit,
) {
    val ctx = LocalContext.current
    val productName = Build.PRODUCT
    val marketingName = DeviceCatalog.marketingName(ctx, productName)
        ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.detected_device),
                style = MaterialTheme.typography.labelLarge.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = marketingName,
                style = MaterialTheme.typography.titleLarge.copy(lineHeight = 32.sp),
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$productName  •  Android ${Build.VERSION.RELEASE}  •  SDK ${Build.VERSION.SDK_INT}  •  ${snapshot.region.label}",
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = imei,
                onValueChange = onImeiChange,
                label = { Text(stringResource(R.string.imei)) },
                supportingText = { Text(stringResource(R.string.optional_beta_channel)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onCheckClick,
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.checking))
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.check_for_firmware))
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.couldnt_check),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
