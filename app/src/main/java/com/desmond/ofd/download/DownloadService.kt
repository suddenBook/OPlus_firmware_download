package com.desmond.ofd.download

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that pins the process alive while a download runs. The actual download
 * work lives in [DownloadCoordinator]; this service just exists so Android doesn't reap the
 * process when the app is backgrounded.
 *
 * Lifecycle:
 *  - Coordinator.start() → context.startForegroundService(DownloadService)
 *  - onStartCommand → startForeground(notification) within 5 s
 *  - Observe Coordinator.state → update notification on each tick
 *  - When state becomes Idle → stopForeground + stopSelf
 */
class DownloadService : Service() {

    private val scope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }
    private var observerJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initial = DownloadNotifications.build(applicationContext, DownloadCoordinator.state.value)
        startInForeground(initial)
        if (observerJob?.isActive != true) {
            observerJob = scope.launch {
                DownloadCoordinator.state.collectLatest { state ->
                    if (state is DownloadState.Idle) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        val notif = DownloadNotifications.build(applicationContext, state)
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        observerJob?.cancel()
        scope.cancel()
    }

    private fun startInForeground(notif: android.app.Notification) {
        val type =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
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
