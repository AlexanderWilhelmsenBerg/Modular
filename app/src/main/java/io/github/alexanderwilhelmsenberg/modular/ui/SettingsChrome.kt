package io.github.alexanderwilhelmsenberg.modular.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class SettingsIcon {
    PRODUCTIVITY, APPEARANCE, ADDONS, ADVANCED, STAR, CATEGORIES, FOLDER,
    SORT, WALLPAPER, APPS, HIDDEN, INFO, HOME, DIAGNOSTICS,
}

/** Flat, full-width settings row. The icon starts at 24dp and the text at 76dp. */
@Composable
internal fun SettingsRow(
    title: String,
    description: String?,
    icon: SettingsIcon,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val ink = MaterialTheme.colorScheme.onBackground
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(start = 24.dp, end = 24.dp, top = 13.dp, bottom = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsGlyph(icon, Modifier.size(24.dp), if (enabled) ink else secondary)
        Spacer(Modifier.width(28.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                color = if (enabled) ink else secondary,
                fontSize = 20.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Normal,
                lineHeight = 25.sp,
                style = MaterialTheme.typography.titleMedium,
            )
            if (description != null) {
                Text(
                    description,
                    color = secondary,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        trailing?.let {
            Spacer(Modifier.width(12.dp))
            it()
        }
    }
}

@Composable
internal fun SettingsSectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(start = 76.dp, end = 24.dp, top = 24.dp, bottom = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
internal fun SettingsGlyph(
    icon: SettingsIcon,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onBackground,
) {
    Canvas(modifier) {
        val u = size.width / 24f
        val stroke = 1.7f * u
        fun pt(x: Float, y: Float) = Offset(x * u, y * u)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, pt(x1, y1), pt(x2, y2), stroke, cap = StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float, r: Float = 2f) =
            drawRoundRect(color, pt(x, y), Size(w * u, h * u), CornerRadius(r * u), style = Stroke(stroke))
        fun circle(x: Float, y: Float, r: Float) =
            drawCircle(color, r * u, pt(x, y), style = Stroke(stroke))
        fun path(vararg points: Pair<Float, Float>) {
            val p = Path().apply {
                points.forEachIndexed { index, pair ->
                    if (index == 0) moveTo(pair.first * u, pair.second * u)
                    else lineTo(pair.first * u, pair.second * u)
                }
            }
            drawPath(p, color, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        when (icon) {
            SettingsIcon.PRODUCTIVITY -> {
                box(3f, 4f, 18f, 16f)
                path(7f to 12f, 10.5f to 15.5f, 17.5f to 8.5f)
            }
            SettingsIcon.APPEARANCE -> {
                circle(12f, 12f, 4f)
                listOf(0, 45, 90, 135, 180, 225, 270, 315).forEach { angle ->
                    val a = Math.toRadians(angle.toDouble())
                    line(
                        (12 + 7 * kotlin.math.cos(a)).toFloat(),
                        (12 + 7 * kotlin.math.sin(a)).toFloat(),
                        (12 + 9 * kotlin.math.cos(a)).toFloat(),
                        (12 + 9 * kotlin.math.sin(a)).toFloat(),
                    )
                }
            }
            SettingsIcon.ADDONS -> {
                box(3f, 3f, 7f, 7f, 1f); box(14f, 3f, 7f, 7f, 1f)
                box(3f, 14f, 7f, 7f, 1f); box(14f, 14f, 7f, 7f, 1f)
            }
            SettingsIcon.ADVANCED -> {
                line(3f, 6f, 21f, 6f); line(3f, 12f, 21f, 12f); line(3f, 18f, 21f, 18f)
                circle(8f, 6f, 2f); circle(16f, 12f, 2f); circle(9f, 18f, 2f)
            }
            SettingsIcon.STAR -> path(
                12f to 2f, 14.8f to 8.7f, 22f to 9.3f, 16.5f to 14f,
                18.2f to 21f, 12f to 17.2f, 5.8f to 21f, 7.5f to 14f,
                2f to 9.3f, 9.2f to 8.7f, 12f to 2f,
            )
            SettingsIcon.CATEGORIES, SettingsIcon.APPS -> {
                box(3f, 3f, 7f, 7f, 1f); box(14f, 3f, 7f, 7f, 1f)
                box(3f, 14f, 7f, 7f, 1f); box(14f, 14f, 7f, 7f, 1f)
            }
            SettingsIcon.FOLDER -> path(
                2f to 7f, 2f to 19f, 22f to 19f, 22f to 8f,
                11f to 8f, 9f to 5f, 2f to 5f, 2f to 7f,
            )
            SettingsIcon.SORT -> {
                line(4f, 6f, 20f, 6f); line(4f, 12f, 16f, 12f); line(4f, 18f, 12f, 18f)
            }
            SettingsIcon.WALLPAPER -> {
                box(2f, 3f, 20f, 18f)
                circle(17f, 8f, 2f)
                path(3f to 18f, 9f to 11f, 13f to 15f, 15f to 13f, 21f to 19f)
            }
            SettingsIcon.HIDDEN -> {
                path(2f to 12f, 6f to 8f, 12f to 6f, 18f to 8f, 22f to 12f)
                path(2f to 12f, 6f to 16f, 12f to 18f, 18f to 16f, 22f to 12f)
                circle(12f, 12f, 3f); line(3f, 21f, 21f, 3f)
            }
            SettingsIcon.INFO -> {
                circle(12f, 12f, 9f); line(12f, 11f, 12f, 17f)
                drawCircle(color, 1f * u, pt(12f, 7f))
            }
            SettingsIcon.HOME -> path(
                2f to 11f, 12f to 3f, 22f to 11f, 20f to 11f,
                20f to 21f, 4f to 21f, 4f to 11f,
            )
            SettingsIcon.DIAGNOSTICS -> {
                path(2f to 12f, 7f to 12f, 10f to 5f, 14f to 19f, 17f to 12f, 22f to 12f)
            }
        }
    }
}
