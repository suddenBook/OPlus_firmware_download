package com.desmond.ofd.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.desmond.ofd.download.DownloadPrefs
import kotlin.math.roundToInt

private const val URL_REALME_OTA = "https://github.com/R0rt1z2/realme-ota"
private const val URL_DANIELSPRINGER = "https://roms.danielspringer.at/index.php?view=ota"
private const val URL_MOBILE_MODELS = "https://github.com/KHwang9883/MobileModels"
private const val URL_SOURCE = "https://github.com/suddenBook/OPlus_firmware_download"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val prefs = remember { DownloadPrefs(ctx) }
    fun open(url: String) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }

    Scaffold(
        modifier = modifier,
        topBar = { CenterAlignedTopAppBar(title = { Text("Info") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Downloads")
            ThreadCountCard(prefs = prefs)

            Spacer(Modifier.height(16.dp))
            SectionHeader("Backends")
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column {
                    LinkRow(
                        title = "realme-ota",
                        subtitle = "Live OPPO update endpoint. Requires the device's current OTA version.",
                        onClick = { open(URL_REALME_OTA) },
                    )
                    HorizontalDivider()
                    LinkRow(
                        title = "danielspringer.at",
                        subtitle = "Third-party catalog of historical OnePlus firmware.",
                        onClick = { open(URL_DANIELSPRINGER) },
                    )
                    HorizontalDivider()
                    LinkRow(
                        title = "MobileModels",
                        subtitle = "Bundled device catalog source (KHwang9883/MobileModels).",
                        onClick = { open(URL_MOBILE_MODELS) },
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionHeader("About")
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column {
                    ListItem(
                        headlineContent = { Text("App version") },
                        supportingContent = { Text("1.0") },
                    )
                    HorizontalDivider()
                    LinkRow(
                        title = "Source code",
                        subtitle = URL_SOURCE,
                        onClick = { open(URL_SOURCE) },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ThreadCountCard(prefs: DownloadPrefs) {
    val configured by prefs.threadCount.collectAsStateWithLifecycle()
    var sliderValue by remember(configured) { mutableFloatStateOf(configured.toFloat()) }
    LaunchedEffect(configured) { sliderValue = configured.toFloat() }

    val current = sliderValue.roundToInt()
    val display = if (current == DownloadPrefs.AUTO) "Auto" else current.toString()

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Concurrent threads",
                style = MaterialTheme.typography.labelLarge.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = display,
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "0 = auto (size-aware). 1–${DownloadPrefs.MAX} fixes the count.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { prefs.setThreadCount(sliderValue.roundToInt()) },
                valueRange = 0f..DownloadPrefs.MAX.toFloat(),
                steps = DownloadPrefs.MAX - 1,
            )
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Icon(
                imageVector = Icons.Outlined.OpenInNew,
                contentDescription = "Open externally",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}
