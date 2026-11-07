package io.github.alexanderwilhelmsenberg.modular.appearance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Draw over the stationary system wallpaper, underneath the scrolling Home list. */
@Composable
fun WallpaperHomeScrim(dimmingPercent: Int, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black.copy(alpha = clampWallpaperDimming(dimmingPercent) / 100f)))
}
