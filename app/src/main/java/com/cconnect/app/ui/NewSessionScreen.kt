package com.cconnect.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cconnect.app.MainViewModel
import com.cconnect.app.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen(state: UiState, vm: MainViewModel) {
    val snackbar = remember { SnackbarHostState() }
    var editingPath by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.dismissMessage()
        }
    }
    BackHandler { vm.cancelNewSession() }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("New session", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Choose a folder for Claude to work in",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = vm::cancelNewSession) { Icon(Icons.Default.Close, "Cancel") }
                },
                actions = {
                    IconButton(onClick = vm::toggleHidden) {
                        Icon(
                            if (state.showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (state.showHidden) "Hide hidden folders" else "Show hidden folders",
                        )
                    }
                },
            )
        },
        bottomBar = { LaunchBar(state, vm) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Box(Modifier.height(4.dp))

            // Path bar: tap to type a path directly
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::goUp, enabled = state.cwd != "/") {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Parent folder")
                }
                IconButton(onClick = vm::goHome) { Icon(Icons.Default.Home, "Home folder") }
                val path = editingPath
                if (path != null) {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { editingPath = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            vm.goToPath(path)
                            editingPath = null
                        }),
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        state.cwd,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { editingPath = state.cwd }
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 12.dp),
                    )
                }
            }

            HorizontalDivider()

            LazyColumn(Modifier.fillMaxSize()) {
                if (state.dirs.isEmpty() && !state.busy) {
                    item {
                        Text(
                            "No subfolders",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(24.dp),
                        )
                    }
                }
                items(state.dirs, key = { it.path }) { dir ->
                    ListItem(
                        headlineContent = { Text(dir.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = { Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                        modifier = Modifier.clickable { vm.openDir(dir.path) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LaunchBar(state: UiState, vm: MainViewModel) {
    Surface(tonalElevation = 3.dp) {
        Button(
            onClick = vm::startSession,
            enabled = !state.busy && state.cwd.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp).height(52.dp),
        ) {
            if (state.busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Default.PlayArrow, null)
                val folder = state.cwd.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }
                Text("  Start Claude in $folder", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
