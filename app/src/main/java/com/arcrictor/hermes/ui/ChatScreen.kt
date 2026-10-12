package com.arcrictor.hermes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.arcrictor.hermes.gateway.UiMessage
import com.arcrictor.hermes.media.ServerImages
import com.arcrictor.hermes.turn.TurnState
import kotlinx.coroutines.delay

/**
 * Live chat (Phase 3): top app bar with session title + true activity subtitle,
 * transcript (history + frozen turns), the running turn, and a pill composer
 * whose send button becomes Stop while Hermes works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    title: String,
    history: List<UiMessage>,
    extras: List<UiMessage>,
    live: TurnState,
    recovering: Boolean,
    notice: String?,
    sendError: String?,
    sending: Boolean,
    sentCount: Int,
    images: ServerImages,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(sentCount) { if (sentCount > 0) draft = "" }

    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(live.turnAlive) {
        while (live.turnAlive) {
            delay(1_000)
            nowMs = System.currentTimeMillis()
        }
    }

    val showLive = live.turnAlive || live.reply.isNotBlank() || live.thinking.isNotBlank() ||
        live.notes.isNotEmpty() || live.tools.isNotEmpty()
    val listState = rememberLazyListState()
    val scrollKey = history.size to (extras.size + live.reply.length + live.notes.size + live.tools.size)
    LaunchedEffect(scrollKey) {
        val total = history.size + extras.size + if (showLive) 1 else 0
        if (total == 0) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        if (lastVisible == null || lastVisible >= total - 2) listState.scrollToItem(total - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        title.ifBlank { "(untitled)" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                    )
                    ActivityLine(live, recovering, nowMs)
                }
            },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
        )
        notice?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            ) { Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        }
        live.gapNotice?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            ) { Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        }
        sendError?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            ) {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            messageRows(history + extras, images)
            if (showLive) {
                item {
                    LiveTurn(live, nowMs)
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Message Hermes") },
                shape = CircleShape,
                singleLine = false,
                maxLines = 5,
                modifier = Modifier.weight(1f),
            )
            val alive = live.turnAlive || recovering
            Button(
                onClick = { if (alive) onStop() else onSend(draft) },
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                enabled = alive || (draft.isNotBlank() && !sending),
                modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = if (alive) "Stop" else "Send" },
            ) {
                Text(if (alive) "■" else "↑")
            }
        }
    }
}

@Composable
private fun LiveTurn(live: TurnState, nowMs: Long) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (live.reply.isNotBlank()) Text(live.reply, modifier = Modifier.padding(4.dp))
        live.notes.forEach { note ->
            Text(note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(4.dp))
        }
        if (live.thinking.isNotBlank()) ThinkingPanel(live.thinking)
        ToolTimeline(live.tools, nowMs)
        live.usage?.let {
            Text(
                it.label(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}
