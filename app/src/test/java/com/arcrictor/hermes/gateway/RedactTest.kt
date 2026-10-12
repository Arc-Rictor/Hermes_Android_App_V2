package com.arcrictor.hermes.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {
    @Test fun `ticket in URL is masked`() {
        val out = Redact.text("WS connecting ws://h/api/ws?ticket=AbC123xYzSecretTicketValue99")
        assertTrue(out.contains("ticket=AbC123…"))
        assertFalse(out.contains("SecretTicketValue99"))
    }

    @Test fun `json token fields masked, structure kept`() {
        val out = Redact.text("""{"access_token":"ACCESSTOKENVALUE123456","expires_at":123}""")
        assertTrue(out.contains(""""access_token":"ACCESS…""""))
        assertTrue(out.contains(""""expires_at":123"""))
    }

    @Test fun `password and cookie masked`() {
        val out = Redact.text(
            """{"password":"supersecretpassword"} Cookie: hermes_session_pkce=eyJicm9rZXIiOiJ symmetrical""",
        )
        assertFalse(out.contains("supersecretpassword"))
        assertTrue(out.contains("hermes_session_pkce=eyJicm…"))
    }

    @Test fun `bearer masked, short codes readable`() {
        val out = Redact.text("Authorization: Bearer ACCESSTOKENVALUE123456")
        assertTrue(out.contains("Bearer ACCESS…"))
        val code = Redact.text("""{"code":"ABC123"}""")
        assertTrue(code.contains("ABC123"))
    }

    @Test fun `ordinary text untouched`() {
        val s = "message.delta tool.start session_id=2425776e"
        assertTrue(Redact.text(s) == s)
    }
}
