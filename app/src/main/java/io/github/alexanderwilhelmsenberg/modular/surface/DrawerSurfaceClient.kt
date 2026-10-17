package io.github.alexanderwilhelmsenberg.modular.surface

import io.github.alexanderwilhelmsenberg.modular.apps.LaunchableApp
import io.github.alexanderwilhelmsenberg.modular.capabilities.AppSortClient
import io.github.alexanderwilhelmsenberg.modular.capabilities.BoundedBinderCalls
import io.github.alexanderwilhelmsenberg.modular.capabilities.CapabilityRouter
import io.github.alexanderwilhelmsenberg.modular.composition.ActiveContextState
import io.github.alexanderwilhelmsenberg.modular.moduleapi.StandardCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityManager
import io.github.alexanderwilhelmsenberg.modular.moduleapi.capabilities.ISurfaceDrawerCapability
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppKeyParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortItemParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.AppSortRequestParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceActionResultParcel
import io.github.alexanderwilhelmsenberg.modular.moduleapi.model.SurfaceDocumentParcel
import java.util.Locale
import kotlinx.coroutines.CancellationException

class DrawerSurfaceClient(private val router: CapabilityRouter, private val security: ModuleSecurityManager) {
    suspend fun render(active: ActiveContextState, apps: List<LaunchableApp>): Result<SurfaceDocumentParcel> = attempt {
        val drawer = requireNotNull(active.resolvedBindings.singleOrNull {
            it.capability == StandardCapabilities.SurfaceDrawer
        }) { "No unambiguous Drawer binding" }
        val packageName = requireNotNull(drawer.route.component).packageName
        val authorization = security.status(packageName, router.requestedCoreCapabilities(drawer.route))
        require(CoreCapabilities.AppsCatalog in authorization.effectiveGrants) {
            "Drawer app catalog permission is not granted"
        }
        val sorter = requireNotNull(active.routeFor(StandardCapabilities.AppsSort)) { "No Sort binding" }
        val ordered = AppSortClient(router).sort(sorter, apps).getOrThrow()
        val items = ordered.map {
            AppSortItemParcel(AppKeyParcel(it.key.packageName, it.key.activityName, it.key.profileSerial), it.label.take(512))
        }
        require(items.sumOf { it.label.length + it.key.packageName.length + it.key.activityName.length } <= 65_536) {
            "Catalog exceeds Drawer transport budget"
        }
        val binder = router.acquireModuleBinder(drawer.route).getOrThrow()
        BoundedBinderCalls.call("drawer:${drawer.route.component}") {
            val document = ISurfaceDrawerCapability.Stub.asInterface(binder).render(
                drawer.instance.id, drawer.instance.configurationJson,
                AppSortRequestParcel(items, Locale.getDefault().toLanguageTag()),
            )
            SurfaceDocumentValidator.validate(document).getOrThrow()
        }.getOrThrow()
    }

    suspend fun performAction(active: ActiveContextState, actionId: String): Result<Unit> = attempt {
        val drawer = requireNotNull(active.resolvedBindings.singleOrNull {
            it.capability == StandardCapabilities.SurfaceDrawer
        }) { "No unambiguous Drawer binding" }
        require(actionId.matches(Regex("^[A-Za-z0-9._:-]{1,96}$")))
        val binder = router.acquireModuleBinder(drawer.route).getOrThrow()
        BoundedBinderCalls.call("drawer:${drawer.route.component}") {
            val result = ISurfaceDrawerCapability.Stub.asInterface(binder).performAction(drawer.instance.id, actionId)
            require(result.status == SurfaceActionResultParcel.STATUS_SUCCESS) { result.message ?: "Drawer action failed" }
        }.getOrThrow()
    }

    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }
}
