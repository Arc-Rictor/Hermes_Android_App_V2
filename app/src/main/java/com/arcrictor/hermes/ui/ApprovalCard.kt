package com.arcrictor.hermes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arcrictor.hermes.gateway.ApprovalCard

/**
 * Approval card (R5.1): command + description and only the offered choices.
 * No answer on timeout — the card visibly expires via `request.cancel`.
 */
@Composable
fun ApprovalCard(card: ApprovalCard, onAnswer: (String) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (card.command.isNotBlank()) {
                Text(card.command, style = MaterialTheme.typography.titleMedium)
            }
            if (card.description.isNotBlank()) {
                Text(card.description, style = MaterialTheme.typography.bodyMedium)
            }
            card.toolName?.let {
                Text(
                    "via $it",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.expired) {
                Text(
                    "Expired" + (card.expiredReason?.let { ": $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    card.choices.forEach { choice ->
                        OutlinedButton(onClick = { onAnswer(choice) }) {
                            Text(choice.replaceFirstChar { it.uppercase() })
                        }
                    }
                }
            }
        }
    }
}
