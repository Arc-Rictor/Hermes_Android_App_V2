package com.arcrictor.hermes

import android.app.Application
import com.arcrictor.hermes.data.TokenStore
import com.arcrictor.hermes.diagnostics.CaptureLog
import com.arcrictor.hermes.gateway.AuthApi
import com.arcrictor.hermes.gateway.GatewayClient
import com.arcrictor.hermes.gateway.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val tokenManager by lazy { TokenManager(tokenStore, { AuthApi(it, http) }) }
    val gateway: GatewayClient by lazy {
        GatewayClient(http, appScope) { captureLog.add(it) }
    }
}
