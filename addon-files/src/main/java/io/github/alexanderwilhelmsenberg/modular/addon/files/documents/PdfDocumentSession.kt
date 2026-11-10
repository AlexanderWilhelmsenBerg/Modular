package io.github.alexanderwilhelmsenberg.modular.addon.files.documents

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.LoadParams
import android.graphics.pdf.PdfRenderer
import android.os.Build
import io.github.alexanderwilhelmsenberg.modular.addon.files.storage.FilesRepository
import java.io.Closeable
import java.io.IOException
import java.security.MessageDigest

data class PdfReadingPosition(val pageIndex: Int, val zoom: Float)
data class PdfPageInfo(val index: Int, val width: Int, val height: Int)
data class PdfTextMatch(val pageIndex: Int, val textStartIndex: Int)

/** One open PDF at a time. Page work must run off the main thread and be canceled when hidden. */
class PdfDocumentSession private constructor(
    private val context: Context,
    private val repository: FilesRepository,
    val fileId: String,
    val fingerprint: String,
    private val renderer: PdfRenderer,
) : Closeable {
    val pageCount: Int get() = renderer.pageCount
    val textFeaturesAvailable: Boolean get() = Build.VERSION.SDK_INT >= 35
    val passwordFeatureAvailable: Boolean get() = Build.VERSION.SDK_INT >= 35

    @Synchronized fun pageInfo(index: Int): PdfPageInfo {
        require(index in 0 until pageCount)
        return renderer.openPage(index).use { PdfPageInfo(index, it.width, it.height) }
    }

    /** Returns one bounded bitmap. Caller owns and must recycle it when the page leaves the viewport. */
    @Synchronized fun renderPage(index: Int, zoom: Float, maxPixels: Int = 4_000_000): Bitmap {
        require(index in 0 until pageCount)
        require(zoom.isFinite() && zoom in 0.25f..4f)
        require(maxPixels in 1..4_000_000)
        renderer.openPage(index).use { page ->
            val requestedWidth = (page.width * zoom).toInt().coerceAtLeast(1)
            val requestedHeight = (page.height * zoom).toInt().coerceAtLeast(1)
            val scale = minOf(1.0, kotlin.math.sqrt(maxPixels.toDouble() / requestedWidth.toDouble() / requestedHeight.toDouble()))
            val width = (requestedWidth * scale).toInt().coerceAtLeast(1)
            val height = (requestedHeight * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.WHITE)
            try {
                val matrix = Matrix().apply { setScale(width.toFloat() / page.width, height.toFloat() / page.height) }
                page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            } catch (error: Exception) { bitmap.recycle(); throw error }
        }
    }

    /** API 35+ exposes PDF text. An empty result can mean an image-only page. */
    @Synchronized fun pageText(index: Int, maxCharacters: Int = 100_000): String? {
        if (!textFeaturesAvailable) return null
        require(index in 0 until pageCount)
        require(maxCharacters in 1..100_000)
        return renderer.openPage(index).use { page ->
            buildString {
                for (block in page.textContents) {
                    if (length >= maxCharacters) break
                    append(block.text.take(maxCharacters - length))
                }
            }
        }
    }

    /** Search only requested pages so large PDFs are not scanned or retained at once. */
    @Synchronized fun searchPage(index: Int, query: String, maxMatches: Int = 100): List<PdfTextMatch>? {
        if (!textFeaturesAvailable) return null
        require(index in 0 until pageCount)
        require(query.length in 1..200 && maxMatches in 1..100)
        return renderer.openPage(index).use { page ->
            page.searchText(query).take(maxMatches).map { PdfTextMatch(index, it.textStartIndex) }
        }
    }

    fun readingPosition(): PdfReadingPosition? {
        val value = context.getSharedPreferences("files-pdf-positions", Context.MODE_PRIVATE)
            .getString(positionKey(), null) ?: return null
        val parts = value.split(',')
        val page = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val zoom = parts.getOrNull(1)?.toFloatOrNull() ?: return null
        if (page !in 0 until pageCount || !zoom.isFinite() || zoom !in 0.25f..4f) return null
        return PdfReadingPosition(page, zoom)
    }

    fun rememberPosition(pageIndex: Int, zoom: Float) {
        require(pageIndex in 0 until pageCount && zoom.isFinite() && zoom in 0.25f..4f)
        context.getSharedPreferences("files-pdf-positions", Context.MODE_PRIVATE)
            .edit().putString(positionKey(), "$pageIndex,$zoom").apply()
    }

    private fun positionKey(): String = MessageDigest.getInstance("SHA-256")
        .digest("$fileId:$fingerprint".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    override fun close() { renderer.close() }

    companion object {
        /** Password is used for this open only and is never persisted. API 29–34 cannot open encrypted PDFs. */
        fun open(context: Context, repository: FilesRepository, fileId: String, password: String? = null): PdfDocumentSession {
            if (password != null && Build.VERSION.SDK_INT < 35)
                throw UnsupportedOperationException("Password-protected PDFs require Android 15 or newer")
            val descriptor = repository.openRead(fileId)
            try {
                val renderer = if (Build.VERSION.SDK_INT >= 35) {
                    PdfRenderer(descriptor, LoadParams.Builder().apply { password?.let { setPassword(it) } }.build())
                } else PdfRenderer(descriptor)
                return PdfDocumentSession(context.applicationContext, repository, fileId, repository.fingerprint(fileId), renderer)
            } catch (error: Exception) {
                descriptor.close()
                throw IOException("Cannot open PDF; it may be encrypted, damaged, or unsupported by Android", error)
            }
        }
    }
}
