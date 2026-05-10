package com.desmond.ofd.download

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that pins the process alive while at least one download is in flight.
 * Observes [DownloadCoordinator.jobs] and updates the (single, summary) notification on each
 * change. When the jobs map empties, releases foreground and self-stops.
 *
 * Android 15+ caps `dataSync` foreground services at 6 hours per 24-hour window. When the
 * timer fires, [onTimeout] is invoked — we cancel all in-flight downloads cleanly rather
 * than letting the system kill the process mid-flight.
 */
class DownloadService : LifecycleService() {

    private var observerJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val initial = DownloadNotifications.build(applicationContext, DownloadCoordinator.jobs.value.values)
        startInForeground(initial)
        if (observerJob?.isActive != true) {
            observerJob = lifecycleScope.launch {
                DownloadCoordinator.jobs.collectLatest { jobsMap ->
                    if (jobsMap.isEmpty()) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val notif = DownloadNotifications.build(applicationContext, jobsMap.values)
                        try {
                            NotificationManagerCompat.from(applicationContext)
                                .notify(DownloadNotifications.NOTIFICATION_ID, notif)
                        } catch (_: SecurityException) {
                            // POST_NOTIFICATIONS can be denied while the foreground service runs.
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ enforces a 6h cap on dataSync FGS. Cancel cleanly so partial files are
        // removed and StateFlow returns to empty, instead of the system killing us hard.
        DownloadCoordinator.jobs.value.keys.toList().forEach { DownloadCoordinator.cancel(it) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startInForeground(notif: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else 0
        ServiceCompat.startForeground(
            this,
            DownloadNotifications.NOTIFICATION_ID,
            notif,
            type,
        )
    }
}
