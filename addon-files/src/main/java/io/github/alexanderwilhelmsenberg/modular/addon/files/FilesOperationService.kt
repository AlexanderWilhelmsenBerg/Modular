package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Keeps user-started storage work alive when its embedded surface or Activity goes away. */
internal class AndroidFilesOperationLifetime(private val context: Context) : FilesOperationLifetime {
    data class State(val id: String, val label: String, val status: String, val done: Long = 0, val total: Long? = null)
    @Volatile var state: State? = null
        private set
    private var lastNotification = 0L
    private val main = Handler(Looper.getMainLooper())

    @Synchronized override fun begin(id: String, label: String): String? {
        state = State(id, label, label)
        lastNotification = 0
        return runCatching {
            ContextCompat.startForegroundService(context, Intent(context, FilesOperationService::class.java))
            null
        }.getOrElse { failure -> "Android could not keep this operation running in the background: ${failure.message}" }
    }

    @Synchronized override fun update(id: String, status: String, done: Long, total: Long?) {
        val old = state?.takeIf { it.id == id } ?: return
        state = old.copy(status = status, done = done, total = total)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNotification < 1_000) return
        lastNotification = now
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        // Notification updates do not touch repository or controller locks.
        runCatching { context.getSystemService(NotificationManager::class.java)
            .notify(FilesOperationService.NOTIFICATION, FilesOperationService.notification(context, state!!)) }
    }

    @Synchronized override fun finish(id: String) {
        if (state?.id != id) return
        state = null
        main.post {
            synchronized(this) {
                if (state == null) context.stopService(Intent(context, FilesOperationService::class.java))
            }
        }
    }
}

class FilesOperationService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "File operations", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as FilesApplication
        val state = app.operationLifetime.state
        // A short operation can finish before Android delivers this start request.
        val shown = state ?: AndroidFilesOperationLifetime.State("finished", "Files", "Operation finished")
        ServiceCompat.startForeground(this, NOTIFICATION, notification(this, shown),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (state == null) { stopSelf(startId); return START_NOT_STICKY }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:files-operation")
                .apply { acquire(60 * 60 * 1000L) }
        }
        if (intent?.action == CANCEL) {
            Thread { app.pageController.cancelCurrentOperation() }.start()
        }
        // Destructive jobs must never be blindly replayed after process death.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as FilesApplication).pageController.cancelCurrentOperation()
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "files-operations"
        internal const val NOTIFICATION = 4101
        private const val CANCEL = "files.operation.CANCEL"
        internal fun notification(context: Context, state: AndroidFilesOperationLifetime.State): Notification {
            val open = PendingIntent.getActivity(context, 0, Intent(context, FilesActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val cancel = PendingIntent.getService(context, 1,
                Intent(context, FilesOperationService::class.java).setAction(CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val total = state.total?.takeIf { it > 0 }
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(state.label).setContentText(state.status)
                .setStyle(NotificationCompat.BigTextStyle().bigText(state.status))
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
                .setProgress(1000, if (total == null) 0 else (state.done.toDouble() / total * 1000).toInt().coerceIn(0, 1000), total == null)
                .addAction(0, "Cancel", cancel).build()
        }
    }
}
