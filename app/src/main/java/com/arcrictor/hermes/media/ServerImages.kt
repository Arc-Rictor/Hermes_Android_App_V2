package com.arcrictor.hermes.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
    private val cache = ConcurrentHashMap<String, Bitmap>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    suspend fun bitmap(path: String): Bitmap? {
        cache[path]?.let { return it }
        if (path in failed) return null
        return try {
            val bytes = api.fetchMedia(path)
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalArgumentException("not an image")
            // Downscale memory hogs: cap at 1568 px like uploads.
            val scaled = if (maxOf(bmp.width, bmp.height) > 1568) {
                val f = 1568f / maxOf(bmp.width, bmp.height)
                Bitmap.createScaledBitmap(bmp, (bmp.width * f).toInt(), (bmp.height * f).toInt(), true)
            } else bmp
            cache[path] = scaled
            scaled
        } catch (_: Exception) {
            failed.add(path)
            null
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
