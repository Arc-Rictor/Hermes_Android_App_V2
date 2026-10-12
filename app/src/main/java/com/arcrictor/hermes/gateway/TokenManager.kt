package com.arcrictor.hermes.gateway

import com.arcrictor.hermes.data.TokenStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Pure refresh policy. See PROTOCOL.md §2. */
object RefreshPolicy {
    /** Refresh proactively 60 s before expiry (desktop parity). */
    fun needsRefresh(expiresAtSec: Long, nowSec: Long): Boolean = nowSec >= expiresAtSec - 60
}

/**
 * Owns login, token storage and refresh. Single in-flight refresh (Mutex):
 * concurrent 401s reuse it instead of stampeding the server.
 */
/** Storage seam so the flow is unit-testable without Android. */
interface TokenStorage {
    val tokens: kotlinx.coroutines.flow.Flow<TokenStore.TokenSet?>
    suspend fun save(set: TokenStore.TokenSet)
    suspend fun clear()
    suspend fun rememberEndpoint(baseUrl: String, user: String)
}

class TokenManager(
    private val store: TokenStorage,
    private val apiFor: (baseUrl: String) -> AuthApi = ::AuthApi,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private val refreshMutex = Mutex()

    val tokens get() = store.tokens

    suspend fun login(
        baseUrl: String,
        username: String,
        password: String,
        onStep: (String) -> Unit = {},
    ): TokenStore.TokenSet {
        val api = apiFor(baseUrl)
        onStep("Starting secure sign-in…")
        val verifier = Pkce.verifier()
        val state = Pkce.state()
        val cookie = api.authorize(Pkce.challenge(verifier), state)
        onStep("Verifying…")
        val next = api.passwordLogin(cookie, username, password)
        val code = Pkce.parseNext(next, state)
        onStep("Finishing…")
        val t = api.exchangeToken(code, verifier)
        val set = TokenStore.TokenSet(
            accessToken = t.accessToken,
            refreshToken = t.refreshToken,
            provider = t.provider,
            userId = t.userId,
            expiresAt = t.expiresAt,
            baseUrl = api.base,
        )
        store.save(set)
        return set
    }

    /** Returns valid tokens, refreshing first when within 60 s of expiry. */
    suspend fun ensureValid(): TokenStore.TokenSet {
        val current = store.tokens.first() ?: throw LoginError.SessionExpired()
        if (!RefreshPolicy.needsRefresh(current.expiresAt, clock())) return current
        return refreshMutex.withLock {
            val latest = store.tokens.first() ?: throw LoginError.SessionExpired()
            if (!RefreshPolicy.needsRefresh(latest.expiresAt, clock())) return latest
            val t = apiFor(latest.baseUrl).refreshToken(latest.refreshToken)
            val set = TokenStore.TokenSet(
                accessToken = t.accessToken,
                refreshToken = t.refreshToken,
                provider = t.provider,
                userId = t.userId,
                expiresAt = t.expiresAt,
                baseUrl = latest.baseUrl,
            )
            store.save(set)
            return set
        }
    }

    suspend fun logout() = store.clear()
    suspend fun rememberEndpoint(baseUrl: String, user: String) = store.rememberEndpoint(baseUrl, user)
}
