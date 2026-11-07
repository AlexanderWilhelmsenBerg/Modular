package io.github.alexanderwilhelmsenberg.modular.appearance

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode

/** Draw over the stationary system wallpaper, underneath the scrolling Home list. */
@Composable
fun WallpaperHomeScrim(dimmingPercent: Int, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        // Replace the previous frame, including when dimming is zero. SrcOver
        // would blend into retained rows/Settings pixels instead of clearing them.
        drawRect(
            color = Color.Black.copy(alpha = clampWallpaperDimming(dimmingPercent) / 100f),
            blendMode = BlendMode.Src,
        )
    }
}
