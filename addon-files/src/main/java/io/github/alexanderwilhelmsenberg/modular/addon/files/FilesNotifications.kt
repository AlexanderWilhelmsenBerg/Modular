package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

internal object FilesNotifications {
    const val CHANNEL = "files-operations"
    const val RESULT = 4102
    fun status(context: Context): JSONObject {
        val manager = context.getSystemService(NotificationManager::class.java)
        val granted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val channel = manager.getNotificationChannel(CHANNEL)
        return JSONObject().put("permissionGranted", granted).put("appEnabled", manager.areNotificationsEnabled())
            .put("channelImportance", channel?.importance ?: NotificationManager.IMPORTANCE_LOW)
            .put("visible", granted && manager.areNotificationsEnabled() && channel?.importance != NotificationManager.IMPORTANCE_NONE)
    }
    fun open(context: Context, id: String) = PendingIntent.getActivity(context, 0,
        Intent(context, FilesActivity::class.java).putExtra(FilesActivity.EXTRA_SHOW_OPERATION, true)
            .putExtra("operationId", id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun completed(context: Context, state: AndroidFilesOperationLifetime.State, summary: String) {
        if (!status(context).optBoolean("visible")) return
        context.getSystemService(NotificationManager::class.java).notify(RESULT,
            NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(state.label).setContentText(summary.take(300))
                .setStyle(NotificationCompat.BigTextStyle().bigText(summary.take(1000)))
                .setContentIntent(open(context, state.id)).setAutoCancel(true).setSilent(true).build())
    }
}
