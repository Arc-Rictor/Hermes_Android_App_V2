package com.arcrictor.hermes.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTest {
    @Test fun `runtime id death falls back to stored-id resume`() {
        assertEquals(Recovery.Plan.Resume("runtime is gone"), Recovery.decide(activateErrorCode = 4001))
        assertEquals(Recovery.Plan.Resume("runtime is gone"), Recovery.decide(activateErrorCode = 4007))
    }

    @Test fun `other activate errors retry`() {
        assertEquals(Recovery.Plan.Retry, Recovery.decide(activateErrorCode = 4009))
    }

    @Test fun `truncated replay falls back to resume`() {
        assertEquals(Recovery.Plan.Resume("event replay truncated"), Recovery.decide(truncated = true))
    }

    @Test fun `epoch change replays from zero`() {
        assertEquals(Recovery.Plan.ReplayFromZero, Recovery.decide(epochChanged = true))
    }

    @Test fun `normal replay preserves watermark`() {
        assertEquals(Recovery.Plan.Replay, Recovery.decide())
    }

    @Test fun `open requests from the resume snapshot are restored`() {
        val store = ServerRequests()
        val request = OpenRequestEntry(
            id = "srq-abc123",
            method = "approval",
            params = GatewayJson.parseToJsonElement(
                """{"request_id":"req-1","command":"test command","choices":["once","deny"]}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        store.rehydrate(listOf(request))
        assertEquals(1, store.activeCount())
        assertTrue(store.cards.value.single() is ApprovalCard)
    }
}
