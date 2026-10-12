package com.arcrictor.hermes.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonRpcTest {
    @Test fun `gateway ready classifies as event without session`() {
        val f = JsonRpc.classify(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"skin":{},"change_events":true,"heartbeat":true,"replay_epoch":"abc123"}}}""",
        ) as GatewayFrame.Event
        assertEquals("gateway.ready", f.type)
        assertEquals("", f.sessionId)
        assertNull(f.seq)
    }

    @Test fun `message delta carries session, text and seq`() {
        val f = JsonRpc.classify(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"message.delta","session_id":"2425776e","payload":{"text":"Hel"},"seq":42}}""",
        ) as GatewayFrame.Event
        assertEquals("2425776e", f.sessionId)
        assertEquals(42L, f.seq)
    }

    @Test fun `tool start pairs by tool id`() {
        val f = JsonRpc.classify(
            """{"jsonrpc":"2.0","method":"event","params":{"type":"tool.start","session_id":"s","payload":{"tool_id":"t1","name":"terminal","preview":"ls"},"seq":7}}""",
        ) as GatewayFrame.Event
        assertEquals("t1", (f.payload["tool_id"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun `approval arrives as server request with srq id`() {
        val f = JsonRpc.classify(
            """{"jsonrpc":"2.0","id":"srq-1a2b3c4d5e6f","method":"approval","params":{"session_id":"s","request_id":"r1","command":"rm -rf x","choices":["once","deny"]}}""",
        ) as GatewayFrame.ServerRequest
        assertEquals("srq-1a2b3c4d5e6f", f.id)
        assertEquals("approval", f.method)
    }

    @Test fun `response error carries code`() {
        val f = JsonRpc.classify(
            """{"jsonrpc":"2.0","id":"a9","error":{"code":4001,"message":"unknown session"}}""",
        ) as GatewayFrame.Response
        assertEquals("a9", f.id)
        assertEquals(4001L, f.error!!.code)
        assertNull(f.result)
    }

    @Test fun `capabilities reply parses`() {
        val f = JsonRpc.classify(
            """{"jsonrpc":"2.0","id":"a1","result":{"server_requests":["approval","clarify"],"declines_not_shown":false}}""",
        ) as GatewayFrame.Response
        assertNull(f.error)
    }

    @Test fun `garbage is unparseable, never throws`() {
        assertTrue(JsonRpc.classify("not json") is GatewayFrame.Unparseable)
        assertTrue(JsonRpc.classify("[DONE]") is GatewayFrame.Unparseable)
        assertTrue(JsonRpc.classify("""{"jsonrpc":"2.0"}""") is GatewayFrame.Unparseable)
    }

    @Test fun `builders produce exact shapes`() {
        val req = JsonRpc.request("a2", "session.create", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals("""{"jsonrpc":"2.0","id":"a2","method":"session.create","params":{}}""", req)
        assertTrue(
            JsonRpc.errorAnswer("srq-7", -32601, "no handler for server request: tour")
                .contains(""""code":-32601"""),
        )
    }
}
