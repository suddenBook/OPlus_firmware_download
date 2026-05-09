package com.desmond.ofd.ui.screens

import android.content.Intent
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.desmond.ofd.catalog.CatalogRepository
import com.desmond.ofd.catalog.DeviceCatalog
import com.desmond.ofd.catalog.DeviceEntry
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
    val modes = listOf("Auto", "Manual")
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()

    var autoImei by remember { mutableStateOf("") }
    val snapshot = remember { DeviceProps.snapshot() }
    val catalog by produceState<List<DeviceEntry>>(initialValue = emptyList(), ctx) {
        value = CatalogRepository(ctx).allDevices()
    }
    val autoSuggest = remember(catalog, snapshot) {
        catalog.firstOrNull { it.model == snapshot.productName }
    }

    LaunchedEffect(mode) { vm.reset() }

    var pending by remember { mutableStateOf<PendingDownload?>(null) }
    val savePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val intent = pending
        pending = null
        if (uri == null || intent == null) return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        vm.startDownload(uri, intent.outcome, intent.displayName)
        scope.launch { snackbarHostState.showSnackbar("Download started") }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("OPlus Firmware") },
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
            SingleChoiceSegmentedButtonRow {
                modes.forEachIndexed { index, label ->
                    SegmentedButton(
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
                            pending = PendingDownload(winner, name)
                            savePicker.launch(name)
                        },
                        onCopied = { label ->
                            scope.launch { snackbarHostState.showSnackbar("$label copied") }
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
                text = "Detected device",
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
                label = { Text("IMEI") },
                supportingText = { Text("Optional for beta channel.") },
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
                    Text("Checking…")
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Check for firmware")
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
                text = "Couldn't check",
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
