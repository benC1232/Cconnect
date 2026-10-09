package com.cconnect.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cconnect.app.ClaudeSession
import com.cconnect.app.MainViewModel
import com.cconnect.app.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(state: UiState, vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    val uriHandler = LocalUriHandler.current
    var confirmClose by remember { mutableStateOf<ClaudeSession?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }
    LaunchedEffect(state.openUrl) {
        state.openUrl?.let {
            runCatching { uriHandler.openUri(it) }
            vm.onUrlOpened()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Claude sessions", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${state.user}@${state.host}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::refreshSessions) { Icon(Icons.Default.Refresh, "Refresh") }
                    IconButton(onClick = vm::disconnect) { Icon(Icons.AutoMirrored.Filled.Logout, "Disconnect") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::newSession,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New session") },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Box(Modifier.height(4.dp))

            if (!state.keyInstalled) {
                Banner(
                    icon = { Icon(Icons.Default.Key, null) },
                    text = "Couldn't install this phone's key automatically. Try again so next time no password is needed.",
                    action = "Install key",
                    onAction = vm::installKey,
                )
            }

            when {
                !state.sessionsLoaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.sessions.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No Claude sessions running.\nTap \"New session\" to start one in a folder.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.sessions, key = { it.name }) { session ->
                        SessionRow(
                            session,
                            onOpen = { vm.openSession(session) },
                            onClose = { confirmClose = session },
                        )
                    }
                }
            }
        }
    }

    confirmClose?.let { session ->
        AlertDialog(
            onDismissRequest = { confirmClose = null },
            title = { Text("Close session?") },
            text = { Text("This stops Claude in ${session.folder}.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.closeSession(session)
                    confirmClose = null
                }) { Text("Close") }
            },
            dismissButton = { TextButton(onClick = { confirmClose = null }) { Text("Cancel") } },
        )
    }

    state.trustPrompt?.let { session ->
        AlertDialog(
            onDismissRequest = vm::dismissTrustPrompt,
            title = { Text("Trust ${session.folder}?") },
            text = {
                Text(
                    "Claude is asking whether it can work with the files in ${session.dir}. " +
                        "Only trust folders with code you know."
                )
            },
            confirmButton = { TextButton(onClick = { vm.trustFolder(session) }) { Text("Trust") } },
            dismissButton = { TextButton(onClick = { vm.closeSession(session) }) { Text("Close session") } },
        )
    }

    state.sessionExit?.let { exit ->
        AlertDialog(
            onDismissRequest = vm::dismissSessionExit,
            title = { Text("Claude exited in ${exit.session.folder}") },
            text = {
                Text(
                    exit.output.ifBlank { "No output." },
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                )
            },
            confirmButton = { TextButton(onClick = { vm.closeSession(exit.session) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = vm::dismissSessionExit) { Text("OK") } },
        )
    }
}

private val ClaudeSession.folder: String
    get() = dir.trimEnd('/').substringAfterLast('/').ifEmpty { dir.ifEmpty { name } }

@Composable
private fun SessionRow(session: ClaudeSession, onOpen: () -> Unit, onClose: () -> Unit) {
    ListItem(
        headlineContent = { Text(session.folder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(
                when {
                    !session.running -> "Exited — tap to see why"
                    session.needsTrust -> "Waiting for you to trust this folder — tap"
                    else -> session.dir
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = when {
                    !session.running -> MaterialTheme.colorScheme.error
                    session.needsTrust -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        },
        leadingContent = {
            if (session.running) {
                Icon(Icons.Default.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
            } else {
                Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            }
        },
        trailingContent = {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close ${session.folder}") }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}

@Composable
internal fun Banner(icon: @Composable () -> Unit, text: String, action: String, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(12.dp))
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}
