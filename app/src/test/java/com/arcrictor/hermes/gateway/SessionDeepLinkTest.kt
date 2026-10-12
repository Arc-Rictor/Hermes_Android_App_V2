package com.arcrictor.hermes.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionDeepLinkTest {
    @Test fun `parses stored session id without interpreting it`() {
        assertEquals(
            "20261012_152209_272b67",
            SessionDeepLink.storedId("hermes://session/20261012_152209_272b67"),
        )
    }

    @Test fun `rejects unrelated or malformed links`() {
        assertNull(SessionDeepLink.storedId(null))
        assertNull(SessionDeepLink.storedId("https://example.com/session/abc"))
        assertNull(SessionDeepLink.storedId("hermes://other/abc"))
        assertNull(SessionDeepLink.storedId("hermes://session/"))
        assertNull(SessionDeepLink.storedId("hermes://session/one/two"))
        assertNull(SessionDeepLink.storedId("hermes://session/id?unexpected=1"))
    }
}
