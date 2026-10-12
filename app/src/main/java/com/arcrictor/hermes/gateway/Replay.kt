package com.arcrictor.hermes.gateway

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure helpers for the PROTOCOL.md §5 reconnect procedure.
 * The orchestration (activate → events.since → held frames → resume fallback)
 * lives in [TurnSession]; everything orderable and testable lives here.
 */
object Replay {
    /**
     * Step 4: replayed events plus the live frames held during activate,
     * deduplicated by the seq watermark and applied in seq order.
     * An epoch change means the server restarted: watermarks are cleared,
     * so everything passes. Frames without a seq (undedupeable) always pass.
     */
    fun select(
        events: List<GatewayFrame.Event>,
        watermark: Long,
        epoch: String,
        adoptedEpoch: String,
    ): List<GatewayFrame.Event> =
        events
            .filter { e -> epoch != adoptedEpoch || (e.seq ?: Long.MAX_VALUE) > watermark }
            .sortedWith(compareBy { it.seq ?: Long.MIN_VALUE })

    /**
     * `session.events.since` event items arrive either as full `{"method":"event",
     * "params":{…}}` frames or as bare params objects — the contract shows the
     * result keys but not the item envelope. Accept both; null when unparseable.
     * If live disagrees, report per AGENTS.md rule 1.
     */
    fun itemToEvent(item: JsonObject): GatewayFrame.Event? {
        val params = (item["params"] as? JsonObject) ?: item
        val type = (params["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return null
        // A full frame says method:"event"; a bare params object says nothing.
        // Anything naming another method is not an event.
        val method = (item["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (method != null && method != "event") return null
        return GatewayFrame.Event(
            type = type,
            sessionId = (params["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "",
            payload = (params["payload"] as? JsonObject) ?: JsonObject(emptyMap()),
            seq = (params["seq"] as? JsonPrimitive)?.content?.toLongOrNull(),
        )
    }
}
