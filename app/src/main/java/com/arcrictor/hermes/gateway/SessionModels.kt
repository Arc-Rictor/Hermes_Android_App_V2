package com.arcrictor.hermes.gateway

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** One row of `session.list`. `id` is the STORED id. See PROTOCOL.md §5. */
@Serializable
data class SessionRow(
    val id: String,
    val title: String = "",
    val preview: String = "",
    val started_at: Double = 0.0,
    val message_count: Int = 0,
    val source: String = "",
)

/** One transcript row from resume/history. Unknown keys are ignored. */
@Serializable
data class HistoryMessage(
    val role: String,
    val text: String? = null,
    val timestamp: Double? = null,
    val row_id: Long? = null,
    val display_kind: String? = null,
    val display_metadata: JsonElement? = null,
    // tool rows:
    val name: String? = null,
    val context: String? = null,
    val tool_call_id: String? = null,
    val args: JsonElement? = null,
    // assistant reasoning sidecars (rendered when plain strings):
    val reasoning: JsonElement? = null,
    val reasoning_content: JsonElement? = null,
)

/** `inflight` block of a resume snapshot: the turn still running (or retained failed one). */
@Serializable
data class InflightTurn(
    val user: String = "",
    val assistant: String = "",
    val streaming: Boolean = false,
    val display_kind: String? = null,
    val display_metadata: JsonElement? = null,
    val error: String? = null,
    val status: String? = null,
)

@Serializable
data class QueuedPrompt(val user: String)

/** Still-open server→client request, re-delivered on resume. */
@Serializable
data class OpenRequestEntry(
    val id: String,
    val method: String,
    val params: JsonObject = JsonObject(emptyMap()),
)

/** Result of `session.events.since` (all keys REQ per PROTOCOL.md §5). */
@Serializable
data class EventsSince(
    val events: List<JsonObject> = emptyList(),
    val latest_seq: Long = 0,
    val truncated: Boolean = false,
    val count: Int = 0,
    val epoch: String = "",
    val open_requests: List<OpenRequestEntry> = emptyList(),
) {
    /** Event items, tolerating both envelope shapes (see Replay.itemToEvent). */
    fun parsedEvents(): List<GatewayFrame.Event> = events.mapNotNull(Replay::itemToEvent)
}

/** Result of `session.create` / `session.resume` / `session.activate`. */
@Serializable
data class LiveSessionSnapshot(
    /** RUNTIME id — use for every call except resume/delete. */
    val session_id: String,
    val stored_session_id: String? = null,
    val resumed: String? = null,
    val session_key: String? = null,
    val message_count: Int = 0,
    val messages: List<HistoryMessage> = emptyList(),
    val info: JsonObject = JsonObject(emptyMap()),
    val inflight: InflightTurn? = null,
    val queued: QueuedPrompt? = null,
    val open_requests: List<OpenRequestEntry> = emptyList(),
    val running: Boolean? = null,
    val status: String? = null,
) {
    /** Durable id to persist: newest wins (compression can rotate it). */
    fun storedId(): String = session_key ?: resumed ?: stored_session_id ?: session_id
}
