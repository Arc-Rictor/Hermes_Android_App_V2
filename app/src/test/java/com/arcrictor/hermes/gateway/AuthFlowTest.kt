package com.arcrictor.hermes.gateway

import com.arcrictor.hermes.data.TokenStore
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.Executors

/**
 * Full PKCE login against a fake dashboard (JDK built-in HTTP server, no extra deps).
 * Mirrors the live-verified exchange in PROTOCOL.md §2.
 */
class AuthFlowTest {
    private lateinit var server: HttpServer
    private lateinit var base: String
    @Volatile private var lastState = "CLIENTSTATE"

    private class FakeStore : TokenStorage {
        var set: TokenStore.TokenSet? = null
        override val tokens = MutableStateFlow<TokenStore.TokenSet?>(null)
        override suspend fun save(set: TokenStore.TokenSet) {
            this.set = set
            tokens.value = set
        }
        override suspend fun clear() {
            set = null
            tokens.value = null
        }
    }

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/auth/native/authorize") { ex ->
            val q = ex.requestURI.query.split("&").associate {
                val i = it.indexOf("=")
                it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }
            check(q["code_challenge_method"] == "S256")
            check(q["redirect_uri"] == "http://127.0.0.1/cb")
            lastState = q["state"] ?: "CLIENTSTATE"
            ex.responseHeaders.add("Set-Cookie", "hermes_session_pkce=BROKER1; Path=/; HttpOnly")
            ex.responseHeaders.add("Location", "/login")
            ex.sendResponseHeaders(302, -1)
            ex.close()
        }
        server.createContext("/auth/password-login") { ex ->
            val cookie = ex.requestHeaders.getFirst("Cookie") ?: ""
            val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val ok = "hermes_session_pkce=BROKER1" in cookie &&
                "\"username\":\"kirsty\"" in body && "\"password\":\"s3cret\"" in body
            val json = if (ok) {
                """{"ok":true,"next":"http://127.0.0.1/cb?code=GWCODE9&state=$lastState"}"""
            } else {
                """{"detail":"Invalid credentials"}"""
            }
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(if (ok) 200 else 401, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/auth/native/token") { ex ->
            val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            check("\"code\":\"GWCODE9\"" in body)
            val json = """{"access_token":"AT-1","refresh_token":"RT-1","token_type":"Bearer",""" +
                """"expires_at":9999999999,"provider":"basic","user_id":"kirsty"}"""
            val bytes = json.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.createContext("/auth/native/refresh") { ex ->
            val body = ex.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val bytes = if ("\"refresh_token\":\"DEAD\"" in body) {
                """{"error":"session_expired","detail":"gone"}"""
            } else {
                """{"access_token":"AT-2","refresh_token":"RT-2","token_type":"Bearer","expires_at":9999999999,"provider":"basic","user_id":"kirsty"}"""
            }.toByteArray()
            ex.sendResponseHeaders(if ("\"refresh_token\":\"DEAD\"" in body) 401 else 200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        base = "http://127.0.0.1:${server.address.port}"
    }

    @After fun stop() {
        server.stop(0)
    }

    @Test fun `dead refresh token clears the store`() = runTest {
        val store = FakeStore()
        store.save(TokenStore.TokenSet("AT", "DEAD", "basic", "kirsty", expiresAt = 0, baseUrl = base))
        val mgr = TokenManager(store, clock = { 1000 }) { AuthApi(it) }
        try {
            mgr.ensureValid()
            org.junit.Assert.fail("expected SessionExpired")
        } catch (e: LoginError.SessionExpired) {
            assertEquals(null, store.set)
        }
    }

    @Test fun `expiring token refreshes and persists the new pair`() = runTest {
        val store = FakeStore()
        store.save(TokenStore.TokenSet("AT-1", "RT-1", "basic", "kirsty", expiresAt = 1030, baseUrl = base))
        val mgr = TokenManager(store, clock = { 1000 }) { AuthApi(it) }
        val set = mgr.ensureValid()
        assertEquals("AT-2", set.accessToken)
        assertEquals("RT-2", store.set!!.refreshToken)
        assertEquals(base, store.set!!.baseUrl)
    }

    @Test fun `TokenManager login end to end`() = runTest {
        val store = FakeStore()
        val mgr = TokenManager(store) { AuthApi(it) }
        val steps = mutableListOf<String>()
        val set = mgr.login(base, "kirsty", "s3cret") { steps.add(it) }
        assertEquals("kirsty", set.userId)
        assertEquals("AT-1", set.accessToken)
        assertEquals(base, set.baseUrl)
        assertEquals(3, steps.size)
    }

    @Test fun `full login stores tokens`() = runTest {
        val store = FakeStore()
        // NOTE: TokenManager.login generates its own state, which the fake server cannot
        // echo (it always returns CLIENTSTATE), so this test drives AuthApi directly with
        // a matching state. TokenManager wiring is covered by RefreshPolicyTest.
        val api = AuthApi(base)
        val verifier = Pkce.verifier()
        val state = "CLIENTSTATE"
        val cookie = api.authorize(Pkce.challenge(verifier), state)
        assertEquals("BROKER1", cookie)
        val next = api.passwordLogin(cookie, "kirsty", "s3cret")
        assertEquals("GWCODE9", Pkce.parseNext(next, state))
        val t = api.exchangeToken("GWCODE9", verifier)
        assertEquals("kirsty", t.userId)
        assertEquals("AT-1", t.accessToken)
        store.save(
            TokenStore.TokenSet(t.accessToken, t.refreshToken, t.provider, t.userId, t.expiresAt, base),
        )
        assertEquals("RT-1", store.set!!.refreshToken)
        assertEquals(base, store.set!!.baseUrl)
    }

    @Test fun `refresh maps 401 to SessionExpired`() = runTest {
        val api = AuthApi(base)
        try {
            api.refreshToken("DEAD")
            org.junit.Assert.fail("expected SessionExpired")
        } catch (e: LoginError.SessionExpired) {
            // expected
        }
    }

    @Test fun `wrong password maps to Http 401`() = runTest {
        val api = AuthApi(base)
        try {
            api.passwordLogin("BROKER1", "kirsty", "wrong")
            org.junit.Assert.fail("expected failure")
        } catch (e: LoginError.Http) {
            assertEquals(401, e.code)
            assertEquals("Wrong username or password.", e.message)
        }
    }
}
