package io.github.alexanderwilhelmsenberg.modular.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

/** System-owned management screens; app identity always includes its profile. */
internal class AndroidAppActions(context: Context) {
    private val launcherApps = requireNotNull(context.getSystemService(LauncherApps::class.java))
    private val userManager = requireNotNull(context.getSystemService(UserManager::class.java))

    private fun userFor(key: AppKey): UserHandle = requireNotNull(
        userManager.getUserForSerialNumber(key.profileSerial),
    ) { "This app's profile is no longer available." }

    fun showInfo(key: AppKey) {
        launcherApps.startAppDetailsActivity(
            ComponentName(key.packageName, key.activityName), userFor(key), null, null,
        )
    }

    /** Other profiles use their profile-aware App info screen, never another user's package. */
    fun uninstall(key: AppKey, launchConfirmation: (Intent) -> Unit): Boolean {
        if (userFor(key) != Process.myUserHandle()) {
            showInfo(key)
            return false
        }
        launchConfirmation(uninstallIntent(key.packageName))
        return true
    }
}

internal fun uninstallIntent(packageName: String): Intent =
    Intent(Intent.ACTION_DELETE, Uri.fromParts("package", packageName, null))
        .putExtra(Intent.EXTRA_RETURN_RESULT, true)
