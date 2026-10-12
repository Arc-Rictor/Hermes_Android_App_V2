package com.arcrictor.hermes.gateway

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import kotlin.coroutines.resumeWithException

val GatewayJson = Json { ignoreUnknownKeys = true }

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("provider") val provider: String,
    @SerialName("user_id") val userId: String,
)

@Serializable
private data class NextResponse(val ok: Boolean, val next: String)

/**
 * Raw dashboard auth REST. Pure HTTP, no storage. See PROTOCOL.md §2.
 * Passwords pass through and are never stored — callers must drop them after [login].
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
    }

    private suspend fun Call.awaitBytes(): Pair<Int, ByteArray> =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel() }
            enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: okhttp3.Response) {
                    response.use {
                        cont.resume(it.code to (it.body?.bytes() ?: ByteArray(0)))
                    }
                }
            })
        }

    private fun ioError(e: IOException): LoginError.Network =
        LoginError.Network(e, hostOf(base))

    /**
     * Step 1: begins the native flow (redirects disabled — the PKCE cookie is harvested
     * from the 302 manually; following it would drop the cookie).
     * @return the `hermes_session_pkce` cookie value.
     */
    suspend fun authorize(challenge: String, state: String): String {
        fun q(v: String) = URLEncoder.encode(v, "UTF-8")
        val url = "$base/auth/native/authorize?provider=$PROVIDER" +
            "&code_challenge=${q(challenge)}&code_challenge_method=S256" +
            "&redirect_uri=${q(REDIRECT_URI)}&state=${q(state)}"
        val response = try {
            suspendCancellableCoroutine<okhttp3.Response> { cont ->
                val call = client.newCall(Request.Builder().url(url).get().build())
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cont.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: okhttp3.Response) {
                        cont.resume(response, null)
                    }
                })
            }
        } catch (e: IOException) {
            throw ioError(e)
        }
        response.use {
            if (it.code != 302) throw LoginError.Http(it.code, "authorize returned ${it.code}")
            val setCookie = it.headers("Set-Cookie")
                .firstOrNull { h -> h.startsWith("hermes_session_pkce=") }
                ?: throw LoginError.MissingCode("no PKCE cookie on authorize 302")
            return setCookie.substringAfter("hermes_session_pkce=").substringBefore(";")
        }
    }

    /** Step 2: password login under the broker cookie. Returns the `next` URL. */
    suspend fun passwordLogin(pkceCookie: String, username: String, password: String): String {
        val body = """{"provider":"$PROVIDER","username":${jsonStr(username)},"password":${jsonStr(password)}}"""
        val req = Request.Builder().url("$base/auth/password-login")
            .header("Cookie", "hermes_session_pkce=$pkceCookie")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val (code, bytes) = try {
            client.newCall(req).awaitBytes()
        } catch (e: IOException) {
            throw ioError(e)
        }
        if (code == 401) throw LoginError.Http(401, "")
        if (code == 429) throw LoginError.Http(429, "Too many attempts. Wait a minute and retry.")
        if (code != 200) throw LoginError.Http(code, bytes.toString(Charsets.UTF_8).take(160))
        return GatewayJson.decodeFromString<NextResponse>(bytes.toString(Charsets.UTF_8)).next
    }

    /** Step 3: code + verifier → tokens. */
    suspend fun exchangeToken(code: String, verifier: String): TokenResponse {
        val body = """{"code":${jsonStr(code)},"code_verifier":${jsonStr(verifier)}}"""
        val req = Request.Builder().url("$base/auth/native/token")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val (httpCode, bytes) = try {
            client.newCall(req).awaitBytes()
        } catch (e: IOException) {
            throw ioError(e)
        }
        if (httpCode != 200) throw LoginError.MissingCode("code exchange failed ($httpCode)")
        return GatewayJson.decodeFromString(bytes.toString(Charsets.UTF_8))
    }

    /** Refresh. 401 session_expired → [LoginError.SessionExpired]; 503 → [LoginError.ProviderUnreachable]. */
    suspend fun refreshToken(refreshToken: String): TokenResponse {
        val body = """{"refresh_token":${jsonStr(refreshToken)},"provider":"$PROVIDER"}"""
        val req = Request.Builder().url("$base/auth/native/refresh")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val (httpCode, bytes) = try {
            client.newCall(req).awaitBytes()
        } catch (e: IOException) {
            throw ioError(e)
        }
        if (httpCode == 401) throw LoginError.SessionExpired()
        if (httpCode == 503) throw LoginError.ProviderUnreachable()
        if (httpCode != 200) throw LoginError.Http(httpCode, bytes.toString(Charsets.UTF_8).take(160))
        return GatewayJson.decodeFromString(bytes.toString(Charsets.UTF_8))
    }

    /** Mint a 30 s single-use WebSocket ticket. */
    suspend fun wsTicket(accessToken: String): String {
        val req = Request.Builder().url("$base/api/auth/ws-ticket")
            .header("Authorization", "Bearer $accessToken")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        val (httpCode, bytes) = try {
            client.newCall(req).awaitBytes()
        } catch (e: IOException) {
            throw ioError(e)
        }
        if (httpCode == 401) throw LoginError.SessionExpired()
        if (httpCode != 200) throw LoginError.Http(httpCode, bytes.toString(Charsets.UTF_8).take(160))
        return GatewayJson.decodeFromString<TicketResponse>(bytes.toString(Charsets.UTF_8)).ticket
    }

    private fun jsonStr(s: String): String =
        buildString {
            append('"')
            for (c in s) when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
            append('"')
        }
}

@Serializable
private data class TicketResponse(val ticket: String, val ttl_seconds: Int = 30)
