package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CapabilityId
import io.github.alexanderwilhelmsenberg.modular.moduleapi.CoreCapabilities
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost

/**
 * Creates revocable host sessions and applies the Phase-1 bootstrap trust rule.
 *
 * Until explicit user-managed capability grants exist, only modules signed by
 * the same signer as Core receive Core-owned capabilities.
 */
class CoreHostFactory(
    context: Context,
    private val appRepository: AppRepository,
) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val corePackage = appContext.packageName

    fun create(
        component: ComponentName,
        sessionId: String,
    ): CoreHostSession {
        val binder = CoreHostBinder(
            context = appContext,
            expectedPackage = component.packageName,
            sessionId = sessionId,
            appRepository = appRepository,
            allowedCapabilities = bootstrapCapabilities(component.packageName),
        )

        return CoreHostSession(
            host = binder,
            revokeAction = binder::revoke,
        )
    }

    private fun bootstrapCapabilities(
        modulePackage: String,
    ): Set<CapabilityId> {
        val sameSigner = packageManager.checkSignatures(
            corePackage,
            modulePackage,
        ) == PackageManager.SIGNATURE_MATCH

        return if (sameSigner) {
            CoreCapabilities.all.toSet()
        } else {
            emptySet()
        }
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
