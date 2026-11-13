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
import net.schmizz.sshj.userauth.UserAuthException
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
data class ClaudeSession(
    val name: String,
    val dir: String,
    val running: Boolean,
    val needsTrust: Boolean = false,
    /** The login shell is stuck on oh-my-zsh's "Would you like to update?" question, before claude starts. */
    val needsZshUpdateAnswer: Boolean = false,
    /** Claude isn't logged in to a claude.ai account, so Remote Control can't start. */
    val needsLogin: Boolean = false,
    /** /login is waiting for the code from the browser; this is its sign-in link. */
    val pendingLoginUrl: String? = null,
)

/** Logging Claude in from the app. [url] is set once /login is showing its sign-in link. */
data class LoginPrompt(val session: ClaudeSession, val url: String? = null, val submitting: Boolean = false)

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
    val zshUpdatePrompt: ClaudeSession? = null,
    val loginPrompt: LoginPrompt? = null,
    /** The SSH connection dropped (e.g. while the app was in the background) and is being re-established. */
    val reconnecting: Boolean = false,
    val openUrl: String? = null,
)

const val CLAUDE_COMMAND = "claude --remote-control"
private const val SESSION_PREFIX = "claude-"
private const val MAX_RECENT_LOGINS = 5
private const val POLL_INTERVAL_MS = 5_000L
private const val RECONNECT_ATTEMPTS = 36
private const val RECONNECT_DELAY_MS = 5_000L // with 36 attempts, keeps trying for about 3 minutes
private val URL = Regex("""https://\S+""")
/** Claude's "do you trust this folder?" prompt, shown on the first run in a folder. */
private val TRUST_PROMPT = Regex("""(?i)Yes, I trust this folder|Do you trust the files in this folder""")
/** oh-my-zsh's update question, asked by the interactive login shell before claude starts. */
private val ZSH_UPDATE_PROMPT = Regex("""(?i)\[oh-my-zsh] Would you like to update\? \[Y/n]\s*$""")
/** Claude's status line when no account is logged in. */
private val NOT_LOGGED_IN = Regex("""Not logged in\s*·\s*Run /login""")
/** The /login menu option for a claude.ai account (the only kind that supports Remote Control). */
private val LOGIN_SUBSCRIPTION_OPTION = Regex("""Claude account with subscription""")
/** Shown under the sign-in link while /login waits for the code from the browser. */
private val LOGIN_CODE_PROMPT = Regex("""Paste code here""")
private val LOGIN_SUCCESS = Regex("""(?i)Login successful""")
private val LOGIN_CONTINUE = Regex("""(?i)Press Enter to continue""")
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
    /** Sessions whose oh-my-zsh update question was already shown. */
    private val zshUpdatePrompted = mutableSetOf<String>()
    /** Sessions whose "not logged in" dialog was already shown. */
    private val loginPrompted = mutableSetOf<String>()

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
                if (!ssh.isConnected && !reconnect()) {
                    disconnectLost()
                    break
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Re-establishes a dropped connection with this phone's key, keeping the screen and any open
     * dialog as they are. Android cuts background apps off the network, so this happens whenever
     * the user leaves the app for a while (e.g. to sign in in the browser). Network errors are
     * retried for a few minutes; a rejected key or changed host key gives up right away.
     */
    private suspend fun reconnect(): Boolean {
        val s = _state.value
        val (host, port) = parseHost(s.host.trim()) ?: return false
        _state.update { it.copy(reconnecting = true) }
        try {
            repeat(RECONNECT_ATTEMPTS) { attempt ->
                try {
                    ssh.connect(host, port, s.user.trim(), null)
                    loadSessions()
                    return true
                } catch (e: HostKeyMismatchException) {
                    return false
                } catch (e: UserAuthException) {
                    return false
                } catch (e: Exception) {
                    if (attempt < RECONNECT_ATTEMPTS - 1) delay(RECONNECT_DELAY_MS)
                }
            }
            return false
        } finally {
            _state.update { it.copy(reconnecting = false) }
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
            .let(::markPrompts)
        val newPrompt = sessions.firstOrNull { it.needsTrust && trustPrompted.add(it.name) }
        val newZshPrompt = sessions.firstOrNull { it.needsZshUpdateAnswer && zshUpdatePrompted.add(it.name) }
        val newLogin = sessions.firstOrNull { it.needsLogin && loginPrompted.add(it.name) }
        _state.update { s ->
            s.copy(
                sessions = sessions,
                sessionsLoaded = true,
                trustPrompt = newPrompt ?: s.trustPrompt?.takeIf { p -> sessions.any { it.name == p.name && it.needsTrust } },
                zshUpdatePrompt = newZshPrompt
                    ?: s.zshUpdatePrompt?.takeIf { p -> sessions.any { it.name == p.name && it.needsZshUpdateAnswer } },
                // Once /login is underway the "not logged in" line is gone, so keep a prompt that has its link.
                loginPrompt = s.loginPrompt?.takeIf { p ->
                    sessions.any { it.name == p.session.name && it.running && (p.url != null || it.needsLogin) }
                }
                    // A /login already waiting for its code (e.g. the app restarted mid-login): pick it back up.
                    ?: sessions.firstOrNull { it.pendingLoginUrl != null }?.let { LoginPrompt(it, it.pendingLoginUrl) }
                    ?: newLogin?.let { LoginPrompt(it) },
            )
        }
    }

    /** Checks the screen of each running session for prompts that need an answer, in one round trip. */
    private fun markPrompts(sessions: List<ClaudeSession>): List<ClaudeSession> {
        val running = sessions.filter { it.running }
        if (running.isEmpty()) return sessions
        val script = running.joinToString("; ") {
            "echo ${shellQuote(PANE_SEPARATOR + it.name)}; tmux capture-pane -p -J -t ${shellQuote(it.name)}"
        }
        val out = runCatching { ssh.exec(script).stdout }.getOrNull() ?: return sessions
        val screens = out.split(PANE_SEPARATOR).drop(1).associate { it.substringBefore('\n') to it.substringAfter('\n') }
        return sessions.map { s ->
            val screen = screens[s.name].takeIf { s.running }.orEmpty()
            s.copy(
                needsTrust = screen.contains(TRUST_PROMPT),
                needsZshUpdateAnswer = screen.isWaitingForZshUpdate(),
                needsLogin = screen.contains(NOT_LOGGED_IN),
                pendingLoginUrl = if (screen.contains(LOGIN_CODE_PROMPT)) extractLoginUrl(screen) else null,
            )
        }
    }

    /** True only while the question is the last thing on screen, i.e. still unanswered. */
    private fun String.isWaitingForZshUpdate(): Boolean =
        lines().lastOrNull { it.isNotBlank() }?.let { ZSH_UPDATE_PROMPT.containsMatchIn(it) } == true

    /** Answers oh-my-zsh's update question; claude starts once the shell continues. */
    fun answerZshUpdate(session: ClaudeSession, update: Boolean) = io {
        _state.update { it.copy(zshUpdatePrompt = null, busy = true) }
        try {
            val t = shellQuote(session.name)
            val screen = ssh.exec("tmux capture-pane -p -J -t $t").stdout
            if (!screen.isWaitingForZshUpdate()) error("The question is no longer on the session's screen.")
            ssh.exec("tmux send-keys -t $t ${if (update) "y" else "n"} Enter")
            _state.update {
                it.copy(message = if (update) "Updating oh-my-zsh, then Claude starts" else "Skipped the update. Claude is starting")
            }
        } catch (e: Exception) {
            _state.update { it.copy(message = "Couldn't answer: ${describe(e)}") }
        } finally {
            _state.update { it.copy(busy = false) }
        }
        loadSessions()
    }

    fun dismissZshUpdatePrompt() = _state.update { it.copy(zshUpdatePrompt = null) }

    /**
     * Answers the trust prompt with "Yes". The cursor may start on "No, exit", so move it
     * onto the "Yes" option before pressing Enter, then check the prompt is gone.
     */
    fun trustFolder(session: ClaudeSession) = io {
        _state.update { it.copy(trustPrompt = null, busy = true) }
        try {
            val t = shellQuote(session.name)
            if (!selectMenuOption(t, YES_OPTION)) error("Couldn't find the trust question on the session's screen.")
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

    /**
     * Moves the cursor in one of Claude's menus onto the line matching [option] and presses Enter.
     * The cursor may start on any option. Returns false if the menu or option isn't on screen.
     */
    private fun selectMenuOption(t: String, option: Regex): Boolean {
        val lines = ssh.exec("tmux capture-pane -p -J -t $t").stdout.lines()
        val cursor = lines.indexOfFirst { it.trimStart().startsWith(MENU_CURSOR) }
        val target = lines.indexOfFirst { option.containsMatchIn(it) }
        if (cursor < 0 || target < 0) return false
        val moves = List(kotlin.math.abs(target - cursor)) { if (target > cursor) "Down" else "Up" }
        ssh.exec("tmux send-keys -t $t ${(moves + "Enter").joinToString(" ")}")
        return true
    }

    /** Polls the session's screen until [done] matches it, or fails after [timeoutMs]. */
    private suspend fun waitForScreen(t: String, timeoutMs: Long, what: String, done: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val screen = ssh.exec("tmux capture-pane -p -J -t $t").stdout
            if (done(screen)) return screen
            if (System.currentTimeMillis() > deadline) error("Timed out waiting for $what.")
            delay(700)
        }
    }

    // ---- Logging Claude in ----

    /**
     * Runs /login in the session, picks the claude.ai account option and reads the sign-in link off
     * the screen. The user then signs in on their phone and pastes the code back ([submitLoginCode]).
     */
    fun startLogin(session: ClaudeSession) = io {
        _state.update { it.copy(busy = true) }
        try {
            val t = shellQuote(session.name)
            ssh.exec("tmux send-keys -t $t -l /login")
            delay(500) // let the slash-command menu settle before Enter
            ssh.exec("tmux send-keys -t $t Enter")
            val menu = waitForScreen(t, 10_000, "the login menu") {
                it.contains(LOGIN_SUBSCRIPTION_OPTION) || it.contains(LOGIN_CODE_PROMPT)
            }
            if (!menu.contains(LOGIN_CODE_PROMPT) && !selectMenuOption(t, LOGIN_SUBSCRIPTION_OPTION)) {
                error("Couldn't find the Claude account option in the login menu.")
            }
            val screen = waitForScreen(t, 15_000, "the sign-in link") {
                it.contains(LOGIN_CODE_PROMPT) && extractLoginUrl(it) != null
            }
            val url = extractLoginUrl(screen)!!
            _state.update { it.copy(loginPrompt = LoginPrompt(session, url)) }
        } catch (e: Exception) {
            _state.update { it.copy(loginPrompt = null, message = "Couldn't start login: ${describe(e)}") }
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    /** Pastes the code from the browser into /login and confirms the result. */
    fun submitLoginCode(session: ClaudeSession, code: String) = io {
        _state.update { s -> s.copy(loginPrompt = s.loginPrompt?.copy(submitting = true), busy = true) }
        try {
            val t = shellQuote(session.name)
            ssh.exec("tmux send-keys -t $t -l ${shellQuote(code.trim())}")
            ssh.exec("tmux send-keys -t $t Enter")
            val screen = waitForScreen(t, 30_000, "Claude to confirm the login") {
                it.contains(LOGIN_SUCCESS) || !it.contains(LOGIN_CODE_PROMPT)
            }
            if (!screen.contains(LOGIN_SUCCESS)) {
                val tail = screen.lines().filter { it.isNotBlank() }.takeLast(4).joinToString(" ") { it.trim() }
                error(tail.ifBlank { "Claude didn't accept the code." })
            }
            if (screen.contains(LOGIN_CONTINUE)) ssh.exec("tmux send-keys -t $t Enter")
            loginPrompted.remove(session.name)
            // --remote-control is only read at startup, so this session stays without a link.
            _state.update {
                it.copy(loginPrompt = null, message = "Logged in. Close this session and start a new one to use Remote Control.")
            }
        } catch (e: Exception) {
            _state.update { s ->
                s.copy(loginPrompt = s.loginPrompt?.copy(submitting = false), message = "Login failed: ${describe(e)}")
            }
        } finally {
            _state.update { it.copy(busy = false) }
        }
        loadSessions()
    }

    /** Closes the login dialog, backing out of /login if it was already showing its link. */
    fun cancelLogin(session: ClaudeSession) = io {
        val inProgress = _state.value.loginPrompt?.url != null
        _state.update { it.copy(loginPrompt = null) }
        if (inProgress) runCatching { ssh.exec("tmux send-keys -t ${shellQuote(session.name)} Escape") }
    }

    /** Tapping a session: open its remote-control link, or explain why it exited. */
    fun openSession(session: ClaudeSession) = io {
        val output = runCatching {
            ssh.exec("tmux capture-pane -p -J -t ${shellQuote(session.name)} -S -200").stdout
        }.getOrDefault("")
        when {
            session.needsTrust -> _state.update { it.copy(trustPrompt = session) }
            session.needsZshUpdateAnswer -> _state.update { it.copy(zshUpdatePrompt = session) }
            session.needsLogin -> _state.update { it.copy(loginPrompt = LoginPrompt(session)) }
            _state.value.loginPrompt?.session?.name == session.name -> Unit // its login dialog is already open
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
            s.copy(sessions = s.sessions.filter { it.name != session.name }, sessionExit = null, trustPrompt = null, zshUpdatePrompt = null,
                loginPrompt = s.loginPrompt?.takeIf { it.session.name != session.name })
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

        /**
         * Reads /login's sign-in link off the screen. Claude hard-wraps it at the pane width, so the
         * continuation lines (which start at column 0, unlike the indented UI text) are joined back on.
         */
        fun extractLoginUrl(screen: String): String? {
            val lines = screen.lines()
            val start = lines.indexOfFirst { it.trimStart().startsWith("https://") && "oauth" in it }
            if (start < 0) return null
            val url = StringBuilder(lines[start].trim())
            for (line in lines.drop(start + 1)) {
                if (line.isBlank() || line.first().isWhitespace()) break
                url.append(line.trimEnd())
            }
            return url.toString()
        }

        fun describe(e: Throwable): String {
            val msg = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            val cause = e.cause?.message?.takeIf { it.isNotBlank() && it != msg }
            return if (cause != null) "$msg ($cause)" else msg
        }
    }
}
