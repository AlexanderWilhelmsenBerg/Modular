package io.github.alexanderwilhelmsenberg.modular.addon.sort.mostused

import android.app.AppOpsManager
import android.content.Context
import android.os.Process

/** Usage Access is a special Android app operation, not a runtime permission dialog. */
object UsageAccess {
    fun isGranted(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        } catch (_: RuntimeException) {
            false
        }
    }
}
