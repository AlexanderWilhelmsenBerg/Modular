package io.github.alexanderwilhelmsenberg.modular.organization

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.coroutines.resume

/** One foreground-only collector shared by all folder rules. Call stop on pause/hidden Home. */
class FolderConditionCoordinator(
    context: Context,
    private val store: SavedConditionStore,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val locationSource: ForegroundLocationSource = AndroidForegroundLocationSource(context),
) {
    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val locationManager = app.getSystemService(LocationManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableStatuses = MutableStateFlow<Map<String, FolderVisibilityStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, FolderVisibilityStatus>> = mutableStatuses
    private var folders = emptyList<FolderDefinition>()
    private var definitions = emptyMap<String, FolderCondition>()
    private var foreground = false
    private var signals = FolderSignals()
    private val retainedAreas = mutableMapOf<String, RetainedAreaDecision>()
    private var locationJob: Job? = null
    private var locationExpiryJob: Job? = null
    private var scheduleJob: Job? = null
    private val locationBudget = LocationRequestBudget()
    private var audioRegistered = false
    private var networkRegistered = false
    private var timeRegistered = false
    private var locationModeRegistered = false
    private var needsAreaActive = false

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }
    private val networkCallback: ConnectivityManager.NetworkCallback = if (Build.VERSION.SDK_INT >= 31)
        object : ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            override fun onAvailable(network: Network) = refreshOnMain()
            override fun onLost(network: Network) = refreshOnMain()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refreshOnMain()
        }
    else object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshOnMain()
        override fun onLost(network: Network) = refreshOnMain()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = refreshOnMain()
    }
    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }
    private val locationModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!foreground) return
            locationJob?.cancel(); locationJob = null
            locationExpiryJob?.cancel(); locationExpiryJob = null
            signals = signals.copy(locationFix = null)
            retainedAreas.clear()
            refresh()
            if (needsAreaActive && locationManager.isLocationEnabled) refreshLocation()
        }
    }

    fun start(currentFolders: List<FolderDefinition>) {
        folders = currentFolders
        definitions = store.loadAll(currentFolders.flatMap { it.conditionIds })
        val needed = currentFolders.filter { it.visibilityMode == FolderVisibilityMode.SMART }
            .flatMap { folder -> folder.conditionIds.mapNotNull(definitions::get) }.filter { it.enabled }
        val needsHeadset = needed.any { it is FolderCondition.Headset }
        val needsWifi = needed.any { it is FolderCondition.Wifi }
        val needsArea = needed.any { it is FolderCondition.Area }
        val needsTime = needed.any { it is FolderCondition.Schedule }
        needsAreaActive = needsArea
        if (!needsArea) {
            locationJob?.cancel(); locationJob = null
            locationExpiryJob?.cancel(); locationExpiryJob = null
            signals = signals.copy(locationFix = null)
            retainedAreas.clear()
        }
        if (needsHeadset && !audioRegistered) {
            audio.registerAudioDeviceCallback(audioCallback, Handler(Looper.getMainLooper())); audioRegistered = true
        } else if (!needsHeadset && audioRegistered) {
            audio.unregisterAudioDeviceCallback(audioCallback); audioRegistered = false
        }
        if (needsWifi && !networkRegistered) {
            runCatching { connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                networkCallback) }
                .onSuccess { networkRegistered = true }
        } else if (!needsWifi && networkRegistered) {
            connectivity.unregisterNetworkCallback(networkCallback); networkRegistered = false
        }
        if (needsTime && !timeRegistered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
            }
            app.registerReceiver(timeReceiver, filter); timeRegistered = true
        } else if (!needsTime && timeRegistered) {
            app.unregisterReceiver(timeReceiver); timeRegistered = false
        }
        if ((needsArea || needsWifi) && !locationModeRegistered) {
            val filter = IntentFilter().apply {
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            }
            app.registerReceiver(locationModeReceiver, filter); locationModeRegistered = true
        } else if (!needsArea && !needsWifi && locationModeRegistered) {
            app.unregisterReceiver(locationModeReceiver); locationModeRegistered = false
        }
        foreground = true
        if (needsArea && hasLocationPermission()) {
            signals = signals.copy(locationFix = locationSource.recentFix())
            scheduleLocationExpiry()
        }
        refresh()
        if (needsArea && signals.locationFix?.let {
                clock.instant().epochSecond - it.capturedAt.epochSecond in 0..FolderRuleEvaluator.MAX_FIX_AGE_SECONDS
            } != true) refreshLocation()
        scheduleJob?.cancel()
        if (needsTime) scheduleJob = scope.launch {
            while (foreground) {
                // Schedules are wall-clock evaluated, so DST and timezone changes never use elapsed 24h arithmetic.
                val now = currentWallClock(clock)
                val millisToNextMinute = (60 - now.second) * 1000L - now.nano / 1_000_000L
                delay(millisToNextMinute.coerceAtLeast(100L))
                refresh()
            }
        }
    }

    fun stop() {
        foreground = false
        needsAreaActive = false
        locationJob?.cancel(); locationJob = null
        locationExpiryJob?.cancel(); locationExpiryJob = null
        scheduleJob?.cancel(); scheduleJob = null
        if (audioRegistered) { audio.unregisterAudioDeviceCallback(audioCallback); audioRegistered = false }
        if (networkRegistered) { connectivity.unregisterNetworkCallback(networkCallback); networkRegistered = false }
        if (timeRegistered) { app.unregisterReceiver(timeReceiver); timeRegistered = false }
        if (locationModeRegistered) { app.unregisterReceiver(locationModeReceiver); locationModeRegistered = false }
        signals = FolderSignals()
        retainedAreas.clear()
        mutableStatuses.value = emptyMap()
    }

    fun close() { stop(); scope.cancel() }

    /** Explicit retry from Settings remains rate bounded. */
    fun retryLocation() { if (foreground && needsAreaActive) refreshLocation() }

    private fun refreshOnMain() { scope.launch { refresh() } }

    private fun refresh() {
        if (!foreground) return
        val locationPermission = hasLocationPermission()
        val locationEnabled = locationManager.isLocationEnabled
        val wifiPermission = ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED && locationEnabled
        if (!locationPermission || !locationEnabled) retainedAreas.clear()
        signals = signals.copy(
            headsetOutputs = readHeadsets(),
            connectedSsid = if (wifiPermission) readConnectedWifi() else null,
            locationPermission = locationPermission,
            locationEnabled = locationEnabled,
            wifiPermission = wifiPermission,
        )
        val now = currentWallClock(clock)
        val next = folders.associate { folder ->
            folder.id to FolderRuleEvaluator.evaluate(folder, definitions, signals, now, retainedAreas)
        }
        next.values.forEach { status ->
            status.conditions.forEach { (id, result) ->
                val rule = definitions[id]
                if (rule is FolderCondition.Area && result.reason != ConditionReason.BOUNDARY_UNCERTAIN &&
                    result.outcome != ConditionOutcome.UNKNOWN) {
                    retainedAreas[id] = RetainedAreaDecision(result.outcome, result.evaluatedAt)
                }
            }
        }
        mutableStatuses.value = next
    }

    private fun readHeadsets(): Set<HeadsetKind>? = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .mapNotNull { device -> when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> HeadsetKind.WIRED
            AudioDeviceInfo.TYPE_USB_HEADSET -> HeadsetKind.USB
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET -> HeadsetKind.BLUETOOTH
            else -> null
        } }.toSet() }.getOrNull()

    @Suppress("DEPRECATION")
    private fun readConnectedWifi(): String? = runCatching {
        val wifiNetwork = connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        } ?: return@runCatching ""
        val networkSsid = (connectivity.getNetworkCapabilities(wifiNetwork)?.transportInfo as? WifiInfo)?.ssid
        chooseConnectedSsid(networkSsid) {
            app.getSystemService(WifiManager::class.java)?.connectionInfo?.ssid
        }
    }.getOrNull()

    private fun hasLocationPermission(): Boolean = listOf(Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION).any {
        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshLocation() {
        if (!foreground || !needsAreaActive || !hasLocationPermission() || !locationManager.isLocationEnabled) return
        if (!locationBudget.claim(clock.instant())) return
        locationJob?.cancel()
        locationJob = scope.launch {
            val fix = withTimeoutOrNull(10_000L) { locationSource.currentFix() }
            if (foreground) {
                signals = signals.copy(locationFix = fix ?: locationSource.recentFix())
                refresh()
                scheduleLocationExpiry()
            }
        }
    }

    private fun scheduleLocationExpiry() {
        locationExpiryJob?.cancel()
        val fix = signals.locationFix ?: return
        val remaining = (fix.capturedAt.epochSecond + FolderRuleEvaluator.MAX_FIX_AGE_SECONDS + 1 -
            clock.instant().epochSecond).coerceAtLeast(0)
        if (remaining > 0) locationExpiryJob = scope.launch {
            delay(remaining * 1000)
            refresh()
            if (foreground) refreshLocation() // one shared bounded refresh at expiry
        }
    }
}

/** Android may redact one SSID source while the other still exposes the current identity. */
internal fun normalizeConnectedSsid(raw: String?): String? = raw?.removeSurrounding("\"")
    ?.takeUnless { it.isBlank() || it.equals("<unknown ssid>", ignoreCase = true) }

internal fun chooseConnectedSsid(transportSsid: String?, managerSsid: () -> String?): String? =
    normalizeConnectedSsid(transportSsid) ?: normalizeConnectedSsid(managerSsid())

/** The injected clock supplies the instant; the current device zone supplies wall-clock rules. */
internal fun currentWallClock(clock: Clock): ZonedDateTime =
    ZonedDateTime.ofInstant(clock.instant(), ZoneId.systemDefault())

interface ForegroundLocationSource {
    fun recentFix(): LocationFix?
    suspend fun currentFix(): LocationFix?
}

/** Uses platform network/balanced provider only; never starts continuous GPS updates. */
class AndroidForegroundLocationSource(context: Context) : ForegroundLocationSource {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)

    @Suppress("MissingPermission")
    override fun recentFix(): LocationFix? = runCatching {
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { provider -> manager.getLastKnownLocation(provider) }
            .maxByOrNull { it.time }?.toFix()
    }.getOrNull()

    @Suppress("MissingPermission")
    override suspend fun currentFix(): LocationFix? = runCatching {
        if (!manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return recentFix()
        suspendCancellableCoroutine { continuation ->
            if (Build.VERSION.SDK_INT >= 30) {
                val cancel = CancellationSignal()
                continuation.invokeOnCancellation { cancel.cancel() }
                manager.getCurrentLocation(LocationManager.NETWORK_PROVIDER, cancel,
                    ContextCompat.getMainExecutor(app)) { location ->
                    if (continuation.isActive) continuation.resume(location?.toFix())
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(location.toFix())
                    }
                }
                continuation.invokeOnCancellation { manager.removeUpdates(listener) }
                manager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
            }
        }
    }.getOrNull()

    private fun Location.toFix() = LocationFix(latitude, longitude,
        if (hasAccuracy()) accuracy.toDouble() else Double.NaN, Instant.ofEpochMilli(time))
}
