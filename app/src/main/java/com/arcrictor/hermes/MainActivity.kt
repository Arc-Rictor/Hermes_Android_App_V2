package com.arcrictor.hermes

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.arcrictor.hermes.config.Defaults
import com.arcrictor.hermes.gateway.GatewayClient
import com.arcrictor.hermes.gateway.GatewayFrame
import com.arcrictor.hermes.gateway.LiveSessionSnapshot
import com.arcrictor.hermes.gateway.LoginError
import com.arcrictor.hermes.gateway.RpcException
import com.arcrictor.hermes.gateway.SessionRow
import com.arcrictor.hermes.gateway.TurnSession
import com.arcrictor.hermes.ui.ChatScreen
import com.arcrictor.hermes.ui.DiagnosticsScreen
import com.arcrictor.hermes.ui.LoginScreen
import com.arcrictor.hermes.ui.LoginUiState
import com.arcrictor.hermes.ui.SessionsScreen
import com.arcrictor.hermes.ui.SharedFile
import com.arcrictor.hermes.work.TurnService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private enum class Screen { Sessions, Diagnostics }

private data class ChatOpen(
    val row: SessionRow,
    val title: String,
    val session: TurnSession,
    val notice: String?,
    val draft: String = "",
    val shared: List<SharedFile> = emptyList(),
)

private fun Exception.userMessage(prefix: String) = (this as? LoginError)?.message ?: "$prefix: $message"

/** Notices for a resume snapshot (snapshot cards arrive in Phase 5). */
private fun chatNotice(snap: LiveSessionSnapshot): String? {
    val notices = buildList {
        if (snap.queued != null) add("A queued message is waiting.")
    }
    return notices.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

private fun titleOf(snap: LiveSessionSnapshot, fallback: String): String =
    (snap.info["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotBlank() } ?: fallback

class MainActivity : ComponentActivity() {
    /** Content shared from another app, awaiting a session pick (R4.3). */
    private var sharedDraft: String? by mutableStateOf(null)
    private var sharedUris: List<SharedFile> by mutableStateOf(emptyList())
    private var notificationSessionId: String? by mutableStateOf(null)

    private fun routeNotification(intent: Intent?) {
        notificationSessionId = intent?.getStringExtra(TurnService.EXTRA_STORED_ID)
    }

    private fun handleShared(intent: Intent?) {
        fun stashText(text: String?) {
            if (!text.isNullOrBlank()) sharedDraft = listOfNotNull(sharedDraft, text).joinToString("\n")
        }
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val stream: Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) sharedUris = sharedUris + SharedFile(stream.toString(), intent.type, null)
                stashText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
                if (stream != null || sharedDraft != null) {
                    (application as? HermesApp)?.captureLog?.add("share received (${intent.type})")
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
                sharedUris = sharedUris + streams.map { SharedFile(it.toString(), intent.type, null) }
                stashText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
                if (streams.isNotEmpty() || sharedDraft != null) {
                    (application as? HermesApp)?.captureLog?.add("share received ${streams.size} file(s) (${intent.type})")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShared(intent)
        routeNotification(intent)
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
                        var open by remember { mutableStateOf<ChatOpen?>(null) }
                        /** Stored id the user most recently asked to open; null after Back. Guards stale results. */
                        var openingId by remember { mutableStateOf<String?>(null) }
                        var sending by remember { mutableStateOf(false) }
                        var sendError by remember { mutableStateOf<String?>(null) }
                        var sentCount by remember { mutableStateOf(0) }
                        val liveRuntimes = remember { mutableStateMapOf<String, String>() } // storedId -> runtimeId
                        val connState by app.gateway.connState.collectAsState()
                        val lines by app.captureLog.lines.collectAsState()
                        val savedUser by app.tokenStore.savedUser.collectAsState(initial = null)
                        val hasSaved by app.tokenStore.tokens.collectAsState(initial = null)

                        val notificationPermission = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission(),
                        ) { granted ->
                            if (!granted) app.captureLog.add("notification permission denied")
                        }
                        LaunchedEffect(Unit) {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(
                                    this@MainActivity,
                                    android.Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }

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
                            fun stillWanted() = openingId == row.id
                            try {
                                val snap = app.sessionsApi.resumeStored(row.id)
                                liveRuntimes[snap.storedId()] = snap.session_id
                                checkContract(snap.info)
                                app.sessionCache.writeTranscript(snap.storedId(), snap.messages)
                                if (!stillWanted()) return
                                val draft = sharedDraft
                                val shared = sharedUris
                                sharedDraft = null
                                sharedUris = emptyList()
                                open = ChatOpen(
                                    row, titleOf(snap, row.title),
                                    app.acquireTurnSession(snap.storedId(), snap.session_id, snap),
                                    chatNotice(snap), draft ?: "", shared,
                                )
                            } catch (e: RpcException) {
                                if (e.code == 4130L && stillWanted()) {
                                    // Transcript too large for live resume: paged REST copy
                                    // over a lazily-bound runtime.
                                    app.captureLog.add("resume 4130 — falling back to paged history")
                                    try {
                                        val msgs = app.sessionsApi.messagesPage(row.id)
                                        app.sessionCache.writeTranscript(row.id, msgs)
                                        val snap = app.sessionsApi.resumeStored(row.id, lazy = true)
                                        liveRuntimes[snap.storedId()] = snap.session_id
                                        val session = app.acquireTurnSession(
                                            snap.storedId(), snap.session_id, snap,
                                        )
                                        session.setHistory(msgs)
                                        open = ChatOpen(
                                            row, titleOf(snap, row.title), session,
                                            "Transcript too large for live view — showing a paged copy.",
                                        )
                                    } catch (e2: Exception) {
                                        sessionsError = e2.userMessage("Couldn't open live session")
                                    }
                                } else if (stillWanted()) {
                                    sessionsError = e.userMessage("Couldn't open live session")
                                }
                            } catch (e: Exception) {
                                if (stillWanted()) sessionsError = e.userMessage("Couldn't open live session")
                            }
                        }

                        LaunchedEffect(authed, notificationSessionId, sessions) {
                            val storedId = notificationSessionId ?: return@LaunchedEffect
                            if (!authed) return@LaunchedEffect
                            val row = sessions.firstOrNull { it.id == storedId }
                                ?: SessionRow(id = storedId)
                            notificationSessionId = null
                            openSession(row)
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
                            val history by o.session.history.collectAsStateWithLifecycle()
                            val extras by o.session.extras.collectAsStateWithLifecycle()
                            val live by o.session.state.collectAsStateWithLifecycle()
                            val recovering by o.session.recovering.collectAsStateWithLifecycle()
                            val cards by o.session.cards.collectAsStateWithLifecycle()
                            ChatScreen(
                                title = o.title,
                                history = history,
                                extras = extras,
                                live = live,
                                recovering = recovering,
                                notice = o.notice,
                                sendError = sendError,
                                sending = sending,
                                sentCount = sentCount,
                                initialDraft = o.draft,
                                initialShared = o.shared,
                                cards = cards,
                                images = app.serverImages,
                                onBack = {
                                    openingId = null
                                    app.releaseTurnSession(o.session)
                                    open = null
                                    sendError = null
                                },
                                onSend = { text, attachments ->
                                    scope.launch {
                                        sending = true
                                        sendError = null
                                        try {
                                            o.session.send(text, attachments)
                                            sentCount++
                                        } catch (e: Exception) {
                                            val msg = e.userMessage("Couldn't send")
                                            app.captureLog.add("ERROR $msg")
                                            sendError = msg
                                        } finally {
                                            sending = false
                                        }
                                    }
                                },
                                onStop = {
                                    scope.launch {
                                        try {
                                            o.session.stop()
                                        } catch (e: Exception) {
                                            app.captureLog.add("ERROR stop: ${e.message}")
                                        }
                                    }
                                },
                                onConsumedShared = { open = o.copy(shared = emptyList()) },
                                onAnswerApproval = { card, choice ->
                                    scope.launch {
                                        try {
                                            o.session.answerApproval(card, choice)
                                        } catch (e: Exception) {
                                            sendError = e.userMessage("Couldn't answer")
                                        }
                                    }
                                },
                                onAnswerClarify = { card, answers ->
                                    scope.launch {
                                        try {
                                            o.session.answerClarify(card, answers)
                                        } catch (e: Exception) {
                                            sendError = e.userMessage("Couldn't answer")
                                        }
                                    }
                                },
                                onAnswerSecret = { card, value ->
                                    scope.launch {
                                        try {
                                            o.session.answerSecret(card, value)
                                        } catch (e: Exception) {
                                            sendError = e.userMessage("Couldn't answer")
                                        }
                                    }
                                },
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
                                            // create writes no DB row until first submit, so the new
                                            // session would not appear in the list: open it directly
                                            // from the create snapshot instead of refetching.
                                            val snap = app.sessionsApi.create(title.ifBlank { null })
                                            checkContract(snap.info)
                                            val stored = snap.storedId()
                                            liveRuntimes[stored] = snap.session_id
                                            openingId = stored
                                            val name = titleOf(snap, title)
                                            open = ChatOpen(
                                                SessionRow(id = stored, title = name), name,
                                                app.acquireTurnSession(stored, snap.session_id, snap),
                                                chatNotice(snap),
                                            )
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
                                        open?.let { app.releaseTurnSession(it.session) }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShared(intent)
        routeNotification(intent)
    }

    override fun onStart() {
        super.onStart()
        TurnService.stop(this)
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            val app = application as HermesApp
            app.backgroundTurnSession()?.let { session ->
                try {
                    TurnService.start(this, session.storedId)
                } catch (e: Exception) {
                    app.captureLog.add("foreground service start failed: ${e.message}")
                }
            }
        }
        super.onStop()
    }

    override fun onDestroy() {
        // Rotation recreates the activity: keep the app-scoped socket alive across it.
        val app = application as HermesApp
        if (isFinishing && !isChangingConfigurations && app.backgroundTurnSession() == null) {
            app.gateway.disconnect()
        }
        super.onDestroy()
    }
}
