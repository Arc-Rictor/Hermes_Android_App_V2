package com.arcrictor.hermes.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class RpcException(val code: Long, message: String) : Exception("RPC $code: $message")
class RpcTimeout(method: String) : Exception("Timed out waiting for $method")

/**
 * JSON-RPC over /api/ws. Owns one socket, the request/response map, the heartbeat,
 * and server→client request dispatch. See PROTOCOL.md §3–§4.
 *
 * Scope so far: connect (ticket → ready → capabilities), request/response, heartbeat
 * watchdog, clean shutdown. Callers reconnect via HermesApp.ensureConnected();
 * seq-based replay arrives in Phase 3.
 */
class GatewayClient(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    /** Sink for protocol lines. Must redact (CaptureLog does) — raw frames pass through. */
    private val log: (String) -> Unit = {},
) {
    enum class ConnState { IDLE, CONNECTING, READY, FAILED }

    private val _connState = MutableStateFlow(ConnState.IDLE)
    val connState: StateFlow<ConnState> = _connState

    /** Every parsed inbound frame. Buffer 256; overflow is logged and dropped (never crash). */
    val frames = MutableSharedFlow<GatewayFrame>(extraBufferCapacity = 256)

    /** Answers server→client requests. Default declines everything with -32601. */
    var serverRequestHandler: suspend (GatewayFrame.ServerRequest) -> HandlerAnswer =
        { HandlerAnswer.Error(-32601, "no handler for server request") }

    sealed interface HandlerAnswer {
        data class Result(val result: JsonObject) : HandlerAnswer
        data class Error(val code: Long, val message: String) : HandlerAnswer
    }

    data class ReadyInfo(
        val heartbeat: Boolean,
        val replayEpoch: String,
        val capabilities: List<String>,
    )

    var readyInfo: ReadyInfo? = null
        private set
    var lastError: String? = null
        private set

    private val idCounter = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<GatewayFrame.Response>>()
    @Volatile private var readySignal: CompletableDeferred<GatewayFrame.Event>? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var generation: Long = 0
    private var heartbeatJob: Job? = null

    /**
     * Opens the socket with a fresh single-use ticket, waits for `gateway.ready`,
     * sends `client.capabilities`, and starts the heartbeat when advertised.
     * @throws LoginError on transport/auth failure, RpcException on protocol failure.
     */
    suspend fun connect(baseUrl: String, ticket: String): ReadyInfo {
        disconnect()
        val gen = ++generation
        _connState.value = ConnState.CONNECTING
        val base = baseUrl.trim().removeSuffix("/")
        val url = "$base/api/ws?ticket=" + URLEncoder.encode(ticket, "UTF-8")
        log("WS connecting $url")
        val opened = CompletableDeferred<Unit>()
        // Must exist before the socket opens: gateway.ready can land right after onOpen.
        val ready = CompletableDeferred<GatewayFrame.Event>().also { readySignal = it }
        val ws = http.newWebSocket(
            Request.Builder().url(url)
                .header("Sec-WebSocket-Protocol", "hermes-gateway-v1")
                .build(),
            Listener(gen, opened),
        )
        socket = ws
        try {
            try {
                withTimeout(15_000) { opened.await() }
            } catch (e: TimeoutCancellationException) {
                throw LoginError.Network(e, AuthApi.hostOf(base))
            }
            val payload = try {
                withTimeout(15_000) { ready.await() }.payload
            } catch (e: TimeoutCancellationException) {
                throw RpcException(-1, "server did not say hello")
            }
            val caps = request(
                "client.capabilities",
                buildJsonObject { put("server_requests", JsonPrimitive(true)) },
                gen = gen,
            )
            val info = ReadyInfo(
                heartbeat = (payload["heartbeat"] as? JsonPrimitive)?.content == "true",
                replayEpoch = (payload["replay_epoch"] as? JsonPrimitive)?.content ?: "",
                capabilities = (caps["server_requests"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    ?: emptyList(),
            )
            readyInfo = info
            _connState.value = ConnState.READY
            log("WS ready (heartbeat=${info.heartbeat}, server_requests=${info.capabilities.size})")
            if (info.heartbeat) startHeartbeat(gen)
            return info
        } catch (e: Exception) {
            fail(gen, e.message ?: "handshake failed")
            throw e
        } finally {
            readySignal = null
        }
    }

    /** One RPC call. Times out after [timeoutMs] (default 120 s; approvals use 300 s). */
    suspend fun request(
        method: String,
        params: JsonObject,
        timeoutMs: Long = 120_000,
        gen: Long = generation,
    ): JsonObject {
        val id = "a" + idCounter.incrementAndGet()
        val done = CompletableDeferred<GatewayFrame.Response>()
        pending[id] = done
        val ws = socket
        if (ws == null || gen != generation) {
            pending.remove(id)
            throw RpcException(-1, "not connected")
        }
        if (!ws.send(JsonRpc.request(id, method, params))) {
            pending.remove(id)
            throw RpcException(-1, "send queue full")
        }
        try {
            val resp = withTimeout(timeoutMs) { done.await() }
            resp.error?.let { throw RpcException(it.code, it.message) }
            return resp.result as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: TimeoutCancellationException) {
            throw RpcTimeout(method)
        } finally {
            pending.remove(id)
        }
    }

    fun disconnect() {
        generation++
        heartbeatJob?.cancel()
        heartbeatJob = null
        try {
            socket?.close(1000, "client disconnect")
        } catch (_: Exception) {
        }
        socket = null
        failPending(CancellationException("disconnected"))
        if (_connState.value != ConnState.IDLE) _connState.value = ConnState.IDLE
    }

    /** Last inbound frame (or ping reply), epoch ms. Any traffic counts as alive. */
    @Volatile private var lastInboundMs: Long = 0

    private fun startHeartbeat(gen: Long) {
        heartbeatJob?.cancel()
        lastInboundMs = System.currentTimeMillis()
        heartbeatJob = scope.launch {
            while (isActive && gen == generation) {
                delay(15_000)
                if (gen != generation) return@launch
                if (System.currentTimeMillis() - lastInboundMs > 45_000) {
                    log("heartbeat deadline missed — dropping socket")
                    fail(gen, "heartbeat timeout")
                    return@launch
                }
                try {
                    request("gateway.ping", JsonObject(emptyMap()), timeoutMs = 20_000, gen = gen)
                } catch (_: Exception) {
                    // A failed ping alone is not fatal; the deadline above decides.
                }
            }
        }
    }

    private fun poke() {
        lastInboundMs = System.currentTimeMillis()
    }

    private fun fail(gen: Long, reason: String) {
        if (gen != generation) return
        lastError = reason
        _connState.value = ConnState.FAILED
        heartbeatJob?.cancel()
        failPending(Exception(reason))
        try {
            socket?.close(1000, reason)
        } catch (_: Exception) {
        }
        socket = null
    }

    private fun failPending(cause: Throwable) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            it.next().value.completeExceptionally(cause)
            it.remove()
        }
    }

    private inner class Listener(
        private val gen: Long,
        private val opened: CompletableDeferred<Unit>,
    ) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (gen == generation) opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (gen != generation) return
            poke()
            val frame = JsonRpc.classify(text)
            log("← ${text.take(300)}")
            when (frame) {
                is GatewayFrame.Response -> pending.remove(frame.id)?.complete(frame)
                is GatewayFrame.ServerRequest -> scope.launch {
                    val answer = try {
                        serverRequestHandler(frame)
                    } catch (e: Exception) {
                        HandlerAnswer.Error(-32603, e.message ?: "handler failed")
                    }
                    if (gen == generation) {
                        val ws = socket
                        val payload = when (answer) {
                            is HandlerAnswer.Result -> JsonRpc.answer(frame.id, answer.result)
                            is HandlerAnswer.Error -> JsonRpc.errorAnswer(frame.id, answer.code, answer.message)
                        }
                        try {
                            ws?.send(payload)
                        } catch (_: Exception) {
                        }
                    }
                }
                is GatewayFrame.Event, is GatewayFrame.Unparseable -> {
                    if (frame is GatewayFrame.Event && frame.type == "gateway.ready") {
                        readySignal?.complete(frame)
                    }
                    if (!frames.tryEmit(frame)) log("frame buffer full — dropped ${frame.javaClass.simpleName}")
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!opened.isCompleted) {
                val code = response?.code
                val reason = when (code) {
                    403 -> "login rejected (ticket expired or revoked)"
                    else -> t.message ?: "connection failed"
                }
                opened.completeExceptionally(LoginError.Http(code ?: -1, reason))
            } else if (gen == generation) {
                fail(gen, t.message ?: "socket failed")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            if (gen == generation && _connState.value != ConnState.IDLE) {
                fail(gen, "server closed ($code)")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (gen == generation && _connState.value != ConnState.IDLE) {
                fail(gen, "socket closed ($code)")
            }
        }
    }
}
