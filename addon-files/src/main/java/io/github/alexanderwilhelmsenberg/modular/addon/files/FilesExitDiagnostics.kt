package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

internal object FilesExitDiagnostics {
    fun snapshot(context: Context): JSONArray {
        if (Build.VERSION.SDK_INT < 30) return JSONArray()
        return runCatching {
            JSONArray(context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 5).map { info ->
                    val reason = when (info.reason) {
                        ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                        ApplicationExitInfo.REASON_ANR -> "ANR"
                        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
                        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
                        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
                        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
                        else -> "REASON_${info.reason}"
                    }
                    JSONObject().put("at", info.timestamp).put("pid", info.pid).put("process", info.processName)
                        .put("reason", reason).put("reasonCode", info.reason).put("status", info.status)
                        .put("description", info.description).put("importance", info.importance)
                        .put("pssKb", info.pss).put("rssKb", info.rss)
                })
        }.getOrDefault(JSONArray())
    }
}
