package io.github.alexanderwilhelmsenberg.modular.filesui.documents

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.FileInputStream

/** A bounded image thumbnail. Lazy-list disposal cancels loading and recycles the bitmap. */
@Composable
fun FileThumbnail(
    documentId: String,
    mimeType: String?,
    documentLoader: suspend (String, String) -> ParcelFileDescriptor?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    if (mimeType?.startsWith("image/") != true) return
    var bitmap by remember(documentId) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(documentId) {
        bitmap = runCatching {
            withContext(Dispatchers.IO) {
                val descriptor = documentLoader(documentId, "r") ?: return@withContext null
                val bytes = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val output = ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(chunk)
                        if (count < 0) break
                        if (output.size() + count > 8 * 1024 * 1024) return@withContext null
                        output.write(chunk, 0, count)
                    }
                    output.toByteArray()
                }
                currentCoroutineContext().ensureActive()
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                val sample = generateSequence(1) { it * 2 }.first { bounds.outWidth / it <= 256 && bounds.outHeight / it <= 256 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }.getOrNull()
    }
    DisposableEffect(bitmap) { val current = bitmap; onDispose { current?.recycle() } }
    Box(modifier.size(42.dp), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription, Modifier.size(42.dp), contentScale = ContentScale.Crop) }
    }
}
