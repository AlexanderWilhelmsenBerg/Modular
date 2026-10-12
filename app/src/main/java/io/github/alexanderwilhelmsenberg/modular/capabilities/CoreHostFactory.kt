package io.github.alexanderwilhelmsenberg.modular.capabilities

import android.content.ComponentName
import android.content.Context
import io.github.alexanderwilhelmsenberg.modular.apps.AppRepository
import io.github.alexanderwilhelmsenberg.modular.moduleapi.IModularHost

class CoreHostFactory(
    context: Context,
    private val appRepository: AppRepository,
) {
    private val appContext = context.applicationContext

    fun create(
        component: ComponentName,
        sessionId: String,
    ): CoreHostSession {
        val binder = CoreHostBinder(
            context = appContext,
            expectedPackage = component.packageName,
            sessionId = sessionId,
            appRepository = appRepository,
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
