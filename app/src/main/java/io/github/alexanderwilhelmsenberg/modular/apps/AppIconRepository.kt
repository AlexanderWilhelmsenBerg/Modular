package io.github.alexanderwilhelmsenberg.modular.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Exact activity/profile icons, with no label or profile badge substituted by Core. */
class AppIconRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val launcherApps = appContext.getSystemService(LauncherApps::class.java)
    private val userManager = appContext.getSystemService(UserManager::class.java)
    private val permits = Semaphore(4)
    private val cache = object : LruCache<IconCacheKey, ImageBitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: IconCacheKey, value: ImageBitmap): Int =
            (value.width.toLong() * value.height * 4L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    private val lock = Any()
    private var generation = 0L
    private var configurationHash: Int? = null
    private val mutableInvalidationVersion = MutableStateFlow(0L)
    val invalidationVersion: StateFlow<Long> = mutableInvalidationVersion

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = invalidatePackage(packageName)
        override fun onPackageRemoved(packageName: String, user: UserHandle) = invalidatePackage(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = invalidatePackage(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach(::invalidatePackage)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach(::invalidatePackage)
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) =
            packageNames.forEach(::invalidatePackage)
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) =
            packageNames.forEach(::invalidatePackage)
    }

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = invalidateAll()
    }

    init {
        // A process-scoped repository has one callback and one bounded cache for every Home row.
        runCatching { launcherApps?.registerCallback(packageCallback, Handler(Looper.getMainLooper())) }
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
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(profileReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appContext.registerReceiver(profileReceiver, filter)
            }
        }
    }

    suspend fun load(key: AppKey, densityDpi: Int, iconSizePx: Int, configHash: Int): ImageBitmap? {
        val cacheKey = IconCacheKey(key, densityDpi, iconSizePx.coerceIn(1, MAX_ICON_SIZE_PX), configHash)
        val requestedGeneration: Long
        synchronized(lock) {
            if (configurationHash != configHash) {
                configurationHash = configHash
                generation++
                cache.evictAll()
                mutableInvalidationVersion.value = generation
            }
            cache.get(cacheKey)?.let { return it }
            requestedGeneration = generation
        }
        return permits.withPermit {
            // Another row may have completed the same icon while this one waited.
            synchronized(lock) { cache.get(cacheKey) }?.let { return@withPermit it }
            val icon = try {
                withContext(Dispatchers.IO) { readPlatformIcon(cacheKey) }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                null // Retry on a later composition; failures are never cached.
            }
            if (icon != null) synchronized(lock) {
                if (generation == requestedGeneration) cache.put(cacheKey, icon)
            }
            icon
        }
    }

    private fun readPlatformIcon(request: IconCacheKey): ImageBitmap? {
        val launcher = launcherApps ?: return null
        val user = userManager?.getUserForSerialNumber(request.key.profileSerial) ?: return null
        val activity = launcher.getActivityList(request.key.packageName, user)
            .firstOrNull { it.componentName.packageName == request.key.packageName &&
                it.componentName.className == request.key.activityName } ?: return null
        val drawable = activity.getIcon(request.densityDpi) ?: return null
        val bitmap = Bitmap.createBitmap(request.iconSizePx, request.iconSizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, request.iconSizePx, request.iconSizePx)
        drawable.draw(Canvas(bitmap))
        return bitmap.asImageBitmap()
    }

    private fun invalidatePackage(packageName: String) = synchronized(lock) {
        generation++
        cache.snapshot().keys.filter { it.key.packageName == packageName }.forEach(cache::remove)
        mutableInvalidationVersion.value = generation
    }

    private fun invalidateAll() = synchronized(lock) {
        generation++
        cache.evictAll()
        mutableInvalidationVersion.value = generation
    }

    private data class IconCacheKey(
        val key: AppKey,
        val densityDpi: Int,
        val iconSizePx: Int,
        val configHash: Int,
    )

    companion object {
        private const val MAX_CACHE_BYTES = 8 * 1024 * 1024
        private const val MAX_ICON_SIZE_PX = 256
        @Volatile private var shared: AppIconRepository? = null

        fun shared(context: Context): AppIconRepository = shared ?: synchronized(this) {
            shared ?: AppIconRepository(context).also { shared = it }
        }
    }
}

/** Returns null while loading or if this exact activity/profile has no available icon. */
@Composable
fun rememberAppIcon(app: LaunchableApp): ImageBitmap? {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val repository = AppIconRepository.shared(context)
    val invalidationVersion by repository.invalidationVersion.collectAsState()
    val iconSizePx = (48f * density.density).roundToInt().coerceIn(1, 256)
    val densityDpi = configuration.densityDpi
    val configHash = configuration.hashCode()
    val icon by produceState<ImageBitmap?>(
        initialValue = null,
        repository,
        app.key,
        densityDpi,
        iconSizePx,
        configHash,
        invalidationVersion,
    ) {
        value = repository.load(app.key, densityDpi, iconSizePx, configHash)
    }
    return icon
}
