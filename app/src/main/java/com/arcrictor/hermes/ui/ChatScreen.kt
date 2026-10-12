package com.arcrictor.hermes.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.arcrictor.hermes.gateway.AttachKind
import com.arcrictor.hermes.gateway.OutgoingAttachment
import com.arcrictor.hermes.gateway.UiMessage
import com.arcrictor.hermes.media.Attachments
import com.arcrictor.hermes.media.CameraSheet
import com.arcrictor.hermes.media.ImportResult
import com.arcrictor.hermes.media.ServerImages
import com.arcrictor.hermes.media.StagedFile
import com.arcrictor.hermes.turn.TurnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    initialDraft: String = "",
    initialShared: List<SharedFile> = emptyList(),
    images: ServerImages,
    onBack: () -> Unit,
    onSend: (String, List<OutgoingAttachment>) -> Unit,
    onStop: () -> Unit,
    onConsumedShared: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable(initialDraft) { mutableStateOf(initialDraft) }
    var pending by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    var importError by remember { mutableStateOf<String?>(null) }
    var showCamera by remember { mutableStateOf(false) }
    LaunchedEffect(sentCount) {
        if (sentCount > 0) {
            draft = ""
            pending = emptyList()
            importError = null
        }
    }

    fun stageImported(results: List<ImportResult>) {
        pending = pending + results.filterIsInstance<ImportResult.Ready>().map { r ->
            val s = r.staged
            PendingAttachment(
                kind = when (s) {
                    is StagedFile.Image -> AttachKind.IMAGE
                    is StagedFile.Pdf -> AttachKind.PDF
                    is StagedFile.Generic -> AttachKind.FILE
                },
                name = s.name,
                file = s.file,
                error = null,
            )
        }
        importError = results.filterIsInstance<ImportResult.Refused>().firstOrNull()?.reason
    }

    fun stageUris(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val results = withContext(Dispatchers.IO) { uris.map { Attachments.import(context, it) } }
            stageImported(results)
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(5),
    ) { uris -> stageUris(uris) }
    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> stageUris(uris) }

    LaunchedEffect(initialShared) {
        if (initialShared.isEmpty()) return@LaunchedEffect
        val results = withContext(Dispatchers.IO) {
            initialShared.map { shared ->
                runCatching { Attachments.import(context, shared.uri.toUri()) }
                    .getOrElse { ImportResult.Refused("Couldn't read that file") }
            }
        }
        stageImported(results)
        if (initialShared.isNotEmpty()) onConsumedShared()
    }

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
        importError?.let {
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
        PendingRow(pending) { pending = pending - it }
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AttachButton(
                onGallery = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                onCamera = { showCamera = true },
                onFile = { fileLauncher.launch(arrayOf("*/*")) },
            )
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
            val ready = pending.filter { it.file != null }
            Button(
                onClick = {
                    if (alive) onStop()
                    else onSend(draft, ready.map { OutgoingAttachment(it.kind, it.file!!, it.name) })
                },
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                enabled = alive || ((draft.isNotBlank() || ready.isNotEmpty()) && !sending),
                modifier = Modifier.size(48.dp)
                    .semantics { contentDescription = if (alive) "Stop" else "Send" },
            ) {
                Text(if (alive) "■" else "↑")
            }
        }
        if (showCamera) {
            CameraSheet(
                onCaptured = { file ->
                    showCamera = false
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            Attachments.importCapture(context, file)
                        }
                        stageImported(listOf(result))
                    }
                },
                onDismiss = { showCamera = false },
            )
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
