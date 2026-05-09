package com.desmond.ofd.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Builds and updates the foreground-service notification for downloads. */
object DownloadNotifications {

    const val CHANNEL_ID = "ofd_downloads"
    const val NOTIFICATION_ID = 0xD0F0

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Firmware downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Progress and result of firmware downloads."
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    fun build(context: Context, state: DownloadState): Notification {
        ensureChannel(context)
        val title = paramsOf(state)?.displayName ?: "Firmware download"
        val (text, indeterminate, pct) = describe(state)

        val openIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()
        val pi = PendingIntent.getActivity(
            context,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(state is DownloadState.Active || state is DownloadState.Verifying)
            .setContentIntent(pi)
        if (state is DownloadState.Active || state is DownloadState.Verifying) {
            builder.setProgress(100, pct, indeterminate)
        }
        return builder.build()
    }

    private fun describe(state: DownloadState): Triple<String, Boolean, Int> = when (state) {
        is DownloadState.Active -> {
            val pct = (state.progress * 100).toInt()
            val text = "$pct%  •  ${formatBytesShort(state.bytesDownloaded)} / ${formatBytesShort(state.totalBytes)}  •  ${formatSpeed(state.speedBps)}"
            Triple(text, state.totalBytes <= 0, pct)
        }
        is DownloadState.Verifying -> Triple("Verifying MD5…", true, 0)
        is DownloadState.Completed -> {
            val ok = state.md5Matches
            val msg = when {
                ok == true -> "Download complete — MD5 verified"
                ok == false -> "Download complete — MD5 mismatch"
                else -> "Download complete"
            }
            Triple(msg, false, 100)
        }
        is DownloadState.Failed -> Triple("Failed: ${state.error.take(60)}", false, 0)
        DownloadState.Idle -> Triple("Idle", false, 0)
    }

    private fun paramsOf(state: DownloadState): DownloadParams? = when (state) {
        is DownloadState.Active -> state.params
        is DownloadState.Verifying -> state.params
        is DownloadState.Completed -> state.params
        is DownloadState.Failed -> state.params
        DownloadState.Idle -> null
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
