package com.arcrictor.hermes

import android.app.Application
import com.arcrictor.hermes.data.SessionCache
import com.arcrictor.hermes.data.TokenStore
import com.arcrictor.hermes.diagnostics.CaptureLog
import com.arcrictor.hermes.gateway.AuthApi
import com.arcrictor.hermes.gateway.GatewayClient
import com.arcrictor.hermes.gateway.LiveSessionSnapshot
import com.arcrictor.hermes.gateway.SessionsApi
import com.arcrictor.hermes.gateway.TokenManager
import com.arcrictor.hermes.gateway.TurnSession
import com.arcrictor.hermes.media.ServerImages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Process-wide singletons. Survives rotation; dies with the process. */
class HermesApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false) // PKCE cookie is harvested from the 302 manually
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val captureLog by lazy { CaptureLog() }
    val tokenStore by lazy { TokenStore(this) }
    val tokenManager by lazy { TokenManager(tokenStore) { AuthApi(it, http) } }
    val sessionCache by lazy { SessionCache(this) }
    val gateway: GatewayClient by lazy { GatewayClient(http, appScope) { captureLog.add(it) } }
    val sessionsApi by lazy {
        SessionsApi(gateway, tokenManager, ensureConnected = { ensureConnected() }) { AuthApi(it, http) }
    }
    val serverImages by lazy { ServerImages(sessionsApi) }

    private val connectMutex = Mutex()

    /**
     * Live chat sessions by stored id. Survive rotation with the process;
     * released when the user backs out. Same runtime → same instance.
     */
    private val turnSessions = ConcurrentHashMap<String, TurnSession>()

    fun acquireTurnSession(
        storedId: String,
        runtimeId: String,
        snapshot: LiveSessionSnapshot,
    ): TurnSession {
        val existing = turnSessions[storedId]
        if (existing != null && existing.runtimeId == runtimeId) return existing
        existing?.close()
        // Server→client requests route to the session whose runtime id they name.
        // Unknown methods get -32601; known cards wait for the user (Phase 5).
        gateway.serverRequestHandler = { req ->
            val sid = req.params.string("session_id")
            turnSessions.values.firstOrNull { it.runtimeId == sid }?.handleServerRequest(req)
                ?: GatewayClient.HandlerAnswer.Error(-32601, "no handler for server request: ${req.method}")
        }
        return TurnSession(gateway, sessionsApi, appScope, ::ensureConnected, storedId, runtimeId, snapshot) {
            captureLog.add(it)
        }.also { turnSessions[storedId] = it }
    }

    fun releaseTurnSession(session: TurnSession) {
        turnSessions.entries.removeIf { it.value === session }
        session.close()
    }

    /**
     * Returns a ready socket, (re)connecting with a fresh ticket if it dropped
     * (screen off, network change, heartbeat timeout). Concurrent callers share one attempt.
     * ponytail: reconnect-on-demand only; seq replay of missed events is Phase 3.
     */
    suspend fun ensureConnected(): GatewayClient.ReadyInfo = connectMutex.withLock {
        gateway.readyInfo?.takeIf { gateway.connState.value == GatewayClient.ConnState.READY }
            ?.let { return it }
        val tokens = tokenManager.ensureValid()
        val ticket = AuthApi(tokens.baseUrl, http).wsTicket(tokens.accessToken)
        captureLog.add("ticket minted for ${tokens.userId}")
        gateway.connect(tokens.baseUrl, ticket)
    }
}
