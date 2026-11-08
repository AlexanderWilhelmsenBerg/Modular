package io.github.alexanderwilhelmsenberg.modular.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.alexanderwilhelmsenberg.modular.organization.SavedArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sinh
import kotlin.math.tan

/** Replaceable tile source; requested tiles are limited to the visible viewport. */
interface MapTileSource {
    suspend fun tile(zoom: Int, x: Int, y: Int): ImageBitmap?
    val attribution: String
}

/** OSM public tiles: identifiable UA, viewport requests only, 7-day cache, no offline prefetch. */
class OsmViewportTileSource(context: Context) : MapTileSource {
    override val attribution = "© OpenStreetMap contributors"
    private val cache = File(context.cacheDir, "osm_viewport_tiles")
    private val userAgent = "ModularLauncher/0.1 (https://github.com/AlexanderWilhelmsenBerg/Modular)"

    override suspend fun tile(zoom: Int, x: Int, y: Int): ImageBitmap? = withContext(Dispatchers.IO) {
        val n = 1 shl zoom
        if (y !in 0 until n) return@withContext null
        val wrappedX = ((x % n) + n) % n
        cache.mkdirs()
        val file = File(cache, "${zoom}_${wrappedX}_$y.png")
        val valid = file.isFile && System.currentTimeMillis() - file.lastModified() < 7L * 24 * 60 * 60 * 1000
        if (!valid) {
            val temp = File(cache, "${zoom}_${wrappedX}_$y.tmp")
            runCatching {
                val connection = URL("https://tile.openstreetmap.org/$zoom/$wrappedX/$y.png").openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", userAgent)
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                try {
                    if (connection.responseCode == 200) {
                        connection.inputStream.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                        if (temp.length() in 1..100_000) temp.renameTo(file) else temp.delete()
                    }
                } finally { connection.disconnect() }
            }
            temp.delete()
            cache.listFiles()?.filter { it.isFile && it.extension == "png" }
                ?.sortedByDescending { it.lastModified() }?.drop(128)?.forEach { it.delete() }
        }
        if (file.isFile) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
    }
}

private const val TILE = 256.0
private fun worldPoint(lat: Double, lon: Double, zoom: Int): Offset {
    val scale = TILE * (1 shl zoom)
    val safeLat = lat.coerceIn(-85.0511, 85.0511)
    val sinLat = kotlin.math.sin(Math.toRadians(safeLat))
    return Offset(((lon + 180.0) / 360.0 * scale).toFloat(),
        ((0.5 - ln((1 + sinLat) / (1 - sinLat)) / (4 * PI)) * scale).toFloat())
}
private fun geoPoint(x: Double, y: Double, zoom: Int): Pair<Double, Double> {
    val scale = TILE * (1 shl zoom)
    val longitude = (x / scale * 360.0 - 180.0).let { ((it + 180.0) % 360.0 + 360.0) % 360.0 - 180.0 }
    val latitude = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / scale)))).coerceIn(-85.0511, 85.0511)
    return latitude to longitude
}

@Composable
fun SavedAreaMapEditor(area: SavedArea, onAreaChange: (SavedArea) -> Unit,
    modifier: Modifier = Modifier, tileSource: MapTileSource? = null) {
    val context = LocalContext.current
    val source = tileSource ?: remember(context) { OsmViewportTileSource(context) }
    var zoom by remember(area.id) { mutableIntStateOf(if (area.latitude == 0.0 && area.longitude == 0.0) 3 else 13) }
    var zoomAccumulation by remember(area.id) { mutableStateOf(1f) }
    val latestArea by rememberUpdatedState(area)
    val latestZoom by rememberUpdatedState(zoom)
    val latestOnAreaChange by rememberUpdatedState(onAreaChange)
    var viewport by remember { mutableStateOf(IntSize(1, 1)) }
    var tiles by remember { mutableStateOf<Map<Pair<Int, Int>, ImageBitmap>>(emptyMap()) }
    val center = worldPoint(area.latitude, area.longitude, zoom)
    val minX = floor((center.x - viewport.width / 2.0) / TILE).toInt()
    val maxX = floor((center.x + viewport.width / 2.0) / TILE).toInt()
    val minY = floor((center.y - viewport.height / 2.0) / TILE).toInt()
    val maxY = floor((center.y + viewport.height / 2.0) / TILE).toInt()
    val needed = (minX..maxX).flatMap { x -> (minY..maxY).map { y -> x to y } }.take(16)
    LaunchedEffect(source, zoom, needed) {
        tiles = emptyMap()
        for ((x, y) in needed) {
            val bitmap = source.tile(zoom, x, y) ?: continue
            tiles = tiles + ((x to y) to bitmap)
        }
    }
    Box(modifier) {
        Canvas(Modifier.fillMaxWidth().height(260.dp).onSizeChanged { viewport = it }
            .pointerInput(area.id) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    zoomAccumulation *= zoomChange
                    val step = when {
                        zoomAccumulation > 1.25f -> 1
                        zoomAccumulation < 0.8f -> -1
                        else -> 0
                    }
                    if (step != 0) zoomAccumulation = 1f
                    val newZoom = (latestZoom + step).coerceIn(3, 18)
                    val current = worldPoint(latestArea.latitude, latestArea.longitude, newZoom)
                    val (lat, lon) = geoPoint(current.x - pan.x.toDouble(), current.y - pan.y.toDouble(), newZoom)
                    zoom = newZoom
                    latestOnAreaChange(latestArea.copy(latitude = lat, longitude = lon))
                }
            }) {
            drawRect(Color(0xFFE2E8EA))
            tiles.forEach { (key, bitmap) ->
                val left = (key.first * TILE - center.x + size.width / 2).toInt()
                val top = (key.second * TILE - center.y + size.height / 2).toInt()
                drawImage(bitmap, dstOffset = IntOffset(left, top), dstSize = IntSize(256, 256))
            }
            val metersPerPixel = cos(Math.toRadians(area.latitude)) * 2 * PI * 6378137.0 /
                (TILE * 2.0.pow(zoom))
            val radiusPx = (area.radiusMeters / metersPerPixel).toFloat().coerceAtMost(size.width * 4)
            drawCircle(Color(0x553675A9), radiusPx)
            drawCircle(Color(0xFF1D5C9B), radiusPx, style = Stroke(width = 3.dp.toPx()))
            drawCircle(Color.White, 5.dp.toPx())
            drawCircle(Color(0xFF1D5C9B), 3.dp.toPx())
        }
        Text(source.attribution, Modifier.padding(4.dp), style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF111111))
    }
    Row {
        TextButton(onClick = { zoom = (zoom - 1).coerceAtLeast(3) }, enabled = zoom > 3) { Text("Zoom out") }
        Text("$zoom", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { zoom = (zoom + 1).coerceAtMost(18) }, enabled = zoom < 18) { Text("Zoom in") }
    }
    Text("Pan to move the center; pinch to zoom. The circle is the saved area.",
        style = MaterialTheme.typography.bodySmall)
    Text("Radius: ${area.radiusMeters.toInt()} m", style = MaterialTheme.typography.bodySmall)
    val range = ln(100_000.0 / 25.0)
    Slider(value = (ln(area.radiusMeters.coerceIn(25.0, 100_000.0) / 25.0) / range).toFloat(),
        onValueChange = { position -> onAreaChange(area.copy(radiusMeters = 25.0 * exp(position.toDouble() * range))) })
    AreaNumericFields(area, onAreaChange)
}

@Composable
private fun AreaNumericFields(area: SavedArea, onAreaChange: (SavedArea) -> Unit) {
    var latitude by remember(area.id) { mutableStateOf(area.latitude.toString()) }
    var longitude by remember(area.id) { mutableStateOf(area.longitude.toString()) }
    var radius by remember(area.id) { mutableStateOf(area.radiusMeters.toString()) }
    LaunchedEffect(area.latitude) { latitude = area.latitude.toString() }
    LaunchedEffect(area.longitude) { longitude = area.longitude.toString() }
    LaunchedEffect(area.radiusMeters) { radius = area.radiusMeters.toString() }
    OutlinedTextField(latitude, { value ->
        latitude = value
        value.toDoubleOrNull()?.takeIf { it in -90.0..90.0 }?.let { onAreaChange(area.copy(latitude = it)) }
    }, label = { Text("Latitude") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = latitude.toDoubleOrNull()?.let { it in -90.0..90.0 } != true)
    OutlinedTextField(longitude, { value ->
        longitude = value
        value.toDoubleOrNull()?.takeIf { it in -180.0..180.0 }?.let { onAreaChange(area.copy(longitude = it)) }
    }, label = { Text("Longitude") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = longitude.toDoubleOrNull()?.let { it in -180.0..180.0 } != true)
    OutlinedTextField(radius, { value ->
        radius = value
        value.toDoubleOrNull()?.takeIf { it in 25.0..100_000.0 }?.let { onAreaChange(area.copy(radiusMeters = it)) }
    }, label = { Text("Radius (meters, 25–100,000)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        isError = radius.toDoubleOrNull()?.let { it in 25.0..100_000.0 } != true)
}
