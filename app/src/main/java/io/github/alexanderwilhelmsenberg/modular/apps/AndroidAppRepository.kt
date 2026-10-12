package io.github.alexanderwilhelmsenberg.modular.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Core-owned source of truth for launchable Android activities.
 *
 * The repository deliberately exposes a platform-neutral identity model:
 * package/activity names plus the persistent profile serial number. Android
 * UserHandle objects remain an implementation detail and are resolved only
 * when talking to LauncherApps.
 */
class AndroidAppRepository(context: Context) : AppRepository {
    private val appContext = context.applicationContext
    private val launcherApps = requireNotNull(
        appContext.getSystemService(LauncherApps::class.java),
    ) {
        "LauncherApps system service is unavailable"
    }
    private val userManager = requireNotNull(
        appContext.getSystemService(UserManager::class.java),
    ) {
        "UserManager system service is unavailable"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private var generation = 0L
    private var profileReceiverRegistered = false

    private val _catalog = MutableStateFlow(AppCatalog())
    override val catalog: StateFlow<AppCatalog> = _catalog.asStateFlow()

    private val launcherCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) {
            refresh()
        }

        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            refresh()
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            refresh()
        }

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            refresh()
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            refresh()
        }
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    init {
        launcherApps.registerCallback(
            launcherCallback,
            Handler(Looper.getMainLooper()),
        )
        registerProfileReceiver()

        scope.launch {
            for (ignored in refreshRequests) {
                loadCatalog()
            }
        }

        refresh()
    }

    override fun refresh() {
        refreshRequests.trySend(Unit)
    }

    override suspend fun launch(key: AppKey): Result<Unit> =
        withContext(Dispatchers.Main.immediate) {
            runCatching {
                val user = requireNotNull(
                    userManager.getUserForSerialNumber(key.profileSerial),
                ) {
                    "Profile no longer exists: " + key.profileSerial
                }

                launcherApps.startMainActivity(
                    ComponentName(key.packageName, key.activityName),
                    user,
                    null,
                    null,
                )
            }
        }

    private suspend fun loadCatalog() {
        _catalog.update { state ->
            state.copy(isRefreshing = true, lastError = null)
        }

        val result = runCatching {
            withContext(Dispatchers.Default) {
                queryCatalog()
            }
        }

        result.fold(
            onSuccess = { data ->
                generation += 1
                _catalog.value = AppCatalog(
                    generation = generation,
                    profiles = data.profiles,
                    apps = data.apps,
                    isRefreshing = false,
                    lastError = null,
                )
            },
            onFailure = { throwable ->
                _catalog.update { state ->
                    state.copy(
                        isRefreshing = false,
                        lastError = throwable.message
                            ?: throwable::class.java.simpleName,
                    )
                }
            },
        )
    }

    private fun queryCatalog(): CatalogData {
        val profileRecords = launcherApps.profiles
            .mapNotNull { user ->
                val serial = userManager.getSerialNumberForUser(user)
                if (serial < 0L) {
                    null
                } else {
                    ProfileRecord(
                        user = user,
                        profile = AppProfile(
                            serialNumber = serial,
                            quietModeEnabled = userManager.isQuietModeEnabled(user),
                        ),
                    )
                }
            }
            .sortedBy { it.profile.serialNumber }

        val apps = buildList {
            profileRecords.forEach { profileRecord ->
                val profileSerial = profileRecord.profile.serialNumber
                launcherApps
                    .getActivityList(null, profileRecord.user)
                    .forEach { activity ->
                        val component = activity.componentName
                        add(
                            LaunchableApp(
                                key = AppKey(
                                    packageName = component.packageName,
                                    activityName = component.className,
                                    profileSerial = profileSerial,
                                ),
                                label = activity.label.toString(),
                                firstInstallTime = activity.firstInstallTime,
                                enabled = activity.applicationInfo.enabled,
                                loadingProgress =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        activity.loadingProgress
                                    } else {
                                        null
                                    },
                            ),
                        )
                    }
            }
        }.sortedWith(
            compareBy(
                { it.key.profileSerial },
                { it.key.packageName },
                { it.key.activityName },
            ),
        )

        return CatalogData(
            profiles = profileRecords.map { it.profile },
            apps = apps,
        )
    }

    private fun registerProfileReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
            addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                addAction(Intent.ACTION_PROFILE_ADDED)
                addAction(Intent.ACTION_PROFILE_REMOVED)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                addAction(Intent.ACTION_PROFILE_AVAILABLE)
                addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                profileReceiver,
                filter,
                Context.RECEIVER_EXPORTED,
            )
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(profileReceiver, filter)
        }

        profileReceiverRegistered = true
    }

    override fun close() {
        launcherApps.unregisterCallback(launcherCallback)

        if (profileReceiverRegistered) {
            try {
                appContext.unregisterReceiver(profileReceiver)
            } catch (_: IllegalArgumentException) {
                // Already unregistered by the platform or lifecycle teardown.
            }
            profileReceiverRegistered = false
        }

        refreshRequests.close()
        scope.cancel()
    }

    private data class ProfileRecord(
        val user: UserHandle,
        val profile: AppProfile,
    )

    private data class CatalogData(
        val profiles: List<AppProfile>,
        val apps: List<LaunchableApp>,
    )
}
