package io.github.alexanderwilhelmsenberg.modular.filesui

import android.os.IBinder
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.window.DialogProperties

/** Supplied only by the embedded Files surface. The standalone Activity uses normal dialogs. */
val LocalFilesWindowToken = staticCompositionLocalOf<IBinder?> { null }

@Composable
fun filesDialogProperties(): DialogProperties {
    val token = LocalFilesWindowToken.current ?: return DialogProperties()
    return DialogProperties(
        windowType = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
        windowToken = token,
    )
}
