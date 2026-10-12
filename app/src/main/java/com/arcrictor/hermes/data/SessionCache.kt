package com.arcrictor.hermes.data

import android.content.Context
import com.arcrictor.hermes.gateway.HistoryMessage
import com.arcrictor.hermes.gateway.SessionRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Disposable JSON-file cache: the session list + one transcript per session.
 * Shown instantly on open, replaced by the server snapshot. The server is the
 * source of truth — deleting this directory loses nothing.
 */
class SessionCache(context: Context) {
    private val dir = File(context.filesDir, "session-cache").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }

    fun sessionsFile() = File(dir, "sessions.json")
    private fun transcriptFile(storedId: String) =
        File(dir, "transcript-" + storedId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")

    /** Public for tests: the file a stored id maps to. */
    fun fileFor(storedId: String): File = transcriptFile(storedId)

    suspend fun readSessions(): List<SessionRow> = withContext(Dispatchers.IO) {
        read(sessionsFile()) ?: emptyList()
    }

    suspend fun writeSessions(rows: List<SessionRow>) = withContext(Dispatchers.IO) {
        sessionsFile().writeText(json.encodeToString(ListSerializer(SessionRow.serializer()), rows))
    }

    suspend fun readTranscript(storedId: String): List<HistoryMessage>? = withContext(Dispatchers.IO) {
        read(transcriptFile(storedId))
    }

    suspend fun writeTranscript(storedId: String, messages: List<HistoryMessage>) =
        withContext(Dispatchers.IO) {
            transcriptFile(storedId).writeText(
                json.encodeToString(ListSerializer(HistoryMessage.serializer()), messages),
            )
        }

    private inline fun <reified T> read(file: File): T? = try {
        if (!file.isFile) null
        else json.decodeFromString(file.readText())
    } catch (_: Exception) {
        null // corrupt cache: act as if empty
    }
}
