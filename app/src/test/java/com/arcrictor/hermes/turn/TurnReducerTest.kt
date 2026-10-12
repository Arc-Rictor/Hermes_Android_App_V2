package com.arcrictor.hermes.turn

import com.arcrictor.hermes.gateway.GatewayFrame
import com.arcrictor.hermes.gateway.JsonRpc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnReducerTest {
    private var now = 1_000_000L
    private fun tick(ms: Long = 1_000): Long = (now + ms).also { now = it }

    /** A recorded turn: start → thinking → tool (reasoning interleaved) → deltas → complete. */
    private val recordedTurn = listOf(
        """{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"2425776e","seq":11}}""",
        """{"jsonrpc":"2.0","method":"event","params":{"type":"status.update","session_id":"2425776e","seq":12,"payload":{"kind":"status","text":"Planning"}}}}""",
        """{"jsonrpc":"2.0","method":"event","params":{"type":"reasoning.delta","session_id":"2425776e","seq":13,"payload":{"text":"Let me check the logs"}}}}""",
        """{"jsonrpc":"2.0","method":"event","params":{"type":"tool.generating","session_id":"2425776e","seq":14,"payload":{"name":"terminal"}}}}""",
        """{"jsonrpc":"2.0","method":"event","params":{"type":"tool.start","session_id":"2425776e","seq":15,"payload":{"tool_id":"c1","name":"terminal","args_text":"ls /var/log"}}}}""",
        """{"jsonrpc":"2.0","method":"event","params":{"type":"reasoning.delta","session_id":"2425776e","seq":16,"payload":{"text":" while that runs"}}}}""",
        """{"jsonrpc":"2.0","method":"event","params":{"type":"tool.complete","session_id":"2425776e","seq":17,"payload":{"tool_id":"c1","name":"terminal","duration_s":2.5,"summary":"3 files"}}}}""",
        """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"2425776e","seq":18,"payload":{"text":"Found "}}}}""",
        """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"2425776e","seq":19,"payload":{"text":"3 files."}}}}""",
        """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"2425776e","seq":20,"payload":{"usage":{"input":12400,"output":3100}}}}""",
    )

    private fun run(frames: List<String>): TurnState {
        var state = TurnState()
        for (raw in frames) {
            val frame = JsonRpc.classify(raw) as GatewayFrame.Event
            val event = GatewayEvent.from(frame) ?: continue
            state = TurnReducer.reduce(state, event, tick())
        }
        return state
    }

    @Test fun `recorded turn walks Working Thinking UsingTool Responding Idle`() {
        val seen = mutableListOf<TurnActivity>()
        var state = TurnState()
        for (raw in recordedTurn) {
            val frame = JsonRpc.classify(raw) as GatewayFrame.Event
            GatewayEvent.from(frame)?.let { state = TurnReducer.reduce(state, it, tick()) }
            seen.add(state.activity)
        }
        assertTrue(seen[0] is TurnActivity.Working)
        assertEquals(TurnActivity.Working("Planning"), seen[1])
        assertEquals(TurnActivity.Thinking, seen[2])
        assertEquals(TurnActivity.UsingTool("terminal"), seen[3])
        assertEquals(TurnActivity.UsingTool("terminal"), seen[4])
        // Reasoning interleaved with an open tool holds UsingTool.
        assertEquals(TurnActivity.UsingTool("terminal"), seen[5])
        assertEquals(TurnActivity.Responding, seen[7])
        assertEquals(TurnActivity.Idle, seen[9])

        assertEquals("Let me check the logswhile that runs", state.thinking)
        assertEquals("Found 3 files.", state.reply)
        assertEquals(1, state.tools.size)
        assertEquals("terminal", state.tools[0].name)
        assertEquals("3 files", state.tools[0].summary)
        assertEquals("2.5s", state.tools[0].displayDuration(now))
        assertEquals("12.4k in · 3.1k out", state.usage!!.label())
    }

    @Test fun `complete with error becomes Error`() {
        val state = run(listOf(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"a","seq":1}}""",
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"a","seq":2,"payload":{"error":"boom went wrong"}}}}""",
        ))
        assertEquals(TurnActivity.Error("boom went wrong"), state.activity)
    }

    @Test fun `complete statuses map to Stopped`() {
        for (status in listOf("interrupted", "stopped", "cancelled", "frobnicate")) {
            val state = run(listOf(
                """{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"a","seq":1}}""",
                """{"jsonrpc":"2.0","method":"event","params":{"type":"message.complete","session_id":"a","seq":2,"payload":{"status":"$status"}}}}""",
            ))
            assertEquals(TurnActivity.Stopped(status), state.activity)
        }
    }

    @Test fun `interim already streamed is skipped, else noted`() {
        val state = run(listOf(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"a","seq":1}}""",
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"a","seq":2,"payload":{"text":"hi"}}}}""",
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.interim","session_id":"a","seq":3,"payload":{"text":"hi","already_streamed":true}}}}""",
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.interim","session_id":"a","seq":4,"payload":{"text":"checking something","already_streamed":false}}}}""",
        ))
        assertEquals("hi", state.reply)
        assertEquals(listOf("checking something"), state.notes)
    }

    @Test fun `complete for unknown tool is ignored`() {
        val state = run(listOf(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"a","seq":1}}""",
            """{"jsonrpc":"2.0","method":"event","params":{"type":"tool.complete","session_id":"a","seq":2,"payload":{"tool_id":"ghost","name":"terminal"}}}}""",
        ))
        assertTrue(state.tools.isEmpty())
        assertTrue(state.activity is TurnActivity.Working)
    }

    @Test fun `risk flag lands on the open entry`() {
        val state = run(listOf(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.start","session_id":"a","seq":1}}""",
            """{"jsonrpc":"2.0","method":"event","params":{"type":"tool.start","session_id":"a","seq":2,"payload":{"tool_id":"c9","name":"terminal"}}}}""",
            """{"jsonrpc":"2.0","method":"event","params":{"type":"tool.output_risk","session_id":"a","seq":3,"payload":{"tool_id":"c9","name":"terminal"}}}}""",
        ))
        assertTrue(state.tools[0].risky)
        assertEquals(TurnActivity.UsingTool("terminal"), state.activity)
    }

    @Test fun `unrendered frames map to null`() {
        for (type in listOf("gateway.ready", "sessions.changed", "todo.updated", "notice", "session.title")) {
            val frame = JsonRpc.classify(
                """{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"a","payload":{}}}""",
            ) as GatewayFrame.Event
            assertEquals(null, GatewayEvent.from(frame))
        }
    }
}
