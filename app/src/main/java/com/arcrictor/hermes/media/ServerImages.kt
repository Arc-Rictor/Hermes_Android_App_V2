package com.arcrictor.hermes.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.arcrictor.hermes.gateway.ImageRef
import com.arcrictor.hermes.gateway.SessionsApi
import java.util.concurrent.ConcurrentHashMap

/**
 * `@image:` attachments live on the gateway; fetch via `GET /api/media?path=`
 * (PROTOCOL.md §1) and hold decoded bitmaps in memory only.
 */
class ServerImages(private val api: SessionsApi) {
    /** ~32 MB of decoded pixels, LRU by byte size. */
    private val cache = object : LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val failed = ConcurrentHashMap.newKeySet<String>()

    suspend fun bitmap(path: String): Bitmap? {
        cache.get(path)?.let { return it }
        if (path in failed) return null
        return try {
            val bytes = api.fetchMedia(path)
            withContext(Dispatchers.Default) { decodeSampled(bytes, MAX_EDGE) }
                ?.also { cache.put(path, it) }
                ?: throw IllegalArgumentException("not an image")
        } catch (_: Exception) {
            failed.add(path)
            null
        }
    }

    companion object {
        const val MAX_EDGE = 1568

        /** Decodes at the largest power-of-two subsample that keeps the long edge ≥ [maxEdge]. */
        fun decodeSampled(bytes: ByteArray, maxEdge: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(maxOf(bounds.outWidth, bounds.outHeight), maxEdge)
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }

        /** Pure, testable: power-of-two divisor so longEdge / size stays ≥ maxEdge. */
        fun sampleSize(longEdge: Int, maxEdge: Int): Int {
            var size = 1
            while (longEdge / (size * 2) >= maxEdge) size *= 2
            return size
        }
    }
}

/** Thumbnail for one `@image:` ref; blank box while loading, nothing on failure. */
@Composable
fun ServerImage(ref: ImageRef, images: ServerImages, modifier: Modifier = Modifier) {
    var bmp by remember(ref.path) { mutableStateOf<Bitmap?>(null) }
    var done by remember(ref.path) { mutableStateOf(false) }
    LaunchedEffect(ref.path) {
        bmp = images.bitmap(ref.path)
        done = true
    }
    if (bmp != null) {
        Image(
            bitmap = bmp!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.FillWidth,
            modifier = modifier,
        )
    } else if (!done) {
        Box(modifier.size(96.dp)) { CircularProgressIndicator() }
    }
}
