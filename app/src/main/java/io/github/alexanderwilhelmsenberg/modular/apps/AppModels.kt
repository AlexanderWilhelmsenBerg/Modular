package io.github.alexanderwilhelmsenberg.modular.apps

data class AppKey(
    val packageName: String,
    val activityName: String,
    val profileSerial: Long,
) {
    val stableId: String
        get() = profileSerial.toString() + ":" + packageName + "/" + activityName
}

data class LaunchableApp(
    val key: AppKey,
    val label: String,
    val firstInstallTime: Long,
    val enabled: Boolean,
    val loadingProgress: Float?,
)

data class AppProfile(
    val serialNumber: Long,
    val quietModeEnabled: Boolean,
)

data class AppCatalog(
    val generation: Long = 0,
    val profiles: List<AppProfile> = emptyList(),
    val apps: List<LaunchableApp> = emptyList(),
    val isRefreshing: Boolean = false,
    val lastError: String? = null,
)
