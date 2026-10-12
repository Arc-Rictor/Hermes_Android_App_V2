package com.arcrictor.hermes.gateway

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayTest {
    private fun event(seq: Long?, type: String = "message.delta"): GatewayFrame.Event =
        GatewayFrame.Event(type, "rid", JsonObject(emptyMap()), seq)

    @Test fun `seq at or below watermark is dropped`() {
        val out = Replay.select(
            listOf(event(7), event(8), event(9)),
            watermark = 8, epoch = "e1", adoptedEpoch = "e1",
        )
        assertEquals(listOf(9L), out.map { it.seq })
    }

    @Test fun `epoch change clears the watermark`() {
        val out = Replay.select(
            listOf(event(3), event(1), event(2)),
            watermark = 99, epoch = "e2", adoptedEpoch = "e1",
        )
        assertEquals(listOf(1L, 2L, 3L), out.map { it.seq })
    }

    @Test fun `held frames merge in seq order, seq-less kept`() {
        val out = Replay.select(
            listOf(event(20), event(null), event(11)),
            watermark = 10, epoch = "e1", adoptedEpoch = "e1",
        )
        assertEquals(3, out.size)
        assertEquals(listOf(11L, 20L), out.mapNotNull { it.seq })
    }

    @Test fun `full-frame and bare-params items both parse`() {
        val frame = GatewayJson.parseToJsonElement(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"r","seq":4,"payload":{"text":"hi"}}}""",
        ) as JsonObject
        val bare = GatewayJson.parseToJsonElement(
            """{"type":"message.delta","session_id":"r","seq":4,"payload":{"text":"hi"}}""",
        ) as JsonObject
        val a = Replay.itemToEvent(frame)!!
        val b = Replay.itemToEvent(bare)!!
        assertEquals("message.delta", a.type)
        assertEquals(4L, a.seq)
        assertEquals(a, b)
    }

    @Test fun `non-event items are rejected`() {
        val bad = GatewayJson.parseToJsonElement("""{"method":"approval","params":{}}""") as JsonObject
        assertNull(Replay.itemToEvent(bad))
        val typeless = GatewayJson.parseToJsonElement("""{"payload":{}}""") as JsonObject
        assertNull(Replay.itemToEvent(typeless))
        assertTrue(true)
    }
}
