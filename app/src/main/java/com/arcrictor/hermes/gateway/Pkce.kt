package com.arcrictor.hermes.gateway

import java.security.SecureRandom
import java.security.MessageDigest
import java.util.Base64

/** PKCE (RFC 7636, S256) helpers for the native login flow. See PROTOCOL.md §2. */
object Pkce {
    private val urlB64 = Base64.getUrlEncoder().withoutPadding()

    fun verifier(random: SecureRandom = SecureRandom()): String =
        urlB64.encodeToString(ByteArray(32).also(random::nextBytes))

    fun state(random: SecureRandom = SecureRandom()): String =
        urlB64.encodeToString(ByteArray(24).also(random::nextBytes))

    fun challenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray(Charsets.US_ASCII))
        return urlB64.encodeToString(digest)
    }

    /**
     * Parses the `next` URL from password-login (`http://127.0.0.1/cb?code=…&state=…`
     * or with an existing query, `&`-separated) and verifies `state`.
     * @throws LoginError.StateMismatch, LoginError.MissingCode
     */
    fun parseNext(next: String, expectedState: String): String {
        val query = next.substringAfter("?", "")
        val params = query.split("&").mapNotNull {
            val i = it.indexOf("=")
            if (i < 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val state = params["state"] ?: throw LoginError.MissingCode("no state in next")
        if (state != expectedState) throw LoginError.StateMismatch()
        return params["code"] ?: throw LoginError.MissingCode("no code in next")
    }
}

/** Failures of the login/token flow. `userMessage` is safe to show. */
sealed class LoginError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable, host: String) : LoginError(
        if (host.startsWith("100.")) "Couldn't reach $host — is Tailscale on and connected?"
        else "Couldn't reach $host — check the address and your connection.",
        cause,
    )
    class Http(val code: Int, detail: String) :
        LoginError(if (code == 401) "Wrong username or password." else "Server error ($code). $detail")
    class StateMismatch : LoginError("Sign-in was interrupted. Please try again.")
    class MissingCode(detail: String) : LoginError("Sign-in failed ($detail). Please try again.")
    /** Refresh token dead: user must sign in again. */
    class SessionExpired : LoginError("Session expired. Please sign in again.")
    /** Auth provider unreachable: keep tokens, retry with backoff. Never a logout. */
    class ProviderUnreachable : LoginError("Auth server unreachable. Will retry.")
}
