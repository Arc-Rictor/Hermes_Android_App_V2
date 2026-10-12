package com.arcrictor.hermes.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PkceTest {
    @Test fun `RFC 7636 Appendix B vector`() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge(verifier))
    }

    @Test fun `verifier is 43 url-safe chars`() {
        repeat(20) {
            val v = Pkce.verifier()
            assertEquals(43, v.length)
            assertTrue(v.matches(Regex("[A-Za-z0-9_-]+")))
        }
    }

    @Test fun `challenge is deterministic`() {
        val v = Pkce.verifier()
        assertEquals(Pkce.challenge(v), Pkce.challenge(v))
    }

    @Test fun `parseNext accepts ? and & separators`() {
        assertEquals("CODE1", Pkce.parseNext("http://127.0.0.1/cb?code=CODE1&state=S1", "S1"))
        assertEquals("CODE2", Pkce.parseNext("http://127.0.0.1/cb?x=1&code=CODE2&state=S2", "S2"))
    }

    @Test fun `parseNext rejects state mismatch`() {
        try {
            Pkce.parseNext("http://127.0.0.1/cb?code=C&state=OTHER", "MINE")
            fail("expected StateMismatch")
        } catch (e: LoginError.StateMismatch) {
            // expected
        }
    }

    @Test fun `parseNext rejects missing code`() {
        try {
            Pkce.parseNext("http://127.0.0.1/cb?state=S", "S")
            fail("expected MissingCode")
        } catch (e: LoginError.MissingCode) {
            // expected
        }
    }
}
