package com.arcrictor.hermes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arcrictor.hermes.turn.ToolEntry

/**
 * Tool timeline (R3.4): start (name + argument preview) → outcome
 * (duration, result summary or error). Collapsed to a "Used N tools" row.
 */
@Composable
fun ToolTimeline(tools: List<ToolEntry>, nowMs: Long, modifier: Modifier = Modifier) {
    if (tools.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    val done = tools.count { !it.open }
    Column(modifier = modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }) {
            Text(
                if (open) "Hide tools"
                else if (done < tools.size) "Using ${tools.size} tool${if (tools.size == 1) "" else "s"}…"
                else "Used ${tools.size} tool${if (tools.size == 1) "" else "s"}",
            )
        }
        if (open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tools.forEach { tool -> ToolRow(tool, nowMs) }
            }
        }
    }
}

@Composable
private fun ToolRow(tool: ToolEntry, nowMs: Long) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (tool.failed) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    (if (tool.failed) "failed: " else "") + tool.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (tool.failed) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    tool.displayDuration(nowMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tool.failed) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val detail = (if (tool.open) tool.preview else tool.summary.ifBlank { tool.preview })
                .take(300)
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (tool.failed) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (tool.risky) {
                Text(
                    "flagged risky — review before continuing",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (tool.failed) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
