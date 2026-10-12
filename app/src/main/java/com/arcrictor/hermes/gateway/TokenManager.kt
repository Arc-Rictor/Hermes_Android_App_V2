package com.arcrictor.hermes.gateway

import com.arcrictor.hermes.data.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Pure refresh policy. See PROTOCOL.md §2. */
object RefreshPolicy {
    /** Refresh proactively 60 s before expiry (desktop parity). */
    fun needsRefresh(expiresAtSec: Long, nowSec: Long): Boolean = nowSec >= expiresAtSec - 60
}

/** Storage seam so the flow is unit-testable without Android. */
interface TokenStorage {
    val tokens: Flow<TokenStore.TokenSet?>
    suspend fun save(set: TokenStore.TokenSet)
    suspend fun clear()
}

private fun TokenResponse.toSet(baseUrl: String) =
    TokenStore.TokenSet(accessToken, refreshToken, provider, userId, expiresAt, baseUrl)

/**
 * Owns login, token storage and refresh. Single in-flight refresh (Mutex):
 * concurrent callers reuse it instead of stampeding the server.
 */
class TokenManager(
    private val store: TokenStorage,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val apiFor: (baseUrl: String) -> AuthApi = ::AuthApi,
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
        val code = Pkce.parseNext(api.passwordLogin(cookie, username, password), state)
        onStep("Finishing…")
        return api.exchangeToken(code, verifier).toSet(api.base).also { store.save(it) }
    }

    /**
     * Returns valid tokens, refreshing first when within 60 s of expiry.
     * A dead refresh token clears the store (PROTOCOL.md §2) and throws SessionExpired;
     * ProviderUnreachable keeps the tokens for a later retry.
     */
    suspend fun ensureValid(): TokenStore.TokenSet {
        val current = store.tokens.first() ?: throw LoginError.SessionExpired()
        if (!RefreshPolicy.needsRefresh(current.expiresAt, clock())) return current
        return refreshMutex.withLock {
            val latest = store.tokens.first() ?: throw LoginError.SessionExpired()
            if (!RefreshPolicy.needsRefresh(latest.expiresAt, clock())) return latest
            try {
                apiFor(latest.baseUrl).refreshToken(latest.refreshToken).toSet(latest.baseUrl)
                    .also { store.save(it) }
            } catch (e: LoginError.SessionExpired) {
                store.clear()
                throw e
            }
        }
    }

    suspend fun logout() = store.clear()
}
