package com.arcrictor.hermes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.arcrictor.hermes.config.Defaults
import com.arcrictor.hermes.gateway.GatewayClient
import com.arcrictor.hermes.gateway.GatewayFrame
import com.arcrictor.hermes.gateway.LiveSessionSnapshot
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private enum class Screen { Sessions, Diagnostics }

private data class OpenSession(
    val row: SessionRow,
    val runtimeId: String,
    val title: String,
    val messages: List<UiMessage>,
    val notice: String?,
)

private fun Exception.userMessage(prefix: String) = (this as? LoginError)?.message ?: "$prefix: $message"

/** Transcript + notices for a resume snapshot (pure; no UI state). */
private fun openSessionFrom(row: SessionRow, snap: LiveSessionSnapshot): OpenSession {
    val title = (snap.info["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotBlank() } ?: row.title
    val notices = buildList {
        if (snap.open_requests.isNotEmpty()) {
            add("${snap.open_requests.size} request(s) from the agent still need an answer (approvals arrive in Phase 5).")
        }
        if (snap.queued != null) add("A queued message is waiting.")
    }.toMutableList()
    val liveTail = snap.inflight
        ?.takeIf { it.assistant.isNotBlank() || it.error != null }
        ?.let {
            notices.add("Reconnected mid-turn — showing what streamed so far.")
            listOf(UiMessage.Assistant(it.assistant.ifBlank { "(no reply yet)" }, null))
        } ?: emptyList()
    return OpenSession(
        row, snap.session_id, title,
        Transcript.project(snap.messages) + liveTail,
        notices.takeIf { it.isNotEmpty() }?.joinToString("\n"),
    )
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as HermesApp
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing),
                    ) {
                        val scope = rememberCoroutineScope()
                        var authed by rememberSaveable { mutableStateOf(false) }
                        var screen by rememberSaveable { mutableStateOf(Screen.Sessions) }
                        var baseUrl by rememberSaveable { mutableStateOf(Defaults.BASE_URL) }
                        var username by rememberSaveable { mutableStateOf(Defaults.USERNAME) }
                        var password by rememberSaveable { mutableStateOf(BuildConfig.HERMES_PASSWORD) }
                        var autoTried by rememberSaveable { mutableStateOf(false) }
                        var loginState by remember { mutableStateOf(LoginUiState()) }
                        var sessions by remember { mutableStateOf<List<SessionRow>>(emptyList()) }
                        var sessionsLoading by remember { mutableStateOf(false) }
                        var sessionsError by remember { mutableStateOf<String?>(null) }
                        var contractWarning by remember { mutableStateOf<String?>(null) }
                        var open by remember { mutableStateOf<OpenSession?>(null) }
                        /** Stored id the user most recently asked to open; null after Back. Guards stale results. */
                        var openingId by remember { mutableStateOf<String?>(null) }
                        var historyLoading by remember { mutableStateOf(false) }
                        val liveRuntimes = remember { mutableStateMapOf<String, String>() } // storedId -> runtimeId
                        val connState by app.gateway.connState.collectAsState()
                        val lines by app.captureLog.lines.collectAsState()
                        val savedUser by app.tokenStore.savedUser.collectAsState(initial = null)
                        val hasSaved by app.tokenStore.tokens.collectAsState(initial = null)

                        suspend fun refreshSessions(silent: Boolean = false) {
                            if (!silent) {
                                sessionsLoading = true
                                sessionsError = null
                            }
                            try {
                                sessions = app.sessionsApi.list().also { app.sessionCache.writeSessions(it) }
                            } catch (e: Exception) {
                                if (sessions.isEmpty()) sessions = app.sessionCache.readSessions()
                                if (!silent) sessionsError = e.userMessage("Couldn't load sessions")
                            } finally {
                                sessionsLoading = false
                            }
                        }

                        fun checkContract(info: JsonObject) {
                            val n = (info["desktop_contract"] as? JsonPrimitive)?.content?.toIntOrNull()
                            if (n != null && n != 8) {
                                contractWarning =
                                    "Server protocol changed (contract $n) — this app was built for 8. Things may misbehave."
                                app.captureLog.add("WARN desktop_contract=$n")
                            }
                        }

                        suspend fun openSession(row: SessionRow) {
                            openingId = row.id
                            historyLoading = true
                            fun stillWanted() = openingId == row.id
                            val cached = app.sessionCache.readTranscript(row.id)
                            if (cached != null && stillWanted()) {
                                open = OpenSession(row, liveRuntimes[row.id] ?: "", row.title, Transcript.project(cached), null)
                            }
                            val result: OpenSession = try {
                                val snap = app.sessionsApi.resumeStored(row.id)
                                liveRuntimes[snap.storedId()] = snap.session_id
                                checkContract(snap.info)
                                app.sessionCache.writeTranscript(snap.storedId(), snap.messages)
                                openSessionFrom(row, snap)
                            } catch (e: RpcException) {
                                if (e.code == 4130L) {
                                    // Transcript too large for live resume: paged REST copy.
                                    app.captureLog.add("resume 4130 — falling back to paged history")
                                    val msgs = app.sessionsApi.messagesPage(row.id)
                                    app.sessionCache.writeTranscript(row.id, msgs)
                                    OpenSession(row, "", row.title, Transcript.project(msgs),
                                        "Transcript too large for live view — showing a paged copy.")
                                } else {
                                    OpenSession(row, "", row.title, cached?.let(Transcript::project) ?: emptyList(),
                                        "Couldn't open live session: ${e.message}")
                                }
                            } catch (e: Exception) {
                                OpenSession(row, "", row.title, cached?.let(Transcript::project) ?: emptyList(),
                                    e.userMessage("Couldn't open live session"))
                            }
                            if (stillWanted()) {
                                open = result
                                historyLoading = false
                            }
                        }

                        /** [creds] = fresh password login; null = reuse saved tokens, falling back to the baked-in password. */
                        fun runLogin(creds: Pair<String, String>?) {
                            scope.launch {
                                loginState = LoginUiState(working = true)
                                app.captureLog.clear()
                                suspend fun passwordLogin(u: String, p: String) {
                                    app.tokenManager.login(baseUrl, u, p) { step ->
                                        loginState = loginState.copy(steps = loginState.steps + step)
                                        app.captureLog.add(step)
                                    }
                                    app.captureLog.add("login ok")
                                }
                                try {
                                    if (creds != null) {
                                        passwordLogin(creds.first, creds.second)
                                        app.ensureConnected()
                                    } else {
                                        try {
                                            app.ensureConnected()
                                        } catch (e: LoginError.SessionExpired) {
                                            if (BuildConfig.HERMES_PASSWORD.isBlank()) throw e
                                            app.captureLog.add("saved session expired — signing in with built-in password")
                                            passwordLogin(username, BuildConfig.HERMES_PASSWORD)
                                            app.ensureConnected()
                                        }
                                    }
                                    password = "" // the field never needs to hold it after sign-in
                                    loginState = LoginUiState()
                                    authed = true
                                    screen = Screen.Sessions
                                } catch (e: Exception) {
                                    val msg = e.userMessage("Failed")
                                    app.captureLog.add("ERROR $msg")
                                    loginState = LoginUiState(error = msg)
                                }
                            }
                        }

                        // While signed in: load the list once, then follow sessions.changed.
                        LaunchedEffect(authed) {
                            if (!authed) return@LaunchedEffect
                            refreshSessions()
                            app.gateway.frames.collect { frame ->
                                if (frame is GatewayFrame.Event && frame.type == "sessions.changed") {
                                    app.captureLog.add("sessions.changed — refetching")
                                    refreshSessions(silent = true)
                                }
                            }
                        }

                        if (!authed) {
                            LaunchedEffect(Unit) {
                                app.tokenStore.savedBaseUrl.first()?.let { baseUrl = it }
                                app.tokenStore.savedUser.first()?.let { if (username.isEmpty()) username = it }
                                // Sign in silently once per process: saved tokens first, else built-in password.
                                if (!autoTried) {
                                    autoTried = true
                                    when {
                                        app.tokenStore.tokens.first() != null -> runLogin(null)
                                        password.isNotBlank() && username.isNotBlank() -> runLogin(username to password)
                                    }
                                }
                            }
                            LoginScreen(
                                baseUrl = baseUrl, username = username, password = password,
                                savedUser = savedUser, hasSavedSession = hasSaved != null,
                                state = loginState,
                                onBaseUrl = { baseUrl = it }, onUsername = { username = it },
                                onPassword = { password = it },
                                onLogin = { runLogin(username to password) },
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
                                onBack = {
                                    openingId = null
                                    open = null
                                    historyLoading = false
                                },
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
                                            checkContract(app.sessionsApi.create(title.ifBlank { null }).info)
                                            refreshSessions()
                                        } catch (e: Exception) {
                                            sessionsError = e.userMessage("Couldn't create")
                                        }
                                    }
                                },
                                onRename = { row, title ->
                                    scope.launch {
                                        try {
                                            app.sessionsApi.rename(row.id, liveRuntimes[row.id], title)
                                            refreshSessions()
                                        } catch (e: Exception) {
                                            sessionsError = e.userMessage("Couldn't rename")
                                        }
                                    }
                                },
                                onDelete = { row ->
                                    scope.launch {
                                        try {
                                            app.sessionsApi.deleteStored(row.id, liveRuntimes.remove(row.id))
                                            sessions = sessions.filterNot { it.id == row.id }
                                        } catch (e: Exception) {
                                            sessionsError = e.userMessage("Couldn't delete")
                                        }
                                    }
                                },
                                onDiagnostics = { screen = Screen.Diagnostics },
                            )
                            Screen.Diagnostics -> DiagnosticsScreen(
                                connState = connState,
                                readySummary = app.gateway.readyInfo
                                    ?.takeIf { connState == GatewayClient.ConnState.READY }
                                    ?.let { "${it.capabilities.size} server-request types, heartbeat=${it.heartbeat}" },
                                versionName = BuildConfig.VERSION_NAME,
                                lines = lines,
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
                                        openingId = null
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
        }
    }

    override fun onDestroy() {
        // Rotation recreates the activity: keep the app-scoped socket alive across it.
        if (isFinishing && !isChangingConfigurations) (application as HermesApp).gateway.disconnect()
        super.onDestroy()
    }
}
