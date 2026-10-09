package com.cconnect.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cconnect.app.ssh.DirEntry
import com.cconnect.app.ssh.HostKeyMismatchException
import com.cconnect.app.ssh.HostKeyStore
import com.cconnect.app.ssh.KeyManager
import com.cconnect.app.ssh.SshManager
import com.cconnect.app.ssh.SshManager.Companion.shellQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class Screen { Login, Sessions, NewSession }

data class RecentLogin(val host: String, val user: String) {
    val label: String get() = "$user@$host"
}

/** A Claude session running in tmux on the server. [running] is false once claude has exited. */
data class ClaudeSession(val name: String, val dir: String, val running: Boolean, val needsTrust: Boolean = false)

/** Why a session exited: its last lines of output. */
data class SessionExit(val session: ClaudeSession, val output: String)

data class UiState(
    val screen: Screen = Screen.Login,
    val host: String = "",
    val user: String = "",
    val password: String = "",
    val recentLogins: List<RecentLogin> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val hostKeyMismatch: Boolean = false,
    val publicKey: String? = null,
    val keyInstalled: Boolean = true,
    val home: String = "",
    val cwd: String = "",
    val dirs: List<DirEntry> = emptyList(),
    val showHidden: Boolean = false,
    val sessions: List<ClaudeSession> = emptyList(),
    val sessionsLoaded: Boolean = false,
    val sessionExit: SessionExit? = null,
    val trustPrompt: ClaudeSession? = null,
    val openUrl: String? = null,
)

const val CLAUDE_COMMAND = "claude --remote-control"
private const val SESSION_PREFIX = "claude-"
private const val MAX_RECENT_LOGINS = 5
private const val POLL_INTERVAL_MS = 5_000L
private val URL = Regex("""https://\S+""")
/** Claude's "do you trust this folder?" prompt, shown on the first run in a folder. */
private val TRUST_PROMPT = Regex("""(?i)Yes, I trust this folder|Do you trust the files in this folder""")
private val YES_OPTION = Regex("""^\s*(\u276F\s*)?Yes\b""")
private const val MENU_CURSOR = "\u276F" // ❯, marks the selected option in Claude's menus
private const val PANE_SEPARATOR = "@@cconnect-pane@@"

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val keys = KeyManager(app)
    private val hostKeys = HostKeyStore(app)
    private val ssh = SshManager(keys, hostKeys)

    private val _state = MutableStateFlow(
        UiState(
            host = prefs.getString("host", "") ?: "",
            user = prefs.getString("user", "") ?: "",
            recentLogins = loadRecentLogins(),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var pollJob: Job? = null
    /** Sessions whose trust prompt was already shown, so it isn't re-shown on every poll. */
    private val trustPrompted = mutableSetOf<String>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val pk = runCatching { keys.publicKeyOpenSsh() }.getOrNull()
            _state.update { it.copy(publicKey = pk) }
        }
    }

    // ---- Login ----

    fun onHostChange(v: String) = _state.update { it.copy(host = v, error = null, hostKeyMismatch = false) }
    fun onUserChange(v: String) = _state.update { it.copy(user = v, error = null) }
    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, error = null) }
    fun selectRecentLogin(r: RecentLogin) =
        _state.update { it.copy(host = r.host, user = r.user, error = null, hostKeyMismatch = false) }

    fun removeRecentLogin(r: RecentLogin) {
        val list = _state.value.recentLogins - r
        saveRecentLogins(list)
        _state.update { it.copy(recentLogins = list) }
    }

    /** Recent successful logins, most recent first, stored one "user\thost" per line. */
    private fun loadRecentLogins(): List<RecentLogin> =
        (prefs.getString("recentLogins", "") ?: "").lines().mapNotNull { line ->
            val (user, host) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
            RecentLogin(host, user)
        }

    private fun saveRecentLogins(list: List<RecentLogin>) =
        prefs.edit().putString("recentLogins", list.joinToString("\n") { "${it.user}\t${it.host}" }).apply()

    fun dismissMessage() = _state.update { it.copy(message = null) }

    fun connect() {
        val s = _state.value
        val (host, port) = parseHost(s.host.trim()) ?: run {
            _state.update { it.copy(error = "Enter a valid IP or host (optionally host:port)") }
            return
        }
        if (s.user.isBlank()) {
            _state.update { it.copy(error = "Enter a username") }
            return
        }
        io {
            _state.update { it.copy(busy = true, error = null, hostKeyMismatch = false) }
            try {
                val result = ssh.connect(host, port, s.user.trim(), s.password.ifEmpty { null })
                prefs.edit().putString("host", s.host.trim()).putString("user", s.user.trim()).apply()
                val recent = RecentLogin(s.host.trim(), s.user.trim())
                val recentLogins = (listOf(recent) + _state.value.recentLogins.filter { it != recent })
                    .take(MAX_RECENT_LOGINS)
                saveRecentLogins(recentLogins)
                // Logged in with a password: install this phone's key (like ssh-copy-id) so it isn't needed next time.
                val keyError = if (result.usedKey) null else runCatching { ssh.installPublicKey() }.exceptionOrNull()
                val home = ssh.homeDir()
                _state.update {
                    it.copy(
                        screen = Screen.Sessions,
                        password = "",
                        recentLogins = recentLogins,
                        keyInstalled = result.usedKey || keyError == null,
                        home = home,
                        message = listOfNotNull(
                            result.newHostFingerprint?.let { fp -> "Trusted new host key $fp" },
                            when {
                                result.usedKey -> null
                                keyError == null -> "Key installed. You can now connect without a password."
                                else -> "Couldn't install key: ${describe(keyError)}"
                            },
                        ).joinToString("\n").ifEmpty { null },
                    )
                }
                startPolling()
            } catch (e: HostKeyMismatchException) {
                _state.update { it.copy(error = e.message, hostKeyMismatch = true) }
            } catch (e: Exception) {
                _state.update { it.copy(error = describe(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    fun forgetHostKey() {
        parseHost(_state.value.host.trim())?.let { (h, p) -> hostKeys.forget(h, p) }
        _state.update { it.copy(hostKeyMismatch = false, error = "Host key forgotten. Connect again to trust the new key.") }
    }

    fun disconnect() {
        stopPolling()
        io { ssh.disconnect() }
        _state.update {
            it.copy(screen = Screen.Login, dirs = emptyList(), sessions = emptyList(), sessionsLoaded = false)
        }
    }

    fun installKey() = io {
        _state.update { it.copy(busy = true) }
        try {
            ssh.installPublicKey()
            _state.update { it.copy(keyInstalled = true, message = "Key installed. You can now connect without a password.") }
        } catch (e: Exception) {
            _state.update { it.copy(message = describe(e)) }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    // ---- Sessions ----

    fun refreshSessions() = io { loadSessions() }

    private fun startPolling() {
        stopPolling()
        pollJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                loadSessions()
                if (!ssh.isConnected) {
                    disconnectLost()
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun loadSessions() {
        val r = runCatching {
            ssh.exec("tmux list-panes -a -F '#{session_name}\t#{pane_dead}\t#{@cconnect_dir}\t#{pane_current_path}' 2>/dev/null")
        }.getOrNull() ?: return
        val sessions = r.stdout.lines()
            .map { it.split('\t') }
            .filter { it.size == 4 && it[0].startsWith(SESSION_PREFIX) }
            .distinctBy { it[0] }
            .map { (name, dead, dir, cwd) -> ClaudeSession(name, dir.ifEmpty { cwd }, running = dead != "1") }
            .let(::markTrustPrompts)
        val newPrompt = sessions.firstOrNull { it.needsTrust && trustPrompted.add(it.name) }
        _state.update { s ->
            s.copy(
                sessions = sessions,
                sessionsLoaded = true,
                trustPrompt = newPrompt ?: s.trustPrompt?.takeIf { p -> sessions.any { it.name == p.name && it.needsTrust } },
            )
        }
    }

    /** Checks the screen of each running session for Claude's trust prompt, in one round trip. */
    private fun markTrustPrompts(sessions: List<ClaudeSession>): List<ClaudeSession> {
        val running = sessions.filter { it.running }
        if (running.isEmpty()) return sessions
        val script = running.joinToString("; ") {
            "echo ${shellQuote(PANE_SEPARATOR + it.name)}; tmux capture-pane -p -J -t ${shellQuote(it.name)}"
        }
        val out = runCatching { ssh.exec(script).stdout }.getOrNull() ?: return sessions
        val screens = out.split(PANE_SEPARATOR).drop(1).associate { it.substringBefore('\n') to it.substringAfter('\n') }
        return sessions.map { it.copy(needsTrust = it.running && screens[it.name]?.contains(TRUST_PROMPT) == true) }
    }

    /**
     * Answers the trust prompt with "Yes". The cursor may start on "No, exit", so move it
     * onto the "Yes" option before pressing Enter, then check the prompt is gone.
     */
    fun trustFolder(session: ClaudeSession) = io {
        _state.update { it.copy(trustPrompt = null, busy = true) }
        try {
            val t = shellQuote(session.name)
            val lines = ssh.exec("tmux capture-pane -p -J -t $t").stdout.lines()
            val cursor = lines.indexOfFirst { it.trimStart().startsWith(MENU_CURSOR) }
            val yes = lines.indexOfFirst { YES_OPTION.containsMatchIn(it) }
            if (cursor < 0 || yes < 0) error("Couldn't find the trust question on the session's screen.")
            val moves = List(kotlin.math.abs(yes - cursor)) { if (yes > cursor) "Down" else "Up" }
            ssh.exec("tmux send-keys -t $t ${(moves + "Enter").joinToString(" ")}")
            delay(1_500)
            val after = ssh.exec("tmux capture-pane -p -J -t $t").stdout
            if (after.contains(TRUST_PROMPT)) error("Claude is still asking. Try again.")
        } catch (e: Exception) {
            _state.update { it.copy(message = "Couldn't trust the folder: ${describe(e)}") }
        } finally {
            _state.update { it.copy(busy = false) }
        }
        loadSessions()
    }

    fun dismissTrustPrompt() = _state.update { it.copy(trustPrompt = null) }

    /** Tapping a session: open its remote-control link, or explain why it exited. */
    fun openSession(session: ClaudeSession) = io {
        val output = runCatching {
            ssh.exec("tmux capture-pane -p -J -t ${shellQuote(session.name)} -S -200").stdout
        }.getOrDefault("")
        when {
            session.needsTrust -> _state.update { it.copy(trustPrompt = session) }
            !session.running ->
                _state.update { it.copy(sessionExit = SessionExit(session, output.trimEnd().lines().takeLast(30).joinToString("\n"))) }
            else -> {
                val url = URL.findAll(output).lastOrNull()?.value?.trimEnd('.', ',', ')')
                _state.update {
                    if (url != null) it.copy(openUrl = url)
                    else it.copy(message = "No link yet. Open the Claude app to find this session.")
                }
            }
        }
    }

    fun onUrlOpened() = _state.update { it.copy(openUrl = null) }
    fun dismissSessionExit() = _state.update { it.copy(sessionExit = null) }

    fun closeSession(session: ClaudeSession) = io {
        runCatching { ssh.exec("tmux kill-session -t ${shellQuote(session.name)}") }
            .onFailure { e -> _state.update { it.copy(message = "Couldn't close: ${describe(e)}") } }
        _state.update { s ->
            s.copy(sessions = s.sessions.filter { it.name != session.name }, sessionExit = null, trustPrompt = null)
        }
        loadSessions()
    }

    // ---- New session: folder picker ----

    fun newSession() {
        _state.update { it.copy(screen = Screen.NewSession) }
        io { loadDir(_state.value.cwd.ifEmpty { _state.value.home }) }
    }

    fun cancelNewSession() = _state.update { it.copy(screen = Screen.Sessions) }

    fun openDir(path: String) = io { loadDir(path) }

    fun goUp() {
        val cwd = _state.value.cwd
        if (cwd == "/" || cwd.isEmpty()) return
        val parent = cwd.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }
        openDir(parent)
    }

    fun goHome() = openDir(_state.value.home)

    fun goToPath(path: String) = io {
        try {
            loadDir(ssh.canonicalize(path.trim()))
        } catch (e: Exception) {
            _state.update { it.copy(message = "Can't open $path: ${describe(e)}") }
        }
    }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        openDir(_state.value.cwd)
    }

    private suspend fun loadDir(path: String) {
        _state.update { it.copy(busy = true) }
        try {
            val dirs = ssh.listDirs(path, _state.value.showHidden)
            _state.update { it.copy(cwd = path, dirs = dirs) }
        } catch (e: Exception) {
            _state.update { it.copy(message = "Can't open $path: ${describe(e)}") }
            if (!ssh.isConnected) disconnectLost()
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    /** Starts claude with remote control in the current folder, in a detached tmux session. */
    fun startSession() {
        val dir = _state.value.cwd
        io {
            _state.update { it.copy(busy = true) }
            try {
                if (ssh.exec("command -v tmux").exitCode != 0) {
                    error("tmux isn't installed on the server. Install it (e.g. sudo apt install tmux) and try again.")
                }
                val base = dir.trimEnd('/').substringAfterLast('/').ifEmpty { "root" }
                    .replace(Regex("[^A-Za-z0-9_-]"), "_").take(30)
                val stamp = SimpleDateFormat("HHmmss", Locale.US).format(Date())
                val name = "$SESSION_PREFIX$base-$stamp"
                val t = shellQuote(name)
                // Interactive login shell so PATH (nvm, ~/.local/bin, ...) is set up. remain-on-exit keeps
                // the pane after claude exits so the app can show why.
                val paneCmd = "\"\${SHELL:-/bin/sh}\" -ilc ${shellQuote(CLAUDE_COMMAND)}"
                val r = ssh.exec(
                    "tmux new-session -d -s $t -x 100 -y 50 -c ${shellQuote(dir)} ${shellQuote(paneCmd)} \\; " +
                        "set-window-option -t $t remain-on-exit on \\; " +
                        "set-option -t $t @cconnect_dir ${shellQuote(dir)}"
                )
                if (r.exitCode != 0) error(r.stderr.ifBlank { r.stdout }.ifBlank { "tmux exited with ${r.exitCode}" })
                _state.update { it.copy(screen = Screen.Sessions, message = "Started Claude in ${base.ifEmpty { dir }}") }
                loadSessions()
            } catch (e: Exception) {
                _state.update { it.copy(message = "Failed to start: ${describe(e)}") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // ---- helpers ----

    private fun disconnectLost() {
        stopPolling()
        _state.update { it.copy(screen = Screen.Login, error = "Connection lost", sessions = emptyList(), sessionsLoaded = false) }
    }

    private fun io(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }

    override fun onCleared() {
        ssh.disconnect()
    }

    companion object {
        /** Parses "host", "host:port", "[ipv6]" or "[ipv6]:port". */
        fun parseHost(input: String): Pair<String, Int>? {
            if (input.isEmpty()) return null
            if (input.startsWith("[")) {
                val end = input.indexOf(']')
                if (end < 0) return null
                val host = input.substring(1, end)
                val rest = input.substring(end + 1)
                val port = if (rest.startsWith(":")) rest.drop(1).toIntOrNull() ?: return null else 22
                return host to port
            }
            if (input.count { it == ':' } == 1) {
                val port = input.substringAfter(':').toIntOrNull() ?: return null
                return input.substringBefore(':') to port
            }
            return input to 22
        }

        fun describe(e: Throwable): String {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            val cause = e.cause?.message?.takeIf { it.isNotBlank() && it != msg }
            return if (cause != null) "$msg ($cause)" else msg
        }
    }
}
