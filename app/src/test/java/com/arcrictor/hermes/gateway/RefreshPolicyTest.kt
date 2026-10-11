package com.arcrictor.hermes.gateway

import com.arcrictor.hermes.data.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshPolicyTest {
    @Test fun `no refresh when fresh`() {
        assertFalse(RefreshPolicy.needsRefresh(nowSec = 1000, expiresAtSec = 2000))
    }

    @Test fun `refresh at 60s boundary`() {
        assertTrue(RefreshPolicy.needsRefresh(nowSec = 1940, expiresAtSec = 2000))
        assertFalse(RefreshPolicy.needsRefresh(nowSec = 1939, expiresAtSec = 2000))
    }

    @Test fun `refresh when expired`() {
        assertTrue(RefreshPolicy.needsRefresh(nowSec = 5000, expiresAtSec = 2000))
    }

    private class FakeStore(var set: TokenStore.TokenSet?) : TokenStorage {
        override val tokens = MutableStateFlow(set)
        var refreshes = 0
        override suspend fun save(set: TokenStore.TokenSet) {
            this.set = set
            tokens.value = set
        }
        override suspend fun clear() {
            set = null
            tokens.value = null
        }
        override suspend fun rememberEndpoint(baseUrl: String, user: String) = Unit
    }

    private fun set(exp: Long) = TokenStore.TokenSet("a", "r", "basic", "u", exp, "http://x")

    @Test fun `ensureValid returns current when fresh`() = runTest {
        val store = FakeStore(set(10_000))
        val mgr = TokenManager(store, clock = { 1000 })
        assertEquals(set(10_000).accessToken, mgr.ensureValid().accessToken)
    }

    @Test fun `ensureValid signs out when nothing stored`() = runTest {
        val mgr = TokenManager(FakeStore(null), clock = { 1000 })
        try {
            mgr.ensureValid()
            org.junit.Assert.fail("expected SessionExpired")
        } catch (e: LoginError.SessionExpired) {
            // expected
        }
    }
}
