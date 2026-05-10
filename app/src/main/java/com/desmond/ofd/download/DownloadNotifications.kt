package com.desmond.ofd.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.desmond.ofd.R

/**
 * Builds a single summary notification representing the aggregate state of all in-flight
 * downloads. With multi-download support we no longer post one notification per download —
 * the Downloads tab shows the per-download breakdown; the notification just keeps the user
 * informed that work is happening and surfaces aggregate progress.
 */
object DownloadNotifications {

    const val CHANNEL_ID = "ofd_downloads"
    const val NOTIFICATION_ID = 0xD0F0

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_downloads_desc)
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    fun build(context: Context, jobs: Collection<DownloadCoordinator.DownloadJob>): Notification {
        ensureChannel(context)
        val (title, text, indeterminate, percent) = describe(context, jobs)

        val openIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        val pi = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val anyActive = jobs.any { it.state is DownloadState.Active || it.state is DownloadState.Verifying }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(anyActive)
            .setContentIntent(pi)
        if (anyActive) builder.setProgress(100, percent, indeterminate)
        return builder.build()
    }

    private data class Description(
        val title: String,
        val text: String,
        val indeterminate: Boolean,
        val percent: Int,
    )

    private fun describe(
        context: Context,
        jobs: Collection<DownloadCoordinator.DownloadJob>,
    ): Description {
        val active = jobs.mapNotNull { it.state as? DownloadState.Active }
        val verifying = jobs.mapNotNull { it.state as? DownloadState.Verifying }
        val activeCount = active.size + verifying.size

        return when {
            jobs.isEmpty() -> Description(
                context.getString(R.string.notification_firmware_download),
                context.getString(R.string.notification_idle),
                indeterminate = false,
                percent = 0,
            )
            activeCount == 0 -> {
                // All downloads are in terminal state (Completed/Failed) waiting for dismissal.
                val anyFailed = jobs.any { it.state is DownloadState.Failed }
                val title = context.getString(R.string.notification_firmware_download)
                val text = if (anyFailed) context.getString(R.string.notification_some_failed)
                else context.getString(R.string.download_complete)
                Description(title, text, indeterminate = false, percent = 100)
            }
            activeCount == 1 && verifying.isNotEmpty() -> {
                val v = verifying.first()
                Description(
                    title = v.params.displayName,
                    text = context.getString(R.string.computing_md5),
                    indeterminate = true,
                    percent = 0,
                )
            }
            activeCount == 1 -> {
                val a = active.first()
                val pct = (a.progress * 100).toInt()
                Description(
                    title = a.params.displayName,
                    text = "$pct%  •  ${formatBytesShort(a.bytesDownloaded)} / ${formatBytesShort(a.totalBytes)}  •  ${formatSpeed(a.speedBps)}",
                    indeterminate = a.totalBytes <= 0,
                    percent = pct,
                )
            }
            else -> {
                val totalBytes = active.sumOf { it.totalBytes.coerceAtLeast(0L) }
                val downloaded = active.sumOf { it.bytesDownloaded }
                val combinedSpeed = active.sumOf { it.speedBps }
                val pct = if (totalBytes > 0)
                    ((downloaded.toFloat() / totalBytes).coerceIn(0f, 1f) * 100).toInt() else 0
                Description(
                    title = context.getString(R.string.notification_n_downloads, activeCount),
                    text = "$pct%  •  ${formatBytesShort(downloaded)} / ${formatBytesShort(totalBytes)}  •  ${formatSpeed(combinedSpeed)}",
                    indeterminate = totalBytes <= 0,
                    percent = pct,
                )
            }
        }
    }

    private fun formatBytesShort(bytes: Long): String = when {
        bytes <= 0 -> "—"
        bytes >= 1L shl 30 -> "%.2f GiB".format(bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> "%.1f MiB".format(bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> "%.1f KiB".format(bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    private fun formatSpeed(bps: Long): String = when {
        bps <= 0 -> ""
        bps >= 1L shl 20 -> "%.1f MB/s".format(bps / (1L shl 20).toDouble())
        bps >= 1L shl 10 -> "%.0f KB/s".format(bps / (1L shl 10).toDouble())
        else -> "$bps B/s"
    }
}
