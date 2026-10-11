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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory redacted protocol log (SPEC.md R1.4/R1.5, PLAN.md Phase 1).
 * Everything is redacted on entry; [export] writes a `.jsonl` capture for the share sheet.
 */
class CaptureLog(private val maxLines: Int = 2000) {
    private val lock = Any()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun add(line: String) {
        val stamped = "${SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())} ${Redact.text(line)}"
        synchronized(lock) {
            val next = (_lines.value + stamped).takeLast(maxLines)
            _lines.value = next
        }
    }

    fun clear() {
        _lines.value = emptyList()
    }

    suspend fun export(context: Context): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "capture-${System.currentTimeMillis()}.jsonl")
        file.writeText(_lines.value.joinToString("\n"))
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
}
