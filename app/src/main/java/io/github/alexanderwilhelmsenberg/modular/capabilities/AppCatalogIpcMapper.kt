package io.github.alexanderwilhelmsenberg.modular.capabilities

import io.github.alexanderwilhelmsenberg.modular.apps.AppCatalog
import io.github.alexanderwilhelmsenberg.modular.apps.AppKey
import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppCatalogInfoParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppCatalogPageParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppProfileParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.LaunchableAppParcel

object AppCatalogIpcMapper {
    const val MAX_PAGE_SIZE = 100

    fun info(catalog: AppCatalog): AppCatalogInfoParcel =
        AppCatalogInfoParcel(
            generation = catalog.generation,
            totalApps = catalog.apps.size,
            profiles = catalog.profiles.map { profile ->
                AppProfileParcel(
                    serialNumber = profile.serialNumber,
                    quietModeEnabled = profile.quietModeEnabled,
                )
            },
            refreshing = catalog.isRefreshing,
            error = catalog.lastError,
        )

    fun page(
        catalog: AppCatalog,
        expectedGeneration: Long,
        offset: Int,
        limit: Int,
    ): AppCatalogPageParcel {
        if (expectedGeneration != catalog.generation) {
            return AppCatalogPageParcel(
                status = AppCatalogPageParcel.STATUS_STALE_GENERATION,
                generation = catalog.generation,
                totalApps = catalog.apps.size,
                offset = 0,
                nextOffset = 0,
                hasMore = catalog.apps.isNotEmpty(),
                apps = emptyList(),
                error = "Catalog generation changed",
            )
        }

        if (offset < 0 || offset > catalog.apps.size || limit !in 1..MAX_PAGE_SIZE) {
            return AppCatalogPageParcel(
                status = AppCatalogPageParcel.STATUS_INVALID_ARGUMENT,
                generation = catalog.generation,
                totalApps = catalog.apps.size,
                offset = offset,
                nextOffset = offset,
                hasMore = false,
                apps = emptyList(),
                error = "offset must be within the catalog and limit must be 1.." +
                    MAX_PAGE_SIZE,
            )
        }

        val endExclusive = minOf(offset + limit, catalog.apps.size)
        val slice = catalog.apps.subList(offset, endExclusive)

        return AppCatalogPageParcel(
            status = AppCatalogPageParcel.STATUS_OK,
            generation = catalog.generation,
            totalApps = catalog.apps.size,
            offset = offset,
            nextOffset = endExclusive,
            hasMore = endExclusive < catalog.apps.size,
            apps = slice.map(::toParcel),
            error = null,
        )
    }

    fun fromParcel(key: AppKeyParcel): AppKey =
        AppKey(
            packageName = key.packageName,
            activityName = key.activityName,
            profileSerial = key.profileSerial,
        )

    private fun toParcel(app: LaunchableApp): LaunchableAppParcel =
        LaunchableAppParcel(
            key = AppKeyParcel(
                packageName = app.key.packageName,
                activityName = app.key.activityName,
                profileSerial = app.key.profileSerial,
            ),
            label = app.label,
            firstInstallTime = app.firstInstallTime,
            enabled = app.enabled,
            loadingProgress = app.loadingProgress,
        )
}
