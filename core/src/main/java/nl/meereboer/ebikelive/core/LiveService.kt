package nl.meereboer.ebikelive.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the bike connection alive while the screen
 * is off. The UI only reads [LiveService.connection].
 */
class LiveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notifyJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification("Wacht op fiets"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        val conn = _connection.value ?: LdiConnection(applicationContext).also { _connection.value = it }
        conn.start()
        notifyJob?.cancel()
        notifyJob = scope.launch {
            combine(conn.status, conn.data) { s, d -> s to d }.collect { (s, d) ->
                val text = if (s == LdiConnection.Status.LIVE)
                    "${fmt(d.speedKmh)} km/u  ·  accu ${d.batterySoc ?: "-"}%"
                else conn.message.value.ifEmpty { "Wacht op fiets" }
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIF_ID, buildNotification(text))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        _connection.value?.stop()
        _connection.value = null
        scope.cancel()
        super.onDestroy()
    }

    private fun fmt(v: Double?) = v?.let { "%.1f".format(it) } ?: "-"

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "eBike live", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pi = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LiveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("eBike live")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .addAction(0, "Stop", stop)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
    }

    companion object {
        private const val CHANNEL = "ebike_live"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "nl.meereboer.ebikelive.STOP"

        private val _connection = MutableStateFlow<LdiConnection?>(null)
        val connection: StateFlow<LdiConnection?> = _connection

        fun start(context: Context) =
            context.startForegroundService(Intent(context, LiveService::class.java))

        fun stop(context: Context) =
            context.startService(Intent(context, LiveService::class.java).setAction(ACTION_STOP))

        /** Runtime permissions the app has to ask for. */
        val PERMISSIONS = arrayOf(
            android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_CONNECT,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
