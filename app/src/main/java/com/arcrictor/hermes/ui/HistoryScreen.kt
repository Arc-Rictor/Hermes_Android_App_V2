package com.arcrictor.hermes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arcrictor.hermes.gateway.UiMessage
import com.arcrictor.hermes.media.ServerImage
import com.arcrictor.hermes.media.ServerImages

@Composable
fun HistoryScreen(
    title: String,
    messages: List<UiMessage>,
    loading: Boolean,
    notice: String?,
    images: ServerImages,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                title.ifBlank { "(untitled)" },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onBack) { Text("Back") }
                OutlinedButton(onClick = onRefresh, enabled = !loading) { Text("Reload") }
            }
        }
        notice?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) { Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        }
        if (loading && messages.isEmpty()) {
            CircularProgressIndicator()
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { msg ->
                when (msg) {
                    is UiMessage.User -> Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            if (msg.text.isNotBlank()) Text(msg.text)
                            msg.images.forEach { ref ->
                                ServerImage(ref, images, Modifier.fillMaxWidth().padding(top = 8.dp))
                            }
                            msg.files.forEach { file ->
                                AssistChip(
                                    onClick = {},
                                    label = { Text("📎 ${file.ref.take(60)}") },
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                    is UiMessage.Assistant -> Column(modifier = Modifier.fillMaxWidth()) {
                        if (msg.text.isNotBlank()) Text(msg.text, modifier = Modifier.padding(4.dp))
                        msg.reasoning?.let { reasoning ->
                            var open by remember { mutableStateOf(false) }
                            TextButton(onClick = { open = !open }) {
                                Text(if (open) "Hide thinking" else "Show thinking")
                            }
                            if (open) {
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    ),
                                ) {
                                    Text(
                                        reasoning,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(12.dp),
                                    )
                                }
                            }
                        }
                    }
                    is UiMessage.Tool -> Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("🔧 ${msg.name}", style = MaterialTheme.typography.labelLarge)
                            if (msg.preview.isNotBlank()) {
                                Text(
                                    msg.preview.take(300),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
