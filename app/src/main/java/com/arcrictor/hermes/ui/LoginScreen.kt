package com.arcrictor.hermes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

data class LoginUiState(
    val working: Boolean = false,
    val steps: List<String> = emptyList(),
    val error: String? = null,
)

@Composable
fun LoginScreen(
    baseUrl: String,
    username: String,
    password: String,
    savedUser: String?,
    hasSavedSession: Boolean,
    state: LoginUiState,
    onBaseUrl: (String) -> Unit,
    onUsername: (String) -> Unit,
    onPassword: (String) -> Unit,
    onLogin: () -> Unit,
    onReconnect: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Hermes", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Sign in to your Hermes gateway (Tailscale).",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = baseUrl, onValueChange = onBaseUrl, label = { Text("Server URL") },
            singleLine = true, enabled = !state.working,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = username, onValueChange = onUsername, label = { Text("Username") },
            singleLine = true, enabled = !state.working,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password, onValueChange = onPassword, label = { Text("Password") },
            singleLine = true, enabled = !state.working,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = onLogin, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
            if (state.working) CircularProgressIndicator(
                modifier = Modifier.padding(end = 8.dp),
                strokeWidth = 2.dp,
            )
            Text(if (state.working) "Signing in…" else "Sign in")
        }
        if (hasSavedSession && savedUser != null) {
            Button(onClick = onReconnect, enabled = !state.working, modifier = Modifier.fillMaxWidth()) {
                Text("Reconnect as $savedUser")
            }
        }
        state.steps.forEach { step ->
            Text("✓ $step", style = MaterialTheme.typography.bodySmall)
        }
        state.error?.let { error ->
            Spacer(Modifier.height(4.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(
                    error,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}
