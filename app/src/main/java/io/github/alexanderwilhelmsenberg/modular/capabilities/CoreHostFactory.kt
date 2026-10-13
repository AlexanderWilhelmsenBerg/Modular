package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.content.ComponentName
import android.content.Context
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost
import io.github.alexanderwilhelmsenberg.modular.security.ModuleSecurityManager

/**
 * Creates revocable Core-host sessions for verified module identities.
 *
 * The module's requested Core capabilities are not authority. The security
 * manager resolves the installed package/signing identity and applies its
 * persistent grant record before the session is opened.
 */
class CoreHostFactory(
    context: Context,
    private val appRepository: AppRepository,
    private val securityManager: ModuleSecurityManager,
) {
    private val appContext =
        context.applicationContext

    suspend fun create(
        component: ComponentName,
        sessionId: String,
        requestedCapabilities: Set<CapabilityId>,
    ): CoreHostSession {
        val authorization =
            securityManager.prepareSession(
                packageName = component.packageName,
                requestedCapabilities =
                    requestedCapabilities,
            )

        val binder = CoreHostBinder(
            context = appContext,
            expectedPackage = component.packageName,
            sessionId = sessionId,
            appRepository = appRepository,
            securityManager = securityManager,
            authorization = authorization,
        )

        return CoreHostSession(
            host = binder,
            revokeAction = binder::revoke,
        )
    }
}

class CoreHostSession internal constructor(
    val host: IModularHost,
    private val revokeAction: () -> Unit,
) {
    fun revoke() {
        revokeAction()
    }
}
