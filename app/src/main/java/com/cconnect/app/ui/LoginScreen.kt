package com.cconnect.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cconnect.app.MainViewModel
import com.cconnect.app.RecentLogin
import com.cconnect.app.UiState

@Composable
fun LoginScreen(state: UiState, vm: MainViewModel) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var showRecent by remember { mutableStateOf(false) }
    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Cconnect", style = MaterialTheme.typography.headlineMedium)
            Text(
                "SSH into a machine and start Claude Code remote control.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))

            Box(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = state.host,
                    onValueChange = vm::onHostChange,
                    label = { Text("IP address") },
                    placeholder = { Text("192.168.1.10 or host:port") },
                    singleLine = true,
                    trailingIcon = if (state.recentLogins.isEmpty()) null else {
                        {
                            IconButton(onClick = { showRecent = !showRecent }) {
                                Icon(
                                    if (showRecent) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                    contentDescription = "Recent logins",
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                RecentLoginsMenu(
                    expanded = showRecent && state.recentLogins.isNotEmpty(),
                    logins = state.recentLogins,
                    onDismiss = { showRecent = false },
                    onSelect = {
                        vm.selectRecentLogin(it)
                        showRecent = false
                    },
                    onRemove = vm::removeRecentLogin,
                )
            }
            OutlinedTextField(
                value = state.user,
                onValueChange = vm::onUserChange,
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = vm::onPasswordChange,
                label = { Text("Password (optional)") },
                supportingText = { Text("Leave empty to log in with this phone's SSH key") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (showPassword) "Hide password" else "Show password",
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (!state.busy) vm.connect() }),
                modifier = Modifier.fillMaxWidth(),
            )

            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            if (state.hostKeyMismatch) {
                TextButton(onClick = vm::forgetHostKey) { Text("I trust this host — forget the old key") }
            }

            Button(
                onClick = vm::connect,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (state.busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text("Connect")
                }
            }

            Spacer(Modifier.height(8.dp))
            PublicKeyCard(state.publicKey)
        }
    }
}

@Composable
private fun RecentLoginsMenu(
    expanded: Boolean,
    logins: List<RecentLogin>,
    onDismiss: () -> Unit,
    onSelect: (RecentLogin) -> Unit,
    onRemove: (RecentLogin) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        logins.forEach { r ->
            DropdownMenuItem(
                text = { Text(r.label, fontFamily = FontFamily.Monospace) },
                leadingIcon = { Icon(Icons.Default.History, null) },
                trailingIcon = {
                    IconButton(onClick = { onRemove(r) }) {
                        Icon(Icons.Default.Close, contentDescription = "Remove ${r.label}")
                    }
                },
                onClick = { onSelect(r) },
            )
        }
    }
}

@Composable
private fun PublicKeyCard(publicKey: String?) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("This phone's SSH key", style = MaterialTheme.typography.titleMedium)
            Text(
                "For password-less login, this key must be in ~/.ssh/authorized_keys on the server. " +
                    "Log in once with your password and it is installed automatically, or copy it over yourself.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (publicKey == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text("  Generating key…", style = MaterialTheme.typography.bodySmall)
                }
            } else {
                SelectionContainer {
                    Text(
                        publicKey,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(publicKey)) }) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                        Text("  Copy")
                    }
                    OutlinedButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, publicKey)
                        }
                        context.startActivity(Intent.createChooser(send, "Share public key"))
                    }) {
                        Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                        Text("  Share")
                    }
                }
            }
        }
    }
}
