package com.arcrictor.hermes.turn

import com.arcrictor.hermes.gateway.GatewayFrame
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Turn-level view of §7 event payloads. The UI never interprets frames;
 * everything it renders comes from [TurnState] via [TurnReducer].
 *
 * [from] returns null for frames Phase 3 does not render (session bookkeeping,
 * notifications, approval cards — later phases). Never throws on odd payloads.
 */
sealed interface GatewayEvent {
    data object MessageStart : GatewayEvent
    data class MessageDelta(val text: String) : GatewayEvent
    data class MessageInterim(val text: String, val alreadyStreamed: Boolean) : GatewayEvent
    data class MessageComplete(
        val text: String?,
        val usage: TurnUsage?,
        val status: String?,
        val error: String?,
    ) : GatewayEvent
    /** Covers thinking.delta (legacy), reasoning.delta and reasoning.available. */
    data class ReasoningDelta(val text: String) : GatewayEvent
    data class ToolGenerating(val name: String) : GatewayEvent
    data class ToolStart(val toolId: String, val name: String, val preview: String) : GatewayEvent
    data class ToolComplete(
        val toolId: String,
        val name: String,
        val durationS: Double?,
        val summary: String,
        val failed: Boolean,
    ) : GatewayEvent
    data class ToolRisk(val toolId: String) : GatewayEvent
    data class StatusUpdate(val text: String) : GatewayEvent
    data class SessionUsage(val usage: TurnUsage) : GatewayEvent
    /** Local: the user tapped Stop and `session.interrupt` was sent. */
    data object Interrupted : GatewayEvent

    companion object {
        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.number(key: String): Double? =
            (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

        private fun usageOf(obj: JsonObject): TurnUsage? {
            val input = obj.number("input")?.toLong() ?: return null
            return TurnUsage(input, obj.number("output")?.toLong() ?: 0L)
        }

        fun from(frame: GatewayFrame.Event): GatewayEvent? {
            val p = frame.payload
            return when (frame.type) {
                "message.start" -> MessageStart
                "message.delta" -> MessageDelta(p.string("text") ?: "")
                "message.interim" -> {
                    val text = p.string("text") ?: return null
                    MessageInterim(text, (p["already_streamed"] as? JsonPrimitive)?.content == "true")
                }
                "message.complete" -> MessageComplete(
                    text = p.string("text"),
                    usage = (p["usage"] as? JsonObject)?.let(::usageOf),
                    status = p.string("status"),
                    error = p.string("error"),
                )
                "thinking.delta", "reasoning.delta" -> ReasoningDelta(p.string("text") ?: "")
                "reasoning.available" -> {
                    val text = p.string("text") ?: return null
                    ReasoningDelta(text)
                }
                "tool.generating" -> ToolGenerating(p.string("name") ?: "tool")
                "tool.start" -> ToolStart(
                    toolId = p.string("tool_id") ?: "",
                    name = p.string("name") ?: "tool",
                    preview = p.string("preview")
                        ?: p.string("args_text")
                        ?: p["args"]?.toString()?.take(200)
                        ?: p.string("context")
                        ?: "",
                )
                "tool.complete" -> ToolComplete(
                    toolId = p.string("tool_id") ?: "",
                    name = p.string("name") ?: "tool",
                    durationS = p.number("duration_s"),
                    summary = p.string("summary")
                        ?: p.string("result_text")
                        ?: p["result"]?.toString()?.take(300)
                        ?: "",
                    // No documented error flag; an `error` key marks failure.
                    // If live disagrees, report per AGENTS.md rule 1.
                    failed = p["error"] != null,
                )
                "tool.output_risk" -> ToolRisk(p.string("tool_id") ?: "")
                "status.update" -> StatusUpdate(p.string("text") ?: "")
                "session.usage" -> {
                    val usage = (p["usage"] as? JsonObject)?.let(::usageOf) ?: return null
                    SessionUsage(usage)
                }
                else -> null
            }
        }
    }
}
