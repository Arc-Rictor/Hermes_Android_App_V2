package com.arcrictor.hermes

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import com.arcrictor.hermes.gateway.AuthApi
import com.arcrictor.hermes.gateway.LoginError
import com.arcrictor.hermes.ui.DiagnosticsScreen
import com.arcrictor.hermes.ui.LoginScreen
import com.arcrictor.hermes.ui.LoginUiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as HermesApp
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val scope = rememberCoroutineScope()
                var authed by rememberSaveable { mutableStateOf(false) }
                var baseUrl by rememberSaveable { mutableStateOf("http://100.123.89.114:9119") }
                var username by rememberSaveable { mutableStateOf("") }
                var password by rememberSaveable { mutableStateOf("") }
                var loginState by remember { mutableStateOf(LoginUiState()) }
                var readySummary by remember { mutableStateOf<String?>(null) }
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
                        } catch (e: Exception) {
                            val msg = (e as? LoginError)?.message ?: "Failed: ${e.message}"
                            app.captureLog.add("ERROR $msg")
                            loginState = LoginUiState(error = msg)
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
                } else {
                    DiagnosticsScreen(
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
                                authed = false
                            }
                        },
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
