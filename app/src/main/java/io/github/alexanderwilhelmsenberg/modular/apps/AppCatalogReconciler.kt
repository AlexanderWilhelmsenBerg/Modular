package io.github.alexanderwilhelmsenberg.modular.apps

internal object AppCatalogReconciler {
    private val order = compareBy<LaunchableApp>(
        { it.key.profileSerial }, { it.key.packageName }, { it.key.activityName },
    )

    fun replacePackage(
        current: List<LaunchableApp>,
        serial: Long,
        packageName: String,
        replacement: List<LaunchableApp>,
    ): List<LaunchableApp> {
        require(replacement.all {
            it.key.profileSerial == serial && it.key.packageName == packageName
        })
        return (current.filterNot {
            it.key.profileSerial == serial && it.key.packageName == packageName
        } + replacement).sortedWith(order)
    }

    fun sorted(apps: List<LaunchableApp>): List<LaunchableApp> = apps.sortedWith(order)

    fun canUpdatePackage(
        knownProfiles: List<AppProfile>,
        currentProfiles: List<AppProfile>,
        serial: Long,
        knownLocale: String,
        currentLocale: String,
    ): Boolean = knownProfiles == currentProfiles &&
        currentProfiles.any { it.serialNumber == serial } && knownLocale == currentLocale
}
