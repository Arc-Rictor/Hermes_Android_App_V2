package com.arcrictor.hermes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
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
import com.arcrictor.hermes.gateway.AttachKind
import java.io.File

/** File shared into the app (intent) or picked, awaiting import. */
data class SharedFile(val uri: String, val mime: String?, val name: String?)

/** Imported file staged under the composer. `file` is null when import failed. */
data class PendingAttachment(
    val kind: AttachKind,
    val name: String,
    val file: File?,
    val error: String?,
)

/**
 * The attach affordance left of the composer (paperclip → source options)
 * plus the staged-attachment chips above it.
 */
@Composable
fun AttachButton(onGallery: () -> Unit, onCamera: () -> Unit, onFile: () -> Unit) {
    var showOptions by remember { mutableStateOf(false) }
    Column {
        if (showOptions) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showOptions = false; onGallery() }) { Text("Gallery") }
                OutlinedButton(onClick = { showOptions = false; onCamera() }) { Text("Camera") }
                OutlinedButton(onClick = { showOptions = false; onFile() }) { Text("File") }
            }
        }
        TextButton(onClick = { showOptions = !showOptions }) { Text("📎") }
    }
}

@Composable
fun PendingRow(pending: List<PendingAttachment>, onRemove: (PendingAttachment) -> Unit) {
    if (pending.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        pending.forEach { item ->
            Row {
                AssistChip(
                    onClick = { onRemove(item) },
                    label = {
                        Text(
                            (if (item.error != null) "⚠ " else "📎 ") + item.name +
                                (if (item.error != null) " — ${item.error}; tap to drop" else " — tap to drop"),
                        )
                    },
                )
            }
        }
        if (pending.any { it.error != null }) {
            Text(
                "Unreadable files stay out of the send.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
