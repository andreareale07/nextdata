package app.nextdata.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process (and therefore the network connections of running transfers)
 * alive while the user is in the system file picker or in another app. It shows an ongoing notification and
 * stops itself when the transfer queue is idle. It only reads the shared [app.nextdata.state.AppController].
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        val initial = build(1)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, initial)

        val controller = (application as NextDataApplication).existingController()
        if (controller == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        job?.cancel()
        job = scope.launch {
            controller.transfers.items.map { list -> list.count { it.status.isActive } }.distinctUntilChanged().collect { n ->
                if (n == 0) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(n))
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.transfer_channel_name), NotificationManager.IMPORTANCE_LOW))
            }
        }
    }

    private fun build(count: Int): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.transfer_notification_title))
            .setContentText(resources.getQuantityString(R.plurals.transfer_notification_text, count, count))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "transfers"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val i = Intent(context, TransferService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }
}
