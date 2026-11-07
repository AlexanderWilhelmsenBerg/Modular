package io.github.alexanderwilhelmsenberg.modular.appearance

import android.app.Activity
import android.app.WallpaperManager
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import android.graphics.Color as AndroidColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

private val lightColors = lightColorScheme(
    primary = Color(0xFF175EA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E9FF),
    onPrimaryContainer = Color(0xFF00315D),
    background = Color(0xFFFAFAFC),
    onBackground = Color(0xFF1A1B1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1B1E),
    surfaceVariant = Color(0xFFE8EAEE),
    onSurfaceVariant = Color(0xFF44474D),
    outline = Color(0xFF747780),
)

private val darkColors = darkColorScheme(
    primary = Color(0xFFA5CAFF),
    onPrimary = Color(0xFF00315D),
    primaryContainer = Color(0xFF004884),
    onPrimaryContainer = Color(0xFFD7E9FF),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE3E5EB),
    surface = Color(0xFF181B21),
    onSurface = Color(0xFFE3E5EB),
    surfaceVariant = Color(0xFF414750),
    onSurfaceVariant = Color(0xFFC1C6D0),
    outline = Color(0xFF8B919B),
)

private val amoledColors = darkColors.copy(
    primary = Color(0xFFB3D4FF),
    onPrimary = Color(0xFF002D55),
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
    surfaceVariant = Color.Black,
    onSurfaceVariant = Color(0xFFD1D6DE),
    surfaceDim = Color.Black,
    surfaceBright = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Color.Black,
    surfaceContainerHighest = Color.Black,
    surfaceTint = Color.Transparent,
    outline = Color(0xFF9FA8B4),
    outlineVariant = Color(0xFF5B616B),
)

@Composable
fun ModularTheme(theme: AppearanceTheme, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = when (theme) {
            AppearanceTheme.LIGHT -> lightColors
            AppearanceTheme.DARK -> darkColors
            AppearanceTheme.AMOLED -> amoledColors
        },
        content = content,
    )
}

/** Align edge-to-edge bars with the selected Compose surfaces on API 29–36. */
fun applySystemBarAppearance(activity: Activity, theme: AppearanceTheme) {
    val window = activity.window
    WindowCompat.setDecorFitsSystemWindows(window, false)
    window.statusBarColor = AndroidColor.TRANSPARENT
    window.navigationBarColor = AndroidColor.TRANSPARENT
    window.isStatusBarContrastEnforced = false
    window.isNavigationBarContrastEnforced = false
    window.decorView.setBackgroundColor(
        if (theme == AppearanceTheme.AMOLED) AndroidColor.BLACK
        else if (theme == AppearanceTheme.DARK) AndroidColor.rgb(17, 19, 24)
        else AndroidColor.rgb(250, 250, 252),
    )
    WindowInsetsControllerCompat(window, window.decorView).apply {
        isAppearanceLightStatusBars = theme == AppearanceTheme.LIGHT
        isAppearanceLightNavigationBars = theme == AppearanceTheme.LIGHT
    }
}

/** Returns whether wallpaper can actually be shown for this Home frame. */
fun applyHomeWindowAppearance(
    activity: Activity,
    theme: AppearanceTheme,
    wallpaperRequested: Boolean,
    homeVisible: Boolean,
): Boolean {
    val window = activity.window
    val useWallpaper = wallpaperRequested && homeVisible && runCatching {
        WallpaperManager.getInstance(activity).isWallpaperSupported
    }.getOrDefault(false)

    applySystemBarAppearance(activity, theme)
    if (useWallpaper) {
        // Transparent drawing requires an alpha-capable window surface. Otherwise
        // HWUI may preserve opaque-buffer pixels from scrolling or from Settings.
        window.setFormat(PixelFormat.TRANSLUCENT)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
        window.decorView.setBackgroundColor(AndroidColor.TRANSPARENT)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    } else {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
        window.setFormat(PixelFormat.OPAQUE)
        val color = when (theme) {
            AppearanceTheme.LIGHT -> AndroidColor.rgb(250, 250, 252)
            AppearanceTheme.DARK -> AndroidColor.rgb(17, 19, 24)
            AppearanceTheme.AMOLED -> AndroidColor.BLACK
        }
        window.setBackgroundDrawable(ColorDrawable(color))
        window.decorView.setBackgroundColor(color)
    }
    return useWallpaper
}
