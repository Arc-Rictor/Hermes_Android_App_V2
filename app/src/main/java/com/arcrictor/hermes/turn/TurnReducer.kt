package com.arcrictor.hermes.turn

import com.arcrictor.hermes.gateway.InflightTurn
import com.arcrictor.hermes.turn.GatewayEvent.Interrupted
import com.arcrictor.hermes.turn.GatewayEvent.MessageComplete
import com.arcrictor.hermes.turn.GatewayEvent.MessageDelta
import com.arcrictor.hermes.turn.GatewayEvent.MessageInterim
import com.arcrictor.hermes.turn.GatewayEvent.MessageStart
import com.arcrictor.hermes.turn.GatewayEvent.ReasoningDelta
import com.arcrictor.hermes.turn.GatewayEvent.SessionUsage
import com.arcrictor.hermes.turn.GatewayEvent.StatusUpdate
import com.arcrictor.hermes.turn.GatewayEvent.ToolComplete
import com.arcrictor.hermes.turn.GatewayEvent.ToolGenerating
import com.arcrictor.hermes.turn.GatewayEvent.ToolRisk
import com.arcrictor.hermes.turn.GatewayEvent.ToolStart

/**
 * Single source of truth for turn state (AGENTS.md rule 6):
 * `TurnState + GatewayEvent -> TurnState`. Pure — JVM-tested with recorded frames.
 */
object TurnReducer {
    fun reduce(state: TurnState, event: GatewayEvent, nowMs: Long): TurnState = when (event) {
        MessageStart -> TurnState(
            activity = TurnActivity.Working(""),
            openRequestCount = state.openRequestCount,
            turnStartMs = nowMs,
        )
        is StatusUpdate -> state.copy(activity = TurnActivity.Working(event.text))
        is ReasoningDelta -> state.copy(
            thinking = state.thinking + event.text,
            // An open tool holds "Using tool" even when reasoning interleaves (R3.2).
            activity = state.openTools.firstOrNull()?.let { TurnActivity.UsingTool(it.name) }
                ?: TurnActivity.Thinking,
        )
        is ToolGenerating -> state.copy(activity = TurnActivity.UsingTool(event.name))
        is ToolStart -> state.copy(
            tools = state.tools + ToolEntry(event.toolId, event.name, event.preview, nowMs),
            activity = TurnActivity.UsingTool(event.name),
        )
        is ToolComplete -> {
            val idx = state.tools.indexOfLast { it.open && it.toolId == event.toolId }
                .takeIf { it >= 0 }
                ?: state.tools.indexOfLast { it.open && it.name == event.name }
                    .takeIf { it >= 0 }
                ?: state.tools.indexOfLast { it.open }
            if (idx == null || idx < 0) {
                state // complete for an unknown entry: ignore, never crash.
            } else {
                val closed = state.tools[idx].copy(
                    endMs = nowMs,
                    durationS = event.durationS,
                    summary = event.summary,
                    failed = event.failed,
                )
                val tools = state.tools.toMutableList().also { it[idx] = closed }
                state.copy(
                    tools = tools,
                    activity = tools.firstOrNull { t -> t.open }?.let { TurnActivity.UsingTool(it.name) }
                        ?: if (state.reply.isNotEmpty()) TurnActivity.Responding
                        else TurnActivity.Working(""),
                )
            }
        }
        is ToolRisk -> {
            val idx = state.tools.indexOfLast { it.open && it.toolId == event.toolId }
                .takeIf { it >= 0 } ?: state.tools.indexOfLast { it.open }
            if (idx == null || idx < 0) state
            else state.copy(tools = state.tools.toMutableList().also { it[idx] = it[idx].copy(risky = true) })
        }
        is MessageDelta -> state.copy(
            reply = state.reply + event.text,
            activity = TurnActivity.Responding,
        )
        is MessageInterim ->
            // Already-streamed text is on screen; anything else is an interim note (R3.5).
            if (event.alreadyStreamed) state
            else state.copy(notes = state.notes + event.text)
        is MessageComplete -> {
            val reply = state.reply.ifBlank { event.text ?: "" }
            state.copy(
                reply = reply,
                usage = event.usage ?: state.usage,
                activity = when {
                    event.error != null -> TurnActivity.Error(event.error)
                    event.status == null ||
                        event.status == "ok" ||
                        event.status == "complete" -> TurnActivity.Idle
                    event.status.contains("error", ignoreCase = true) ||
                        event.status.contains("fail", ignoreCase = true) ->
                        TurnActivity.Error(event.status)
                    // interrupt/stop/cancel/anything else unknown-but-terminal: visible, not idle.
                    // If live disagrees, report per AGENTS.md rule 1.
                    else -> TurnActivity.Stopped(event.status)
                },
            )
        }
        is SessionUsage -> state.copy(usage = event.usage)
        Interrupted -> state.copy(activity = TurnActivity.Stopped("Stopped"))
    }

    /** Live state for a resume snapshot's `inflight` block (cold snapshots → Idle). */
    fun forSnapshot(inflight: InflightTurn?, openRequestCount: Int, nowMs: Long): TurnState {
        if (inflight == null) return TurnState(openRequestCount = openRequestCount)
        if (inflight.error != null) {
            return TurnState(activity = TurnActivity.Error(inflight.error), openRequestCount = openRequestCount)
        }
        if (inflight.assistant.isBlank()) {
            return TurnState(
                activity = TurnActivity.Working(""),
                openRequestCount = openRequestCount,
                turnStartMs = nowMs,
            )
        }
        return TurnState(
            activity = if (inflight.streaming) TurnActivity.Responding else TurnActivity.Working(""),
            reply = inflight.assistant,
            openRequestCount = openRequestCount,
            turnStartMs = nowMs,
        )
    }
}
