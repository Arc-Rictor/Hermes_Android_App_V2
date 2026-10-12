package com.arcrictor.hermes.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeParseTest {
    private val snapshot = """
    {
      "session_id": "2425776e",
      "stored_session_id": "20261011_233258_cf1f51",
      "message_count": 5,
      "messages": [
        {"role": "user", "text": "What is in this image?\n@image:/home/jarvis/.hermes/images/a.png", "row_id": 41},
        {"role": "assistant", "text": "A cat.", "row_id": 42},
        {"role": "tool", "name": "terminal", "context": "ls → …", "tool_call_id": "c1"},
        {"role": "user", "text": "[System: compacted context]", "row_id": 43},
        {"role": "assistant", "text": "", "reasoning": "pondering", "row_id": 44}
      ],
      "info": {"title": "Cat question", "desktop_contract": 8, "model": "gpt-5.6-terra"},
      "inflight": {"user": "and?", "assistant": "Also", "streaming": true},
      "open_requests": [
        {"id": "srq-abc", "method": "approval", "params": {"session_id": "2425776e"}}
      ]
    }
    """.trimIndent()

    @Test fun `snapshot parses with ids, inflight and open requests`() {
        val snap = GatewayJson.decodeFromString(LiveSessionSnapshot.serializer(), snapshot)
        assertEquals("2425776e", snap.session_id)
        assertEquals("20261011_233258_cf1f51", snap.storedId())
        assertEquals(5, snap.message_count)
        assertEquals("Also", snap.inflight!!.assistant)
        assertTrue(snap.inflight!!.streaming)
        assertEquals(1, snap.open_requests.size)
        assertEquals("srq-abc", snap.open_requests[0].id)
    }

    @Test fun `projection of snapshot transcript`() {
        val snap = GatewayJson.decodeFromString(LiveSessionSnapshot.serializer(), snapshot)
        val ui = Transcript.project(snap.messages)
        assertEquals(4, ui.size) // [System: row dropped
        val user = ui[0] as UiMessage.User
        assertEquals("What is in this image?", user.text)
        assertEquals(listOf(ImageRef("/home/jarvis/.hermes/images/a.png")), user.images)
        assertEquals("A cat.", (ui[1] as UiMessage.Assistant).text)
        assertEquals("terminal", (ui[2] as UiMessage.Tool).name)
        assertEquals("pondering", (ui[3] as UiMessage.Assistant).reasoning)
    }

    @Test fun `absent optionals default sanely`() {
        val snap = GatewayJson.decodeFromString(
            LiveSessionSnapshot.serializer(),
            """{"session_id":"ab12cd34","message_count":0,"messages":[]}""",
        )
        assertEquals("ab12cd34", snap.storedId())
        assertNull(snap.inflight)
        assertTrue(snap.open_requests.isEmpty())
    }
}
