package com.arcrictor.hermes.gateway

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerRequestsTest {
    private fun params(json: String): JsonObject =
        GatewayJson.parseToJsonElement(json).jsonObject

    @Test fun `offered choices only, session and always omitted unless listed`() {
        val card = ServerRequests.parseApproval(
            "srq-1", "r1",
            params("""{"request_id":"r1","command":"rm -rf /tmp/x","choices":["once","deny"]}"""),
        )
        assertEquals(listOf("once", "deny"), card.choices)
        assertEquals("rm -rf /tmp/x", card.command)
    }

    @Test fun `missing choices fall back to once plus deny`() {
        val card = ServerRequests.parseApproval("srq-1", "r1", params("""{"request_id":"r1"}"""))
        assertEquals(listOf("once", "deny"), card.choices)
    }

    @Test fun `clarify answer json carries every qid, blanks as null`() {
        val result = ServerRequests.clarifyResult(
            mapOf("q1" to "blue", "q2" to null, "q3" to ""),
        )
        assertEquals(
            """{"answers":{"q1":"blue","q2":null,"q3":""}}""",
            result.toString(),
        )
    }

    @Test fun `cancel by id withdraws only the matching card`() {
        val store = ServerRequests()
        store.addLive("srq-a", "approval", params("""{"request_id":"ra","command":"one"}"""))
        store.addLive("srq-b", "approval", params("""{"request_id":"rb","command":"two"}"""))
        store.expireById("srq-a", "timeout")
        val cards = store.cards.value.filterIsInstance<ApprovalCard>()
        assertTrue(cards.first { it.id == "srq-a" }.expired)
        assertEquals("timeout", cards.first { it.id == "srq-a" }.expiredReason)
        assertTrue(!cards.first { it.id == "srq-b" }.expired)
        assertEquals(1, store.activeCount())
    }

    @Test fun `approval cancelled withdraws by request id`() {
        val store = ServerRequests()
        store.addLive("srq-a", "approval", params("""{"request_id":"ra"}"""))
        store.addLive("srq-b", "clarify", params("""{"questions":[{"qid":"q","question":"?"}]}"""))
        store.expireByRequestIds(listOf("ra"), "superseded")
        assertTrue(store.cards.value.first { it.id == "srq-a" }.expired)
        assertTrue(!store.cards.value.first { it.id == "srq-b" }.expired)
    }

    @Test fun `rehydrate dedupes by id, answering twice stays harmless`() {
        val store = ServerRequests()
        val p = params("""{"request_id":"ra","command":"one"}""")
        val first = store.addLive("srq-a", "approval", p)!!
        val second = store.addLive("srq-a", "approval", p)!!
        assertEquals(first, second)
        assertEquals(1, store.cards.value.size)
        store.markAnswered("srq-a")
        assertTrue(store.cards.value.isEmpty())
    }

    @Test fun `unknown method parses to null, secret prompts parse`() {
        assertNull(ServerRequests.parseCard("srq-x", "terminal.read", params("""{}""")))
        val sudo = ServerRequests.parseCard("srq-s", "sudo", params("""{"command":"reboot"}""")) as SecretCard
        assertEquals(SecretKind.SUDO, sudo.kind)
        assertEquals("reboot", sudo.prompt)
        val secret = ServerRequests.parseCard(
            "srq-e", "secret", params("""{"env_var":"GH_TOKEN","prompt":"Paste a token"}"""),
        ) as SecretCard
        assertEquals("GH_TOKEN", secret.title)
    }
}
