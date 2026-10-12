package com.arcrictor.hermes.diagnostics

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.arcrictor.hermes.gateway.Redact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * In-memory redacted protocol log (SPEC.md R1.4/R1.5). This is the single redaction
 * point: every line is redacted on entry, so callers may pass raw text.
 * [export] writes a plain-text capture for the share sheet.
 */
class CaptureLog(private val maxLines: Int = 2000) {
    private val lock = Any()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun add(line: String) {
        val stamped = "${LocalTime.now().format(STAMP)} ${Redact.text(line)}"
        synchronized(lock) {
            val cur = _lines.value
            _lines.value = if (cur.size < maxLines) cur + stamped else cur.drop(cur.size - maxLines + 1) + stamped
        }
    }

    fun clear() = synchronized(lock) { _lines.value = emptyList() }

    suspend fun export(context: Context): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the latest capture
        val file = File(dir, "hermes-capture-${System.currentTimeMillis()}.txt")
        file.writeText(_lines.value.joinToString("\n"))
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
