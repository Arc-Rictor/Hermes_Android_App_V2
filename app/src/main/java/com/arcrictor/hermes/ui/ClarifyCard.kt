package com.arcrictor.hermes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arcrictor.hermes.gateway.ClarifyCard

/**
 * Clarify card (R5.2): each question with its options or free text;
 * one submit answers all. Multi-select joins with "," (best effort —
 * the wire encoding is unconfirmed; see PROTOCOL.md §8).
 */
@Composable
fun ClarifyCard(card: ClarifyCard, onSubmit: (Map<String, String?>) -> Unit) {
    var single by remember(card.id) { mutableStateOf(mapOf<String, String>()) }
    var multi by remember(card.id) { mutableStateOf(mapOf<String, Set<String>>()) }
    var free by remember(card.id) { mutableStateOf(mapOf<String, String>()) }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card.questions.forEach { q ->
                Text(q.question, style = MaterialTheme.typography.titleSmall)
                when {
                    q.choices == null -> OutlinedTextField(
                        value = free[q.qid] ?: "",
                        onValueChange = { free = free + (q.qid to it) },
                        placeholder = { Text("Type an answer") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    q.multiSelect -> q.choices.forEach { c ->
                        val selected = multi[q.qid].orEmpty()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = c in selected,
                                onCheckedChange = { on ->
                                    multi = multi + (q.qid to if (on) selected + c else selected - c)
                                },
                            )
                            Text(c, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    else -> q.choices.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = single[q.qid] == c,
                                onClick = { single = single + (q.qid to c) },
                            )
                            Text(c, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
            if (card.expired) {
                Text(
                    "Expired" + (card.expiredReason?.let { ": $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val answers = card.questions.associate { q ->
                    q.qid to (single[q.qid]
                        ?: multi[q.qid]?.takeIf { it.isNotEmpty() }?.joinToString(",")
                        ?: free[q.qid]?.takeIf { it.isNotBlank() })
                }
                Button(
                    onClick = { onSubmit(answers) },
                    enabled = answers.values.any { it != null },
                ) { Text("Submit answers") }
            }
        }
    }
}
