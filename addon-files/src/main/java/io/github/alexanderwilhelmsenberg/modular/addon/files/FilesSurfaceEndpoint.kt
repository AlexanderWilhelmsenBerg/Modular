package io.github.alexanderwilhelmsenberg.modular.addon.files

import android.content.Context
import android.view.View
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import io.github.alexanderwilhelmsenberg.modular.modulesdk.ModuleInstanceContext
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceEndpoint
import io.github.alexanderwilhelmsenberg.modular.modulesdk.WorkspaceSurfaceScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The entire Files view is composed in the add-on process; Core receives only a SurfacePackage. */
class FilesSurfaceEndpoint(context: Context, instance: ModuleInstanceContext) :
    WorkspaceSurfaceEndpoint(context, instance) {
    private val controller = (context.applicationContext as FilesApplication).pageController

    override fun onCreateView(scope: WorkspaceSurfaceScope): View {
        return ComposeView(scope.context).apply {
            setContent {
                val host by scope.hostState.collectAsState()
                val preferred = scope.context.getSharedPreferences("files-page", Context.MODE_PRIVATE)
                    .getString("theme", "system") ?: "system"
                val hostDark = host.theme == "dark" || host.theme == "amoled" ||
                    (host.theme !in setOf("light", "dark", "amoled") && isSystemInDarkTheme())
                val dark = when (preferred) {
                    "dark", "amoled" -> true
                    "light" -> false
                    else -> hostDark
                }
                val amoled = preferred == "amoled" || (preferred == "system" && host.theme == "amoled")
                val base = if (amoled) darkColorScheme(background = Color.Black,
                    surface = Color.Black, surfaceContainer = Color(0xFF101010))
                else if (dark) darkColorScheme() else lightColorScheme()
                val scheme = if (preferred == "system") base.copy(
                    background = host.colors["background"]?.let { Color(it) } ?: base.background,
                    surface = host.colors["surface"]?.let { Color(it) } ?: base.surface,
                    onSurface = host.colors["onSurface"]?.let { Color(it) } ?: base.onSurface,
                    primary = host.colors["primary"]?.let { Color(it) } ?: base.primary,
                    onPrimary = host.colors["onPrimary"]?.let { Color(it) } ?: base.onPrimary,
                    primaryContainer = host.colors["primaryContainer"]?.let { Color(it) } ?: base.primaryContainer,
                    onPrimaryContainer = host.colors["onPrimaryContainer"]?.let { Color(it) } ?: base.onPrimaryContainer,
                ) else base
                MaterialTheme(colorScheme = scheme) {
                    FilesNativeContent(controller, refreshToken = 0,
                        onHostLaunch = scope::requestActivity,
                        onExternalDocument = { id ->
                            val nonce = withContext(Dispatchers.IO) {
                                controller.createExternalOpenHandoff(id)
                            }
                            scope.requestActivity(nonce)
                        },
                        onBackState = { canGoBack, exclusiveGestures, handler ->
                            scope.onBackRequested = handler
                            scope.updateState(canGoBack = canGoBack, exclusiveGestures = exclusiveGestures)
                        },
                        onRequestApps = { scope.requestNavigation("apps") })
                }
            }
        }
    }
}
