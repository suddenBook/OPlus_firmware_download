package com.desmond.ofd.ui.screens

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.desmond.ofd.R
import com.desmond.ofd.download.DownloadState
import kotlinx.coroutines.launch

/** Single-card download UI bound to [DownloadCoordinator]'s state flow. */
@Composable
fun DownloadProgressCard(
    state: DownloadState,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state is DownloadState.Idle) return

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            when (state) {
                is DownloadState.Active -> ActiveContent(state, onCancel)
                is DownloadState.Verifying -> VerifyingContent(state)
                is DownloadState.Completed -> CompletedContent(state, onDismiss)
                is DownloadState.Failed -> FailedContent(state, onDismiss)
                DownloadState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun ActiveContent(state: DownloadState.Active, onCancel: () -> Unit) {
    Text(
        text = stringResource(R.string.downloading),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        text = state.params.displayName,
        style = MaterialTheme.typography.titleMedium.copy(lineHeight = 24.sp),
    )

    Spacer(Modifier.height(16.dp))
    LinearProgressIndicator(
        progress = { state.progress },
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )

    Spacer(Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = "${formatBytesShort(state.bytesDownloaded)} / ${formatBytesShort(state.totalBytes)}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = formatSpeed(state.speedBps) +
                if (state.etaSeconds > 0) "  •  " + formatEta(state.etaSeconds) else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(16.dp))
    OutlinedButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(stringResource(R.string.cancel)) }
}

@Composable
private fun VerifyingContent(state: DownloadState.Verifying) {
    Text(
        text = stringResource(R.string.verifying),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(4.dp))
    Text(text = state.params.displayName, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.computing_md5), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CompletedContent(state: DownloadState.Completed, onDismiss: () -> Unit) {
    val md5Ok = state.md5Matches == true
    val md5Bad = state.md5Matches == false
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (md5Bad) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
            contentDescription = null,
            tint = if (md5Bad) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = when {
                md5Ok -> stringResource(R.string.download_complete_md5_verified)
                md5Bad -> stringResource(R.string.download_complete_md5_mismatch)
                else -> stringResource(R.string.download_complete)
            },
            style = MaterialTheme.typography.titleMedium,
            color = if (md5Bad) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(state.params.displayName, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onDismiss,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(stringResource(R.string.done)) }
}

@Composable
private fun FailedContent(state: DownloadState.Failed, onDismiss: () -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val copyErrorDetails = stringResource(R.string.copy_error_details)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.download_failed),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(state.params.displayName, style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(2.dp))
    Text(
        state.error,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    OutlinedButton(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(
                    ClipEntry(ClipData.newPlainText(copyErrorDetails, state.errorReport())),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(copyErrorDetails)
    }
    Spacer(Modifier.height(4.dp))
    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.dismiss))
    }
}

private fun DownloadState.Failed.errorReport(): String =
    """
    OPlus Firmware download failure
    File: ${params.displayName}
    Target URI: ${params.targetUri}
    Expected size: ${params.expectedSize}
    Expected MD5: ${params.expectedMd5 ?: "(none)"}

    Error:
    $error
    """.trimIndent()

private fun formatBytesShort(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes >= 1L shl 30 -> "%.2f GiB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MiB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KiB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

private fun formatSpeed(bps: Long): String = when {
    bps <= 0 -> "—"
    bps >= 1L shl 20 -> "%.1f MB/s".format(bps / (1L shl 20).toDouble())
    bps >= 1L shl 10 -> "%.0f KB/s".format(bps / (1L shl 10).toDouble())
    else -> "$bps B/s"
}

@Composable
private fun formatEta(seconds: Long): String = when {
    seconds >= 3600 -> stringResource(
        R.string.eta_hours_minutes,
        seconds / 3600,
        (seconds % 3600) / 60,
    )
    seconds >= 60 -> stringResource(R.string.eta_minutes_seconds, seconds / 60, seconds % 60)
    else -> stringResource(R.string.eta_seconds, seconds)
}
