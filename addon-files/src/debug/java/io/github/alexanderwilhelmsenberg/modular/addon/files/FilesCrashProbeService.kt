package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Debug-only, unexported secondary process used to prove real crash-stack persistence. */
class FilesCrashProbeService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Handler(Looper.getMainLooper()).post { throw IllegalStateException("Files isolated crash capture probe") }
        return START_NOT_STICKY
    }
}
