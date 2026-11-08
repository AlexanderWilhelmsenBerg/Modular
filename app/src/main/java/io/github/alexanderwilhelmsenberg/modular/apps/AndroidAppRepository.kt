package io.github.alexanderwilhelmsenberg.modular.apps

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
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
class AndroidAppRepository internal constructor(
    context: Context,
    private val onCachedCatalogPublished: ((AppCatalog) -> Unit)?,
) : AppRepository {
    constructor(context: Context) : this(context, null)

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
    private val requestLock = Any()
    private var fullRefreshPending = false
    private val packagesPending = linkedSetOf<RefreshRequest.Package>()
    private val cache = AppCatalogCache(appContext)
    private var generation = 0L
    private var activeLocaleTags = localeTags()
    private var profileReceiverRegistered = false

    private val _catalog = MutableStateFlow(AppCatalog(isRefreshing = true))
    override val catalog: StateFlow<AppCatalog> = _catalog.asStateFlow()

    private val launcherCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) {
            packageChanged(packageName, user)
        }

        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            packageChanged(packageName, user)
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            packageChanged(packageName, user)
        }

        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) {
            packageNames.forEach { packageChanged(it, user) }
        }

        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) {
            packageNames.forEach { packageChanged(it, user) }
        }

        override fun onPackagesAvailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            packageNames.forEach { packageChanged(it, user) }
        }

        override fun onPackagesUnavailable(
            packageNames: Array<out String>,
            user: UserHandle,
            replacing: Boolean,
        ) {
            packageNames.forEach { packageChanged(it, user) }
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

        refresh()
        scope.launch {
            loadCachedCatalog()
            for (ignored in refreshRequests) {
                val pending = synchronized(requestLock) {
                    val requests = if (fullRefreshPending) {
                        listOf(RefreshRequest.Full)
                    } else {
                        packagesPending.toList()
                    }
                    fullRefreshPending = false
                    packagesPending.clear()
                    requests
                }
                if (pending.isEmpty()) continue
                if (pending.any { it is RefreshRequest.Full }) {
                    loadCatalog()
                } else {
                    updatePackages(pending.filterIsInstance<RefreshRequest.Package>())
                }
            }
        }
    }

    override fun refresh() {
        synchronized(requestLock) {
            fullRefreshPending = true
            packagesPending.clear()
        }
        refreshRequests.trySend(Unit)
    }

    private fun packageChanged(packageName: String, user: UserHandle) {
        synchronized(requestLock) {
            if (!fullRefreshPending) {
                packagesPending += RefreshRequest.Package(packageName, user)
                if (packagesPending.size > 256) {
                    packagesPending.clear()
                    fullRefreshPending = true
                }
            }
        }
        refreshRequests.trySend(Unit)
    }

    override suspend fun launch(key: AppKey): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val user = requireNotNull(
                    userManager.getUserForSerialNumber(key.profileSerial),
                ) {
                    "Profile no longer exists: " + key.profileSerial
                }
                require(!userManager.isQuietModeEnabled(user)) {
                    "Profile is unavailable: ${key.profileSerial}"
                }

                val activity = launcherApps.getActivityList(key.packageName, user)
                    .firstOrNull { it.componentName ==
                        ComponentName(key.packageName, key.activityName) }
                require(activity != null && isEnabled(activity.applicationInfo)) {
                    "App is no longer launchable: ${key.stableId}"
                }
                launcherApps.startMainActivity(
                    ComponentName(key.packageName, key.activityName),
                    user,
                    null,
                    null,
                )
            }
        }

    private suspend fun loadCachedCatalog() {
        val snapshot = withContext(Dispatchers.IO) { cache.read() } ?: return
        val records = try {
            withContext(Dispatchers.IO) { queryProfiles() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return
        }
        if (!snapshot.isUsable(localeTags(), records.map { it.profile })) return
        generation = 1
        activeLocaleTags = snapshot.localeTags
        _catalog.value = AppCatalog(
            generation = generation,
            profiles = snapshot.profiles,
            apps = snapshot.apps,
            isRefreshing = true,
        )
        onCachedCatalogPublished?.invoke(_catalog.value)
    }

    private suspend fun loadCatalog() {
        _catalog.update { state ->
            state.copy(isRefreshing = true, lastError = null)
        }

        val result = runCatching {
            withContext(Dispatchers.IO) {
                queryCatalog()
            }
        }

        result.fold(
            onSuccess = { data ->
                publish(data)
            },
            onFailure = { throwable ->
                if (throwable is CancellationException) throw throwable
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

    private suspend fun updatePackages(requests: List<RefreshRequest.Package>) {
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val records = queryProfiles()
                val profiles = records.map { it.profile }
                if (generation == 0L) return@withContext null
                var apps = _catalog.value.apps
                val distinct = requests.map { request ->
                    userManager.getSerialNumberForUser(request.user) to request.packageName
                }.distinct()
                if (distinct.any { (serial, _) ->
                        !AppCatalogReconciler.canUpdatePackage(
                            _catalog.value.profiles, profiles, serial,
                            activeLocaleTags, localeTags(),
                        )
                    }
                ) return@withContext null
                distinct.forEach { (serial, packageName) ->
                    val record = records.first { it.profile.serialNumber == serial }
                    val replacement = queryActivities(record, packageName)
                    apps = AppCatalogReconciler.replacePackage(
                        apps, serial, packageName, replacement,
                    )
                }
                CatalogData(
                    profiles,
                    apps,
                )
            }
        }
        result.fold(
            onSuccess = { data ->
                if (data == null) loadCatalog() else publish(data)
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                loadCatalog()
            },
        )
    }

    private suspend fun publish(data: CatalogData) {
        val current = _catalog.value
        val changed = generation == 0L ||
            current.profiles != data.profiles || current.apps != data.apps
        val locale = localeTags()
        if (changed) {
            generation += 1
        }
        val shouldWrite = changed || activeLocaleTags != locale
        activeLocaleTags = locale
        _catalog.value = AppCatalog(
            generation = generation,
            profiles = data.profiles,
            apps = data.apps,
            isRefreshing = false,
        )
        if (shouldWrite) withContext(Dispatchers.IO) {
            cache.write(CachedAppCatalog(locale, data.profiles, data.apps))
        }
    }

    private fun queryCatalog(): CatalogData {
        val profileRecords = queryProfiles()
        val apps = AppCatalogReconciler.sorted(profileRecords.flatMap {
            queryActivities(it, null)
        })
        return CatalogData(profileRecords.map { it.profile }, apps)
    }

    private fun queryProfiles(): List<ProfileRecord> = launcherApps.profiles
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

    private fun queryActivities(
        profileRecord: ProfileRecord,
        packageName: String?,
    ): List<LaunchableApp> = buildList {
                val profileSerial = profileRecord.profile.serialNumber
                launcherApps.getActivityList(packageName, profileRecord.user)
                    .forEach { activity ->
                        val component = activity.componentName
                        if (packageName != null && component.packageName != packageName) return@forEach
                        add(
                            LaunchableApp(
                                key = AppKey(
                                    packageName = component.packageName,
                                    activityName = component.className,
                                    profileSerial = profileSerial,
                                ),
                                label = activity.label.toString(),
                                firstInstallTime = activity.firstInstallTime,
                                enabled = isEnabled(activity.applicationInfo),
                                loadingProgress =
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        activity.loadingProgress
                                    } else {
                                        null
                                    },
                                androidCategory =
                                    io.github.alexanderwilhelmsenberg.modular.organization.CategoryClassifier
                                        .categorySignal(activity.applicationInfo.category,
                                            (activity.applicationInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0),
                            ),
                        )
                    }
        }

    private fun isEnabled(info: ApplicationInfo): Boolean = info.enabled &&
        (info.flags and ApplicationInfo.FLAG_SUSPENDED) == 0

    private fun localeTags(): String = appContext.resources.configuration.locales.toLanguageTags()

    private fun registerProfileReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_LOCALE_CHANGED)
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

    private sealed interface RefreshRequest {
        data object Full : RefreshRequest
        data class Package(val packageName: String, val user: UserHandle) : RefreshRequest
    }
}

