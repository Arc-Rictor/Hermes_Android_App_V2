package com.arcrictor.hermes.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arcrictor.hermes.gateway.UiMessage
import com.arcrictor.hermes.media.ServerImage
import com.arcrictor.hermes.media.ServerImages

/**
 * Transcript rows shared by the history view and the live chat (R2.2, R3.4, R4.3):
 * user bubbles, unbubbled Hermes replies, tool rows, `@image:` thumbnails, file chips.
 */
fun LazyListScope.messageRows(messages: List<UiMessage>, images: ServerImages) {
    items(messages) { msg -> MessageRow(msg, images) }
}

@Composable
private fun MessageRow(msg: UiMessage, images: ServerImages) {
    when (msg) {
        is UiMessage.User -> Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                msg.label?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
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
            msg.usage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            msg.reasoning?.let { reasoning -> ThinkingPanel(reasoning) }
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
                    Text(msg.preview.take(300), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
