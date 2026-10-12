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
    ): IModularHost =
        CoreHostBinder(
            context = appContext,
            expectedPackage = component.packageName,
            sessionId = sessionId,
            appRepository = appRepository,
        )
}
