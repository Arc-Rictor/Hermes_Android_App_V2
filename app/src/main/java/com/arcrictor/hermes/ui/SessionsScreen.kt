package com.arcrictor.hermes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arcrictor.hermes.gateway.SessionRow

@Composable
fun SessionsScreen(
    sessions: List<SessionRow>,
    loading: Boolean,
    error: String?,
    contractWarning: String?,
    onRefresh: () -> Unit,
    onOpen: (SessionRow) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (SessionRow, String) -> Unit,
    onDelete: (SessionRow) -> Unit,
    onDiagnostics: () -> Unit,
) {
    var newTitle by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<SessionRow?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SessionRow?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Sessions", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRefresh, enabled = !loading) { Text("Refresh") }
                OutlinedButton(onClick = onDiagnostics) { Text("Diagnostics") }
            }
        }
        contractWarning?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) { Text(it, modifier = Modifier.padding(12.dp)) }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newTitle, onValueChange = { newTitle = it },
                label = { Text("New session title (optional)") }, singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onCreate(newTitle); newTitle = "" }, enabled = !loading) {
                Text("New")
            }
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(bottom = 8.dp))
        }
        if (loading && sessions.isEmpty()) {
            CircularProgressIndicator()
        }
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sessions, key = { it.id }) { row ->
                Card(modifier = Modifier.fillMaxWidth().clickable { onOpen(row) }) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            row.title.ifBlank { "(untitled)" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (row.preview.isNotBlank()) {
                            Text(row.preview.take(160), style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                renameTarget = row
                                renameText = row.title
                            }) { Text("Rename") }
                            TextButton(onClick = { deleteTarget = row }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename session") },
            text = {
                OutlinedTextField(
                    value = renameText, onValueChange = { renameText = it },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(target, renameText)
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete session?") },
            text = { Text("\"${target.title.ifBlank { "(untitled)" }}\" and its transcript will be deleted from the server.") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    deleteTarget = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}
