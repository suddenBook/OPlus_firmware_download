package com.desmond.ofd.ui.screens

import android.content.ClipData
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.desmond.ofd.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ResultCard(
    state: HomeUiState.Result,
    onDownloadClick: () -> Unit,
    onCopied: (label: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val winnerOutcome = state.winnerOutcome
    val md5Label = stringResource(R.string.md5)
    val downloadUrlLabel = stringResource(R.string.download_url)

    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.latest_firmware),
                style = MaterialTheme.typography.labelLarge.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))

            if (winnerOutcome == null) {
                NoResultsBlock(state)
            } else {
                Text(
                    text = firmwareVersion(winnerOutcome.versionName),
                    style = MaterialTheme.typography.titleLarge.copy(lineHeight = 32.sp),
                )

                Spacer(Modifier.height(16.dp))
                MetadataRow(
                    sizeBytes = winnerOutcome.sizeBytes,
                    buildDate = extractBuildDate(winnerOutcome),
                )

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Md5Row(
                    md5 = winnerOutcome.md5,
                    onCopy = { onCopied(md5Label) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Spacer(Modifier.height(12.dp))
                BackendsBreakdown(state)

                winnerOutcome.expiresAtEpochSeconds?.let { expiresAt ->
                    Spacer(Modifier.height(12.dp))
                    ExpiryRow(expiresAt = expiresAt)
                }

                Spacer(Modifier.height(12.dp))
                val clipboard = LocalClipboard.current
                val scope = rememberCoroutineScope()
                Button(
                    onClick = onDownloadClick,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.download_with_size, formatBytes(winnerOutcome.sizeBytes)))
                }
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = {
                        scope.launch {
                            clipboard.setClipEntry(
                                ClipEntry(ClipData.newPlainText(downloadUrlLabel, winnerOutcome.downloadUrl)),
                            )
                            onCopied(downloadUrlLabel)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.copy_url))
                }
            }
        }
    }
}

@Composable
private fun MetadataRow(sizeBytes: Long, buildDate: String?) {
    Row(modifier = Modifier.fillMaxWidth()) {
        MetaCell(
            label = stringResource(R.string.size),
            value = formatBytes(sizeBytes),
            modifier = Modifier.weight(1f),
        )
        MetaCell(
            label = stringResource(R.string.build_date),
            value = buildDate ?: "—",
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun MetaCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

@Composable
private fun Md5Row(md5: String?, onCopy: (String) -> Unit) {
    if (md5.isNullOrBlank()) return
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val md5Label = stringResource(R.string.md5)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable {
                scope.launch {
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText(md5Label, md5)),
                    )
                    onCopy(md5)
                }
            }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.md5),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = md5,
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = stringResource(R.string.copy_md5),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun ExpiryRow(expiresAt: Long, modifier: Modifier = Modifier) {
    val now by produceState(initialValue = System.currentTimeMillis() / 1000L) {
        while (true) {
            delay(1000L)
            value = System.currentTimeMillis() / 1000L
        }
    }
    val secondsLeft = (expiresAt - now).coerceAtLeast(0L)
    val isExpired = secondsLeft == 0L
    val color = if (isExpired) MaterialTheme.colorScheme.error
    else MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (isExpired) Icons.Outlined.ErrorOutline else Icons.Outlined.Schedule,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        val text = when {
            isExpired -> stringResource(R.string.url_expired_recheck)
            secondsLeft >= 60 -> stringResource(
                R.string.url_valid_minutes_seconds,
                secondsLeft / 60,
                secondsLeft % 60,
            )
            else -> stringResource(R.string.url_valid_seconds, secondsLeft)
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = color,
        )
    }
}

@Composable
private fun BackendsBreakdown(state: HomeUiState.Result) {
    BackendRow(
        label = stringResource(R.string.backend_label_danielspringer),
        outcome = state.danielspringer,
        isWinner = state.winnerLabel == BackendLabels.DANIELSPRINGER,
    )
    Spacer(Modifier.height(6.dp))
    BackendRow(
        label = stringResource(R.string.backend_label_realme_ota_stable),
        outcome = state.realmeOtaStable,
        isWinner = state.winnerLabel == BackendLabels.REALME_OTA_STABLE,
    )
    Spacer(Modifier.height(6.dp))
    BackendRow(
        label = stringResource(R.string.backend_label_realme_ota_beta),
        outcome = state.realmeOtaBeta,
        isWinner = state.winnerLabel == BackendLabels.REALME_OTA_BETA,
    )
}

@Composable
private fun BackendRow(label: String, outcome: BackendOutcome, isWinner: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (outcome) {
            is BackendOutcome.Success -> Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = if (isWinner) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            is BackendOutcome.Skipped -> Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            is BackendOutcome.Failure, BackendOutcome.NotAttempted -> Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            val detail = when (outcome) {
                is BackendOutcome.Success -> {
                    val ver = firmwareVersion(outcome.versionName)
                    if (isWinner) {
                        stringResource(R.string.backend_newest, ver)
                    } else {
                        stringResource(R.string.backend_older, ver)
                    }
                }
                is BackendOutcome.Skipped -> backendMessageText(outcome.reason)
                is BackendOutcome.Failure -> backendMessageText(outcome.message)
                BackendOutcome.NotAttempted -> stringResource(R.string.not_run)
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun backendMessageText(message: BackendMessage): String = when (message) {
    is BackendMessage.Resource -> stringResource(
        message.resId,
        *message.args.toTypedArray(),
    )
    is BackendMessage.Raw -> message.value
}

@Composable
private fun NoResultsBlock(state: HomeUiState.Result) {
    Text(
        text = stringResource(R.string.no_firmware_found),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.error,
    )
    Spacer(Modifier.height(8.dp))
    BackendsBreakdown(state)
}

private fun firmwareVersion(versionName: String): String =
    versionName.substringAfter('_', missingDelimiterValue = versionName)

private fun extractBuildDate(outcome: BackendOutcome.Success): String? {
    Regex("""/component-ota/(\d{2})/(\d{2})/(\d{2})/""").find(outcome.downloadUrl)?.let {
        val (y, m, d) = it.destructured
        return "20$y-$m-$d"
    }
    val withTimestamp = outcome.realOtaVersion ?: outcome.versionName
    Regex("""_(\d{4})(\d{2})(\d{2})""").find(withTimestamp)?.let {
        val (y, m, d) = it.destructured
        return "$y-$m-$d"
    }
    return null
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes >= 1L shl 30 -> "%.2f GiB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MiB".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.1f KiB".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}
