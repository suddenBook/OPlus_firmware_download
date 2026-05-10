package com.desmond.ofd.ui.screens

import android.content.ClipData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.desmond.ofd.R
import com.desmond.ofd.ui.components.ExpandableCard
import kotlinx.coroutines.launch

private data class ManualSection(
    val icon: ImageVector,
    val title: String,
    val body: @Composable () -> Unit,
)

private data class AdbCommand(
    val description: String,
    val command: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualScreen(modifier: Modifier = Modifier) {
    var expandedTitle by remember { mutableStateOf<String?>(null) }
    val sections = manualSections()

    Scaffold(
        modifier = modifier,
        topBar = { CenterAlignedTopAppBar(title = { Text(stringResource(R.string.manual_title)) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            sections.forEach { section ->
                ExpandableCard(
                    icon = section.icon,
                    title = section.title,
                    expanded = expandedTitle == section.title,
                    onExpandToggle = {
                        expandedTitle = if (expandedTitle == section.title) null else section.title
                    },
                    body = section.body,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun manualSections(): List<ManualSection> = listOf(
    ManualSection(
        icon = Icons.Outlined.Home,
        title = stringResource(R.string.manual_quick_start_title),
        body = { ParagraphBody(stringResource(R.string.manual_quick_start_body)) },
    ),
    ManualSection(
        icon = Icons.Outlined.PhoneAndroid,
        title = stringResource(R.string.manual_auto_title),
        body = { ParagraphBody(stringResource(R.string.manual_auto_body)) },
    ),
    ManualSection(
        icon = Icons.Outlined.Tune,
        title = stringResource(R.string.manual_manual_title),
        body = { ParagraphBody(stringResource(R.string.manual_manual_body)) },
    ),
    ManualSection(
        icon = Icons.Outlined.Terminal,
        title = stringResource(R.string.manual_adb_title),
        body = { AdbBody() },
    ),
    ManualSection(
        icon = Icons.Outlined.Speed,
        title = stringResource(R.string.manual_speed_title),
        body = { ParagraphBody(stringResource(R.string.manual_speed_body)) },
    ),
    ManualSection(
        icon = Icons.Outlined.Bolt,
        title = stringResource(R.string.manual_caveat_title),
        body = { ParagraphBody(stringResource(R.string.manual_caveat_body)) },
    ),
    ManualSection(
        icon = Icons.Outlined.CheckCircle,
        title = stringResource(R.string.manual_md5_title),
        body = { ParagraphBody(stringResource(R.string.manual_md5_body)) },
    ),
    ManualSection(
        icon = Icons.Outlined.ErrorOutline,
        title = stringResource(R.string.manual_errors_title),
        body = { ParagraphBody(stringResource(R.string.manual_errors_body)) },
    ),
)

@Composable
private fun ParagraphBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun AdbBody() {
    val commands = listOf(
        AdbCommand(stringResource(R.string.manual_adb_cmd_product), "adb shell getprop ro.product.name"),
        AdbCommand(stringResource(R.string.manual_adb_cmd_ota), "adb shell getprop ro.build.version.ota"),
        AdbCommand(stringResource(R.string.manual_adb_cmd_nv), "adb shell getprop ro.build.oplus_nv_id"),
        AdbCommand(stringResource(R.string.manual_adb_cmd_rui), "adb shell getprop ro.build.version.realmeui"),
        AdbCommand(stringResource(R.string.manual_adb_cmd_oplusrom), "adb shell getprop ro.build.version.oplusrom"),
        AdbCommand(stringResource(R.string.manual_adb_cmd_display), "adb shell getprop ro.build.display.id"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.manual_adb_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        commands.forEach { AdbCommandRow(it) }
    }
}

@Composable
private fun AdbCommandRow(item: AdbCommand) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyLabel = stringResource(R.string.manual_adb_copy_command)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = item.description,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = item.command,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText(copyLabel, item.command)),
                        )
                    }
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = copyLabel,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
