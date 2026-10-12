package com.arcrictor.hermes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.arcrictor.hermes.config.Defaults
import com.arcrictor.hermes.gateway.AuthApi
import com.arcrictor.hermes.gateway.GatewayFrame
import com.arcrictor.hermes.gateway.LoginError
import com.arcrictor.hermes.gateway.RpcException
import com.arcrictor.hermes.gateway.SessionRow
import com.arcrictor.hermes.gateway.Transcript
import com.arcrictor.hermes.gateway.UiMessage
import com.arcrictor.hermes.ui.DiagnosticsScreen
import com.arcrictor.hermes.ui.HistoryScreen
import com.arcrictor.hermes.ui.LoginScreen
import com.arcrictor.hermes.ui.LoginUiState
import com.arcrictor.hermes.ui.SessionsScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

private sealed interface Screen {
    data object Sessions : Screen
    data object Diagnostics : Screen
}

private data class OpenSession(
    val row: SessionRow,
    val runtimeId: String,
    val title: String,
    val messages: List<UiMessage>,
    val notice: String?,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as HermesApp
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val scope = rememberCoroutineScope()
                var authed by rememberSaveable { mutableStateOf(false) }
                var screen by remember { mutableStateOf<Screen>(Screen.Sessions) }
                var baseUrl by rememberSaveable { mutableStateOf(Defaults.BASE_URL) }
                var username by rememberSaveable { mutableStateOf(Defaults.USERNAME) }
                var password by rememberSaveable { mutableStateOf(BuildConfig.HERMES_PASSWORD) }
                var autoTried by rememberSaveable { mutableStateOf(false) }
                var loginState by remember { mutableStateOf(LoginUiState()) }
                var readySummary by remember { mutableStateOf<String?>(null) }
                var sessions by remember { mutableStateOf<List<SessionRow>>(emptyList()) }
                var sessionsLoading by remember { mutableStateOf(false) }
                var sessionsError by remember { mutableStateOf<String?>(null) }
                var contractWarning by remember { mutableStateOf<String?>(null) }
                var open by remember { mutableStateOf<OpenSession?>(null) }
                var historyLoading by remember { mutableStateOf(false) }
                val liveRuntimes = remember { mutableStateMapOf<String, String>() } // storedId -> runtimeId
                val connState by app.gateway.connState.collectAsState()
                val lines by app.captureLog.lines.collectAsState()
                val savedUser by app.tokenStore.savedUser.collectAsState(initial = null)
                val hasSaved by app.tokenStore.tokens.collectAsState(initial = null)

                suspend fun doConnect() {
                    val tokens = app.tokenManager.ensureValid()
                    app.captureLog.add("tokens ok for ${tokens.userId}, exp=${tokens.expiresAt}")
                    val api = AuthApi(tokens.baseUrl, app.http)
                    val ticket = api.wsTicket(tokens.accessToken)
                    app.captureLog.add("ticket minted")
                    val ready = app.gateway.connect(tokens.baseUrl, ticket)
                    readySummary =
                        "contract=${ready.capabilities.size} server-request types, heartbeat=${ready.heartbeat}"
                    app.captureLog.add("ready: $readySummary")
                }

                suspend fun refreshSessions(silent: Boolean = false) {
                    if (!silent) {
                        sessionsLoading = true
                        sessionsError = null
                    }
                    try {
                        val rows = app.sessionsApi.list()
                        sessions = rows
                        app.sessionCache.writeSessions(rows)
                    } catch (e: Exception) {
                        if (!silent || sessions.isEmpty()) {
                            sessions = app.sessionCache.readSessions()
                            if (!silent) sessionsError = (e as? LoginError)?.message ?: "Couldn't load sessions: ${e.message}"
                        }
                    } finally {
                        sessionsLoading = false
                    }
                }

                fun checkContract(info: kotlinx.serialization.json.JsonObject) {
                    val n = (info["desktop_contract"] as? JsonPrimitive)?.content?.toIntOrNull()
                    if (n != null && n != 8) {
                        contractWarning =
                            "Server protocol changed (contract $n) — this app was built for 8. Things may misbehave."
                        app.captureLog.add("WARN desktop_contract=$n")
                    }
                }

                suspend fun openSession(row: SessionRow) {
                    historyLoading = true
                    // Instant: cached transcript first.
                    val cached = app.sessionCache.readTranscript(row.id)
                    if (cached != null) {
                        open = OpenSession(row, liveRuntimes[row.id] ?: "", row.title, Transcript.project(cached), null)
                    }
                    try {
                        val snap = app.sessionsApi.resumeStored(row.id)
                        liveRuntimes[snap.storedId()] = snap.session_id
                        checkContract(snap.info)
                        val title = (snap.info["title"] as? JsonPrimitive)
                            ?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                            ?: row.title
                        app.sessionCache.writeTranscript(snap.storedId(), snap.messages)
                        val notices = mutableListOf<String>()
                        snap.open_requests.takeIf { it.isNotEmpty() }?.let {
                            notices.add("${it.size} request(s) from the agent still need an answer (approvals arrive in Phase 5).")
                        }
                        snap.queued?.let { notices.add("A queued message is waiting.") }
                        val liveTail = mutableListOf<UiMessage>()
                        snap.inflight?.let { infl ->
                            if (infl.assistant.isNotBlank() || infl.error != null) {
                                liveTail.add(
                                    UiMessage.Assistant(
                                        infl.assistant.ifBlank { "(no reply yet)" },
                                        null,
                                    ),
                                )
                                notices.add("Reconnected mid-turn — showing what streamed so far.")
                            }
                        }
                        open = OpenSession(
                            row, snap.session_id, title,
                            Transcript.project(snap.messages) + liveTail,
                            notices.takeIf { it.isNotEmpty() }?.joinToString("\n"),
                        )
                    } catch (e: RpcException) {
                        if (e.code == 4130L) {
                            // Transcript too large for live resume: paged REST copy.
                            app.captureLog.add("resume 4130 — falling back to paged history")
                            val msgs = app.sessionsApi.messagesPage(row.id)
                            app.sessionCache.writeTranscript(row.id, msgs)
                            open = OpenSession(
                                row, "", row.title, Transcript.project(msgs),
                                "Transcript too large for live view — showing a paged copy.",
                            )
                        } else {
                            open = OpenSession(
                                row, "", row.title,
                                cached?.let(Transcript::project) ?: emptyList(),
                                "Couldn't open live session: ${e.message}",
                            )
                        }
                    } catch (e: Exception) {
                        open = OpenSession(
                            row, "", row.title,
                            cached?.let(Transcript::project) ?: emptyList(),
                            "Couldn't open live session: ${(e as? LoginError)?.message ?: e.message}",
                        )
                    } finally {
                        historyLoading = false
                    }
                }

                fun runLogin(fresh: Map<String, String>?) {
                    scope.launch {
                        loginState = LoginUiState(working = true)
                        app.captureLog.clear()
                        try {
                            if (fresh != null) {
                                app.tokenManager.login(
                                    baseUrl, fresh.getValue("u"), fresh.getValue("p"),
                                ) { step ->
                                    loginState = loginState.copy(steps = loginState.steps + step)
                                    app.captureLog.add(step)
                                }
                                app.captureLog.add("login ok")
                            }
                            doConnect()
                            password = "" // never retain the password in memory longer than needed
                            loginState = LoginUiState()
                            authed = true
                            screen = Screen.Sessions
                            refreshSessions()
                        } catch (e: Exception) {
                            val msg = (e as? LoginError)?.message ?: "Failed: ${e.message}"
                            app.captureLog.add("ERROR $msg")
                            loginState = LoginUiState(error = msg)
                        }
                    }
                }

                // sessions.changed → silent refetch while signed in.
                LaunchedEffect(authed) {
                    if (!authed) return@LaunchedEffect
                    app.gateway.frames.collect { frame ->
                        if (frame is GatewayFrame.Event && frame.type == "sessions.changed") {
                            app.captureLog.add("sessions.changed — refetching")
                            refreshSessions(silent = true)
                        }
                    }
                }

                if (!authed) {
                    LaunchedEffect(Unit) {
                        // Prefill saved endpoint once.
                        val b = app.tokenStore.savedBaseUrl.first()
                        if (b != null) baseUrl = b
                        val u = app.tokenStore.savedUser.first()
                        if (u != null && username.isEmpty()) username = u
                        // Baked-in password: sign in silently on launch (once per process).
                        if (!autoTried && password.isNotBlank() && username.isNotBlank()) {
                            autoTried = true
                            runLogin(mapOf("u" to username, "p" to password))
                        }
                    }
                    LoginScreen(
                        baseUrl = baseUrl, username = username, password = password,
                        savedUser = savedUser, hasSavedSession = hasSaved != null,
                        state = loginState,
                        onBaseUrl = { baseUrl = it }, onUsername = { username = it },
                        onPassword = { password = it },
                        onLogin = { runLogin(mapOf("u" to username, "p" to password)) },
                        onReconnect = { runLogin(null) },
                    )
                } else if (open != null) {
                    val o = open!!
                    HistoryScreen(
                        title = o.title,
                        messages = o.messages,
                        loading = historyLoading,
                        notice = o.notice,
                        images = app.serverImages,
                        onBack = { open = null },
                        onRefresh = { scope.launch { openSession(o.row) } },
                    )
                } else when (screen) {
                    Screen.Sessions -> SessionsScreen(
                        sessions = sessions, loading = sessionsLoading, error = sessionsError,
                        contractWarning = contractWarning,
                        onRefresh = { scope.launch { refreshSessions() } },
                        onOpen = { row -> scope.launch { openSession(row) } },
                        onCreate = { title ->
                            scope.launch {
                                try {
                                    val snap = app.sessionsApi.create(title.ifBlank { null })
                                    checkContract(snap.info)
                                    refreshSessions()
                                } catch (e: Exception) {
                                    sessionsError = (e as? LoginError)?.message ?: "Couldn't create: ${e.message}"
                                }
                            }
                        },
                        onRename = { row, title ->
                            scope.launch {
                                try {
                                    val runtime = liveRuntimes[row.id]
                                    if (runtime != null) {
                                        app.sessionsApi.setTitle(runtime, title)
                                    } else {
                                        // Not live here: resume to rename, then close.
                                        val snap = app.sessionsApi.resumeStored(row.id)
                                        app.sessionsApi.setTitle(snap.session_id, title)
                                        app.sessionsApi.closeRuntime(snap.session_id)
                                    }
                                    refreshSessions()
                                } catch (e: Exception) {
                                    sessionsError = (e as? LoginError)?.message ?: "Couldn't rename: ${e.message}"
                                }
                            }
                        },
                        onDelete = { row ->
                            scope.launch {
                                try {
                                    app.sessionsApi.deleteStored(row.id, liveRuntimes[row.id])
                                    liveRuntimes.remove(row.id)
                                    sessions = sessions.filterNot { it.id == row.id }
                                } catch (e: Exception) {
                                    sessionsError = (e as? LoginError)?.message ?: "Couldn't delete: ${e.message}"
                                }
                            }
                        },
                        onDiagnostics = { screen = Screen.Diagnostics },
                    )
                    Screen.Diagnostics -> DiagnosticsScreen(
                        connState = connState, readySummary = readySummary, lines = lines,
                        onExport = {
                            scope.launch {
                                val uri = app.captureLog.export(this@MainActivity)
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                startActivity(Intent.createChooser(share, "Share capture"))
                            }
                        },
                        onDisconnect = {
                            scope.launch {
                                app.gateway.disconnect()
                                app.tokenManager.logout()
                                open = null
                                authed = false
                                screen = Screen.Sessions
                            }
                        },
                        onBack = { screen = Screen.Sessions },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        (application as HermesApp).gateway.disconnect()
        super.onDestroy()
    }
}
