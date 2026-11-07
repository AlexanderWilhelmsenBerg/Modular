package io.github.alexanderwilhelmsenberg.modular.mostused

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import android.os.UserManager

data class ForegroundUsage(val ownProfileSerial: Long, val millisByPackage: Map<String, Long>)

/** Implementations are called on the controller's IO dispatcher. */
interface MostUsedUsageSource {
    val corePackageName: String
    fun hasUsageAccess(): Boolean
    fun queryForegroundUsage(days: Int, packages: Set<String>): ForegroundUsage
}

class AndroidMostUsedUsageSource(context: Context) : MostUsedUsageSource {
    private val appContext = context.applicationContext
    override val corePackageName: String = appContext.packageName

    override fun hasUsageAccess(): Boolean {
        return try {
            val appOps = appContext.getSystemService(AppOpsManager::class.java) ?: return false
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), corePackageName,
            ) == AppOpsManager.MODE_ALLOWED
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun queryForegroundUsage(days: Int, packages: Set<String>): ForegroundUsage {
        require(days in 1..30)
        val userManager = requireNotNull(appContext.getSystemService(UserManager::class.java))
        val serial = userManager.getSerialNumberForUser(Process.myUserHandle())
        check(serial >= 0L) { "Current profile unavailable" }
        val manager = requireNotNull(appContext.getSystemService(UsageStatsManager::class.java))
        val end = System.currentTimeMillis()
        val start = end - days * 86_400_000L
        val weights = manager.queryAndAggregateUsageStats(start, end).orEmpty()
            .filterKeys { it in packages && it != corePackageName }
            .mapValues { (_, usage) -> usage.totalTimeInForeground.coerceAtLeast(0L) }
        return ForegroundUsage(serial, weights)
    }
}
