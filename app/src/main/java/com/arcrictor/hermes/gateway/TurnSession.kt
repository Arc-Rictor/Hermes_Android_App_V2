package com.arcrictor.hermes.gateway

import com.arcrictor.hermes.turn.GatewayEvent
import com.arcrictor.hermes.turn.TurnReducer
import com.arcrictor.hermes.turn.TurnState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * One open chat session: live [TurnState], the growing transcript tail, and the
 * PROTOCOL.md §5 reconnect procedure. App-scoped (held by HermesApp per stored
 * id) so rotation keeps the turn; [close] on back-out. The runtime is never
 * closed here — it may be shared with another client and idle runtimes are
 * reaped server-side.
 */
class TurnSession(
    private val gateway: GatewayClient,
    private val api: SessionsApi,
    appScope: CoroutineScope,
    private val ensureConnected: suspend () -> GatewayClient.ReadyInfo,
    initialStoredId: String,
    initialRuntimeId: String,
    snapshot: LiveSessionSnapshot,
    private val log: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob())
    private val recoverMutex = Mutex()

    var storedId: String = initialStoredId
        private set
    var runtimeId: String = initialRuntimeId
        private set

    private val _state = MutableStateFlow(
        TurnReducer.forSnapshot(snapshot.inflight, snapshot.open_requests.size, nowMs()),
    )
    val state: StateFlow<TurnState> = _state

    private val _history = MutableStateFlow(Transcript.project(snapshot.messages))
    val history: StateFlow<List<UiMessage>> = _history

    /** Live bubbles (sent/confirmed) plus turns frozen on `message.complete`. */
    private val _extras = MutableStateFlow<List<UiMessage>>(emptyList())
    val extras: StateFlow<List<UiMessage>> = _extras

    private val _recovering = MutableStateFlow(false)
    val recovering: StateFlow<Boolean> = _recovering

    private var watermark = 0L
    private var pendingUserText: String? = snapshot.inflight?.user?.takeIf { it.isNotBlank() }

    /** Frames arriving during activate are held, then applied after the replay. */
    @Volatile private var holding = false
    private val held = mutableListOf<GatewayFrame.Event>()

    init {
        scope.launch {
            gateway.frames.collect { frame ->
                if (frame !is GatewayFrame.Event || frame.sessionId != runtimeId) return@collect
                if (frame.sessionId.isBlank()) return@collect
                synchronized(held) {
                    if (holding) {
                        held.add(frame)
                        return@collect
                    }
                }
                apply(frame)
            }
        }
        scope.launch {
            gateway.connState.collect { if (it == GatewayClient.ConnState.FAILED) recover() }
        }
    }

    fun close() {
        scope.cancel()
    }

    /** Replace the transcript base (oversized-resume paged copy). */
    fun setHistory(messages: List<HistoryMessage>) {
        _history.value = Transcript.project(messages)
    }

    /** Send text; labels the bubble when the server queues/steers/redirects it. */
    suspend fun send(text: String) {
        ensureConnected()
        val status = api.submit(runtimeId, text)
        pendingUserText = text
        _extras.update { it + UiMessage.User(text, emptyList(), emptyList(), submitLabel(status)) }
    }

    /** Stop the running turn. Best-effort RPC; the local state stops regardless. */
    suspend fun stop() {
        try {
            ensureConnected()
            api.interrupt(runtimeId)
        } catch (e: Exception) {
            log("interrupt failed: ${e.message}")
        }
        _state.update { TurnReducer.reduce(it, GatewayEvent.Interrupted, nowMs()) }
    }

    private fun apply(frame: GatewayFrame.Event) {
        if (frame.type == "session.reclaimed") {
            log("session.reclaimed — resuming")
            scope.launch { recover() }
            return
        }
        val adopted = gateway.readyInfo?.replayEpoch ?: ""
        val selected = Replay.select(listOf(frame), watermark, adopted, adopted)
        for (e in selected) {
            val event = GatewayEvent.from(e) ?: continue
            e.seq?.let { watermark = maxOf(watermark, it) }
            val before = _state.value
            _state.update { TurnReducer.reduce(it, event, nowMs()) }
            if (event is GatewayEvent.MessageComplete) freeze(before)
        }
    }

    /** A finished turn becomes transcript rows; the live state resets for the next turn. */
    private fun freeze(before: TurnState) {
        val s = _state.value
        val rows = buildList {
            s.notes.forEach { add(UiMessage.Assistant(it, null)) }
            s.tools.forEach { t ->
                val detail = listOf(
                    t.summary.ifBlank { t.preview },
                    t.displayDuration(nowMs()) + if (t.risky) " · flagged risky" else "",
                ).filter { it.isNotBlank() }.joinToString(" · ")
                add(UiMessage.Tool(t.name, (if (t.failed) "failed: " else "") + detail))
            }
            if (s.reply.isNotBlank() || s.thinking.isNotBlank()) {
                add(UiMessage.Assistant(s.reply, s.thinking.ifBlank { null }, s.usage?.label()))
            }
        }
        // The sent bubble is already on screen; a foreign turn has no prompt row.
        _extras.update { it + rows }
        pendingUserText = null
        _state.value = TurnState(openRequestCount = before.openRequestCount)
    }

    /**
     * Reconnect procedure, PROTOCOL.md §5: activate → events.since → held frames,
     * resume fallback. Concurrent triggers collapse into one run.
     */
    suspend fun recover() {
        if (!recoverMutex.tryLock()) return
        try {
            doRecover()
        } finally {
            recoverMutex.unlock()
        }
    }

    private suspend fun doRecover() {
        val wasAlive = _state.value.turnAlive
        _recovering.value = true
        try {
            // Backoff 1 s → 30 s across both reconnect and replay attempts;
            // cancelled by close() when the user backs out.
            var attempt = 0
            while (true) {
                try {
                    ensureConnected()
                    if (recoverSession(wasAlive)) return
                } catch (e: Exception) {
                    log("recover attempt failed: ${e.message}")
                }
                delay(minOf(1_000L shl attempt.coerceAtMost(5), 30_000L))
                attempt++
            }
        } finally {
            _recovering.value = false
        }
    }

    /**
     * One §5 attempt after the socket is ready. Returns true when settled
     * (replayed or fell back to resume), false to retry with backoff.
     */
    private suspend fun recoverSession(wasAlive: Boolean): Boolean {
        val adopted = gateway.readyInfo?.replayEpoch ?: ""
        synchronized(held) {
            holding = true
            held.clear()
        }
        try {
            val snap = try {
                api.activate(runtimeId)
            } catch (e: RpcException) {
                if (e.code == 4001L || e.code == 4007L) {
                    resumeFallback(wasAlive, "session gone ($e)")
                    return true
                }
                return false
            } catch (_: Exception) {
                return false
            }
            storedId = snap.storedId().also { if (it != storedId) log("stored id rotated") }
            _state.update { it.copy(openRequestCount = snap.open_requests.size) }
            val since = try {
                api.eventsSince(runtimeId, watermark)
            } catch (_: Exception) {
                return false
            }
            if (since.truncated) {
                resumeFallback(wasAlive, "replay truncated at ${since.latest_seq}")
                return true
            }
            _state.update { it.copy(openRequestCount = since.open_requests.size) }
            val replayed = Replay.select(since.parsedEvents(), watermark, since.epoch, adopted)
            val heldNow = synchronized(held) { held.toList().also { held.clear() } }
            val ordered = Replay.select(replayed + heldNow, watermark, since.epoch, adopted)
            for (e in ordered) {
                if (e.sessionId != runtimeId) continue
                val event = GatewayEvent.from(e) ?: continue
                e.seq?.let { watermark = maxOf(watermark, it) }
                val before = _state.value
                _state.update { TurnReducer.reduce(it, event, nowMs()) }
                if (event is GatewayEvent.MessageComplete) freeze(before)
            }
            log("recovered: ${ordered.size} replayed")
            return true
        } finally {
            synchronized(held) { holding = false }
        }
    }

    private suspend fun resumeFallback(wasAlive: Boolean, reason: String) {
        log("resume fallback ($reason)")
        try {
            val snap = api.resumeStored(storedId)
            storedId = snap.storedId()
            runtimeId = snap.session_id
            watermark = 0
            _history.value = Transcript.project(snap.messages)
            pendingUserText = snap.inflight?.user?.takeIf { it.isNotBlank() }
            _state.value = TurnReducer.forSnapshot(snap.inflight, snap.open_requests.size, nowMs())
                .let {
                    if (wasAlive) it.copy(gapNotice = "Reconnected — earlier live steps not shown.")
                    else it
                }
        } catch (e: Exception) {
            log("resume fallback failed: ${e.message}")
        }
    }

    private fun submitLabel(status: String): String? = when (status) {
        "queued" -> "Queued"
        "steered" -> "Steering"
        "redirected" -> "Redirected"
        else -> null
    }

    private fun nowMs(): Long = System.currentTimeMillis()
}
