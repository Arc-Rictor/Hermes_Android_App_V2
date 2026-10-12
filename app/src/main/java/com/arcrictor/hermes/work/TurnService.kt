package com.arcrictor.hermes.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arcrictor.hermes.HermesApp
import com.arcrictor.hermes.MainActivity
import com.arcrictor.hermes.R
import com.arcrictor.hermes.turn.TurnActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Keeps the app process/socket alive while a background turn is running (SPEC R6.1). */
class TurnService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitor: Job? = null
    private var storedId: String? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_STORED_ID)
        if (id.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        storedId = id
        startForeground(
            ONGOING_ID,
            notification("Hermes is working", "Keeping the session connected", id, ongoing = true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        monitor?.cancel()
        monitor = scope.launch {
            val session = (application as HermesApp).turnSession(id)
            if (session == null) {
                stopServiceNow()
                return@launch
            }
            var previouslyAlive = session.state.value.turnAlive
            var idleSince = System.currentTimeMillis()
            while (isActive) {
                val state = session.state.value
                val now = System.currentTimeMillis()
                if (state.turnAlive) {
                    idleSince = now
                    notificationManager().notify(
                        ONGOING_ID,
                        notification("Hermes is working", activityText(state.activity), id, ongoing = true),
                    )
                } else if (previouslyAlive) {
                    notificationManager().notify(
                        REPLY_ID,
                        notification("Hermes finished", "Your reply is ready", id, ongoing = false),
                    )
                    stopServiceNow()
                    return@launch
                } else if (now - idleSince >= IDLE_STOP_MS) {
                    stopServiceNow()
                    return@launch
                }
                previouslyAlive = state.turnAlive
                delay(POLL_MS)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitor?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopServiceNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun notification(
        title: String,
        text: String,
        id: String,
        ongoing: Boolean,
    ) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(openSessionIntent(id))
        .setOnlyAlertOnce(true)
        .setOngoing(ongoing)
        .setAutoCancel(!ongoing)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun openSessionIntent(id: String): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_STORED_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager().createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Hermes turns", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun notificationManager() = getSystemService(NotificationManager::class.java)

    private fun activityText(activity: TurnActivity): String = when (activity) {
        TurnActivity.Idle -> "Waiting"
        is TurnActivity.Working -> activity.status.ifBlank { "Working" }
        TurnActivity.Thinking -> "Thinking"
        is TurnActivity.UsingTool -> "Running ${activity.name}"
        TurnActivity.Responding -> "Writing a reply"
        TurnActivity.NeedsInput -> "Waiting for your input"
        is TurnActivity.Error -> "Turn failed"
        is TurnActivity.Stopped -> "Turn stopped"
    }

    companion object {
        const val EXTRA_STORED_ID = "com.arcrictor.hermes.STORED_SESSION_ID"
        private const val CHANNEL_ID = "hermes_turns"
        private const val ONGOING_ID = 4101
        private const val REPLY_ID = 4102
        private const val POLL_MS = 1_000L
        private const val IDLE_STOP_MS = 60_000L

        fun start(context: Context, storedId: String) {
            val intent = Intent(context, TurnService::class.java).putExtra(EXTRA_STORED_ID, storedId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TurnService::class.java))
        }
    }
}
