package io.github.alexanderwilhelmsenberg.modular.filesui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** Folder appearance is presentation data; unknown values always fall back to the theme default. */
internal data class FilesFolderAppearance(val icon: String = "folder", val color: String = "auto") {
    companion object {
        fun fromJson(value: JSONObject?): FilesFolderAppearance = FilesFolderAppearance(
            icon = value?.optString("icon")?.takeIf { it in iconPaths } ?: "folder",
            color = value?.optString("color")?.takeIf { it == "auto" || it.matches(Regex("#[0-9A-Fa-f]{6}")) } ?: "auto",
        )
    }
}

@Composable
internal fun FilesFolderGlyph(appearance: FilesFolderAppearance, modifier: Modifier = Modifier) {
    val base = if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFF6FA9F5) else Color(0xFFE5B83B)
    val color = if (appearance.color == "auto") base else runCatching {
        Color(android.graphics.Color.parseColor(appearance.color))
    }.getOrDefault(base)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            drawRoundRect(color.copy(alpha = .82f), Offset(w * .08f, h * .16f),
                Size(w * .43f, h * .27f), CornerRadius(w * .075f))
            drawRoundRect(color, Offset(w * .06f, h * .30f), Size(w * .88f, h * .60f),
                CornerRadius(w * .095f))
        }
        if (appearance.icon != "folder") {
            Icon(folderSymbol(appearance.icon), contentDescription = null,
                modifier = Modifier.size(20.dp).padding(top = 7.dp),
                tint = if (color.luminance() > .24f) Color(0xFF273444) else Color.White)
        }
    }
}

@Composable
internal fun FilesAppearanceChoice(
    label: String,
    appearance: FilesFolderAppearance,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RectangleShape
    Column(modifier.heightIn(min = 84.dp)
        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, shape)
        .border(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
        .clickable(role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = label }
        .testTag(tag)
        .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilesFolderGlyph(appearance, Modifier.size(36.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium)
    }
}

private val iconPaths = mapOf(
    "folder" to "",
    "game" to "M6 8h12l3 4v6h-4l-2-3H9l-2 3H3v-6z M7 11v4 M5 13h4 M16 12h1 M18 14h1",
    "audio" to "M4 10v4h3l4 4V6l-4 4z M15 8c2 2 2 6 0 8 M18 5c4 4 4 10 0 14",
    "music" to "M9 5v11a2 2 0 1 1-2-2c.4 0 .7.1 1 .2V7l10-2v9a2 2 0 1 1-2-2c.4 0 .7.1 1 .2V4z",
    "pictures" to "M3 5h18v14H3z M5 16l4-5 3 3 3-4 4 6z M7 8h2v2H7z",
    "video" to "M4 6h16v12H4z M10 9l6 3-6 3z",
    "downloads" to "M11 3h2v10l3-3 2 2-6 6-6-6 2-2 3 3z M4 19h16v2H4z",
    "documents" to "M6 2h9l4 4v16H6z M14 2v5h5 M9 11h7 M9 15h7 M9 19h5",
    "archive" to "M4 4h16v4H4z M6 8h12v13H6z M10 11h4v3h-4z",
    "camera" to "M4 7h4l2-3h4l2 3h4v13H4z M12 10a4 4 0 1 0 0 8 4 4 0 0 0 0-8",
    "work" to "M4 7h16v13H4z M9 7V4h6v3 M4 12h16 M10 11v3h4v-3",
    "home" to "M3 11l9-8 9 8 M5 10v11h14V10 M10 21v-7h4v7",
    "books" to "M4 4h5v16H4z M10 4h5v16h-5z M16 7l4-1 3 13-4 1z",
    "code" to "M9 7l-5 5 5 5 M15 7l5 5-5 5 M14 4l-4 16",
    "apps" to "M4 4h7v7H4z M13 4h7v7h-7z M4 13h7v7H4z M13 13h7v7h-7z",
    "star" to "M12 2l3 6 7 1-5 5 1 7-6-3-6 3 1-7-5-5 7-1z",
)

private val symbolCache = mutableMapOf<String, ImageVector>()
private fun folderSymbol(id: String): ImageVector = synchronized(symbolCache) {
    symbolCache.getOrPut(id) {
        ImageVector.Builder(name = "Folder $id", defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f)
            .addPath(pathData = PathParser().parsePathString(iconPaths[id] ?: iconPaths.getValue("star")).toNodes(),
                fill = SolidColor(Color.Black)).build()
    }
}
