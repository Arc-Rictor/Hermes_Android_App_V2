package com.arcrictor.hermes.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.arcrictor.hermes.turn.TurnActivity
import com.arcrictor.hermes.turn.TurnState

/**
 * The always-true activity line (R3.2) plus an elapsed timer while a turn runs.
 * `Needs input` wins whenever an approval/question card is open.
 */
@Composable
fun ActivityLine(state: TurnState, recovering: Boolean, nowMs: Long, modifier: Modifier = Modifier) {
    val text = when {
        recovering -> "Reconnecting…"
        state.openRequestCount > 0 -> "Needs your approval"
        else -> when (val a = state.activity) {
            TurnActivity.Idle -> "Ready"
            is TurnActivity.Working -> a.status.ifBlank { "Working" }
            TurnActivity.Thinking -> "Thinking"
            is TurnActivity.UsingTool -> "Running ${a.name}"
            TurnActivity.Responding -> "Responding"
            TurnActivity.NeedsInput -> "Needs your approval"
            is TurnActivity.Error -> "Error: ${a.message}"
            is TurnActivity.Stopped -> "Stopped"
        }
    }
    val elapsed = state.turnStartMs?.takeIf { state.turnAlive }?.let { start ->
        " · " + formatElapsed((nowMs - start).coerceAtLeast(0) / 1000)
    } ?: ""
    Text(
        text + elapsed,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

fun formatElapsed(totalSeconds: Long): String =
    "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
