package com.arcrictor.hermes.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Pure, JVM-tested attachment math and naming (SPEC R4.2, PROTOCOL §6).
 * Bitmap/IO work lives in [Attachments] below.
 */
object AttachmentPlan {
    /** Longest side after conversion. */
    const val MAX_SIDE_PX = 1568
    const val JPEG_QUALITY = 80
    /** Raw-file ceiling: OkHttp refuses any WS message over 16 MiB; base64 adds ⅓. */
    const val MAX_RAW_BYTES = 11L * 1024 * 1024

    fun sizeOk(rawBytes: Long): Boolean = rawBytes <= MAX_RAW_BYTES

    /** Power-of-two `inSampleSize` so the longest side lands near the cap. */
    fun sampleSize(width: Int, height: Int): Int {
        var s = 1
        while (maxOf(width, height) / (s * 2) >= MAX_SIDE_PX) s *= 2
        return s
    }

    /** Exact dims after sampling: longest side ≤ cap, aspect kept. */
    fun targetSize(width: Int, height: Int): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= MAX_SIDE_PX) return width to height
        val scale = MAX_SIDE_PX.toDouble() / longest
        return maxOf(1, (width * scale).roundToInt()) to maxOf(1, (height * scale).roundToInt())
    }

    /** The server checks the filename suffix first, so every image is `*.jpg`. */
    fun jpgName(original: String?): String {
        val file = original?.substringAfterLast('/')?.substringAfterLast('\\')?.ifBlank { null }
            ?: return "image.jpg"
        val base = file.substringBeforeLast('.', missingDelimiterValue = file).ifBlank { "image" }
        return "$base.jpg"
    }

    /** `file.attach` queues nothing: its `ref_text` goes on its own line. */
    fun appendRefText(message: String, refText: String): String =
        if (message.isBlank()) refText else message.trimEnd() + "\n" + refText
}

/** A local file staged for the attach-then-submit send order. */
sealed interface StagedFile {
    val file: File
    val name: String

    data class Image(override val file: File, override val name: String) : StagedFile
    data class Pdf(override val file: File, override val name: String) : StagedFile
    data class Generic(override val file: File, override val name: String) : StagedFile
}

sealed interface ImportResult {
    data class Ready(val staged: StagedFile) : ImportResult
    data class Refused(val reason: String) : ImportResult
}

/**
 * Gallery/camera/file/share imports. Ported from V1 `data/Attachments.kt`
 * (downscale loop, filesDir staging) plus EXIF orientation and HEIC handling:
 * HEIC decodes via the platform decoder on current Pixels and is re-encoded
 * to JPEG like everything else.
 */
object Attachments {
    private const val TOO_LARGE = "File too large to send (max 11 MB)"

    fun import(context: Context, uri: Uri): ImportResult {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)
        val name = resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
        return when {
            mime?.startsWith("image/") == true || isImageExt(name) ->
                importImage(context, open = { resolver.openInputStream(uri) }, name)
            mime == "application/pdf" || name?.endsWith(".pdf", ignoreCase = true) == true ->
                stageCopy(context, uri, resolver, name ?: "document.pdf") { f, n -> StagedFile.Pdf(f, n) }
            else -> stageCopy(context, uri, resolver, name ?: "file") { f, n -> StagedFile.Generic(f, n) }
        }
    }

    /** In-app CameraX capture: already a JPEG file, still normalised through the pipeline. */
    fun importCapture(context: Context, file: File): ImportResult =
        importImage(context, open = { file.inputStream().buffered() }, file.name)

    fun encodeBase64(file: File): String =
        Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)

    fun newCaptureFile(context: Context): File {
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        return File(dir, "cap-${UUID.randomUUID()}.jpg")
    }

    private fun isImageExt(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase() ?: return false
        return ext in setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")
    }

    private fun stageCopy(
        context: Context,
        uri: Uri,
        resolver: android.content.ContentResolver,
        name: String,
        wrap: (File, String) -> StagedFile,
    ): ImportResult = runCatching {
        val out = stagedFile(context, name)
        var total = 0L
        resolver.openInputStream(uri)?.use { ins ->
            out.outputStream().use { outs ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > AttachmentPlan.MAX_RAW_BYTES) {
                        out.delete()
                        return ImportResult.Refused(TOO_LARGE)
                    }
                    outs.write(buf, 0, n)
                }
            }
        } ?: return ImportResult.Refused("Couldn't read that file")
        ImportResult.Ready(wrap(out, name))
    }.getOrElse { ImportResult.Refused("Couldn't read that file") }

    private fun importImage(
        context: Context,
        open: () -> InputStream?,
        name: String?,
    ): ImportResult = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ImportResult.Refused("Couldn't read that image")
        val rotation = open()?.use { ExifInterface(it).rotationDegrees() } ?: 0
        val options = BitmapFactory.Options().apply { inSampleSize = AttachmentPlan.sampleSize(bounds.outWidth, bounds.outHeight) }
        var bitmap = open()?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return ImportResult.Refused("Couldn't read that image")
        if (rotation != 0) {
            val m = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        val (tw, th) = AttachmentPlan.targetSize(bitmap.width, bitmap.height)
        if (bitmap.width != tw || bitmap.height != th) {
            val scaled = Bitmap.createScaledBitmap(bitmap, tw, th, true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        val out = stagedFile(context, AttachmentPlan.jpgName(name))
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, AttachmentPlan.JPEG_QUALITY, it) }
        bitmap.recycle()
        ImportResult.Ready(StagedFile.Image(out, out.name))
    }.getOrElse { ImportResult.Refused("Couldn't read that image") }

    private fun stagedFile(context: Context, name: String): File {
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val safe = name.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }
        var file = File(dir, safe)
        var i = 1
        while (file.exists()) file = File(dir, "${safe.substringBeforeLast('.')}-$i.${safe.substringAfterLast('.', "")}")
        return file
    }

    private fun ExifInterface.rotationDegrees(): Int = when (
        getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}
