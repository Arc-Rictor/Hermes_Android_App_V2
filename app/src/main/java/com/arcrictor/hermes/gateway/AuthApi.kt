package com.arcrictor.hermes.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

val GatewayJson = Json { ignoreUnknownKeys = true }

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("provider") val provider: String,
    @SerialName("user_id") val userId: String,
)

@Serializable
private data class NextResponse(val next: String)

@Serializable
private data class TicketResponse(val ticket: String)

/**
 * Raw dashboard auth REST. Pure HTTP, no storage. See PROTOCOL.md §2.
 * Passwords pass through and are never stored by this class.
 */
class AuthApi(
    baseUrl: String,
    private val client: OkHttpClient = defaultHttpClient(),
) {
    val base: String = baseUrl.trim().removeSuffix("/")

    companion object {
        const val REDIRECT_URI = "http://127.0.0.1/cb"
        const val PROVIDER = "basic"

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .build()

        fun hostOf(baseUrl: String): String =
            baseUrl.trim().removeSuffix("/").substringAfter("://").substringBefore("/").substringBefore(":")

        private val JSON = "application/json".toMediaType()
        private fun q(v: String) = URLEncoder.encode(v, "UTF-8")
    }

    private class Reply(val code: Int, val body: String, val setCookies: List<String>)

    /** One blocking call on IO; transport errors become [LoginError.Network]. */
    private suspend fun call(req: Request): Reply = try {
        withContext(Dispatchers.IO) {
            client.newCall(req).execute().use {
                Reply(it.code, it.body?.string() ?: "", it.headers("Set-Cookie"))
            }
        }
    } catch (e: IOException) {
        throw LoginError.Network(e, hostOf(base))
    }

    private fun post(path: String, body: RequestBody, vararg headers: Pair<String, String>): Request =
        Request.Builder().url(base + path).post(body)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .build()

    private fun json(vararg fields: Pair<String, String>): RequestBody =
        buildJsonObject { fields.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }
            .toString().toRequestBody(JSON)

    private fun Reply.failure(): LoginError = LoginError.Http(code, body.take(160))

    /**
     * Step 1: begins the native flow (redirects disabled — the PKCE cookie is harvested
     * from the 302 manually; following it would drop the cookie).
     * @return the `hermes_session_pkce` cookie value.
     */
    suspend fun authorize(challenge: String, state: String): String {
        val url = "$base/auth/native/authorize?provider=$PROVIDER" +
            "&code_challenge=${q(challenge)}&code_challenge_method=S256" +
            "&redirect_uri=${q(REDIRECT_URI)}&state=${q(state)}"
        val r = call(Request.Builder().url(url).get().build())
        if (r.code != 302) throw LoginError.Http(r.code, "authorize returned ${r.code}")
        return r.setCookies.firstOrNull { it.startsWith("hermes_session_pkce=") }
            ?.substringAfter("hermes_session_pkce=")?.substringBefore(";")
            ?: throw LoginError.MissingCode("no PKCE cookie on authorize 302")
    }

    /** Step 2: password login under the broker cookie. Returns the `next` URL. */
    suspend fun passwordLogin(pkceCookie: String, username: String, password: String): String {
        val r = call(
            post(
                "/auth/password-login",
                json("provider" to PROVIDER, "username" to username, "password" to password),
                "Cookie" to "hermes_session_pkce=$pkceCookie",
            ),
        )
        when (r.code) {
            200 -> return GatewayJson.decodeFromString<NextResponse>(r.body).next
            401 -> throw LoginError.Http(401, "")
            429 -> throw LoginError.Http(429, "Too many attempts. Wait a minute and retry.")
            else -> throw r.failure()
        }
    }

    /** Step 3: code + verifier → tokens. */
    suspend fun exchangeToken(code: String, verifier: String): TokenResponse {
        val r = call(post("/auth/native/token", json("code" to code, "code_verifier" to verifier)))
        if (r.code != 200) throw LoginError.MissingCode("code exchange failed (${r.code})")
        return GatewayJson.decodeFromString(r.body)
    }

    /** Refresh. 401 session_expired → [LoginError.SessionExpired]; 503 → [LoginError.ProviderUnreachable]. */
    suspend fun refreshToken(refreshToken: String): TokenResponse {
        val r = call(post("/auth/native/refresh", json("refresh_token" to refreshToken, "provider" to PROVIDER)))
        when (r.code) {
            200 -> return GatewayJson.decodeFromString(r.body)
            401 -> throw LoginError.SessionExpired()
            503 -> throw LoginError.ProviderUnreachable()
            else -> throw r.failure()
        }
    }

    /**
     * Authenticated GET with query params (REST fallbacks: messages paging, /api/media).
     * 401 → [LoginError.SessionExpired].
     */
    suspend fun get(path: String, query: Map<String, String>, accessToken: String): ByteArray {
        val url = base + path + query.entries.joinToString("&", prefix = if (query.isEmpty()) "" else "?") {
            "${q(it.key)}=${q(it.value)}"
        }
        // Bytes, not string: callers decode (images can be large).
        val r = try {
            withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder().url(url).header("Authorization", "Bearer $accessToken").get().build(),
                ).execute().use { it.code to (it.body?.bytes() ?: ByteArray(0)) }
            }
        } catch (e: IOException) {
            throw LoginError.Network(e, hostOf(base))
        }
        when (r.first) {
            200 -> return r.second
            401 -> throw LoginError.SessionExpired()
            else -> throw LoginError.Http(r.first, r.second.toString(Charsets.UTF_8).take(160))
        }
    }

    /** Mint a 30 s single-use WebSocket ticket. */
    suspend fun wsTicket(accessToken: String): String {
        val r = call(post("/api/auth/ws-ticket", ByteArray(0).toRequestBody(null), "Authorization" to "Bearer $accessToken"))
        when (r.code) {
            200 -> return GatewayJson.decodeFromString<TicketResponse>(r.body).ticket
            401 -> throw LoginError.SessionExpired()
            else -> throw r.failure()
        }
    }
}
