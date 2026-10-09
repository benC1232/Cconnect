package com.cconnect.app.ssh

import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.IOUtils
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.keyprovider.KeyPairWrapper
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.util.concurrent.TimeUnit

data class DirEntry(val name: String, val path: String)

data class ExecResult(val exitCode: Int, val stdout: String, val stderr: String)

class HostKeyMismatchException(val host: String, val port: Int) :
    Exception("The host key for $host:$port has changed since the last connection. " +
        "This could mean someone is intercepting the connection.")

class SshManager(private val keys: KeyManager, private val hostKeys: HostKeyStore) {
    private var client: SSHClient? = null
    private var sftp: SFTPClient? = null

    val isConnected: Boolean get() = client?.let { it.isConnected && it.isAuthenticated } == true

    data class ConnectResult(val usedKey: Boolean, val newHostFingerprint: String?)

    /** Connects and authenticates. Tries this device's key first, then the password if given. */
    fun connect(host: String, port: Int, user: String, password: String?): ConnectResult {
        disconnect()
        val config = DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
        val c = SSHClient(config)
        val verifier = hostKeys.newVerifier()
        c.addHostKeyVerifier(verifier)
        c.connectTimeout = 10_000
        c.connection.keepAlive.keepAliveInterval = 15
        try {
            c.connect(host, port)
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            if (verifier.mismatch) throw HostKeyMismatchException(host, port)
            throw e
        }

        var usedKey = true
        try {
            try {
                c.authPublickey(user, KeyPairWrapper(keys.keyPair()))
            } catch (e: UserAuthException) {
                if (password.isNullOrEmpty()) {
                    throw UserAuthException(
                        "Key authentication failed. Enter a password, or add this phone's " +
                            "public key to ~/.ssh/authorized_keys on the server.", e
                    )
                }
                usedKey = false
                val finder = object : PasswordFinder {
                    override fun reqPassword(resource: Resource<*>?) = password.toCharArray()
                    override fun shouldRetry(resource: Resource<*>?) = false
                }
                c.auth(user, AuthPassword(finder), AuthKeyboardInteractive(PasswordResponseProvider(finder)))
            }
            client = c
            sftp = c.newSFTPClient()
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            client = null
            throw e
        }
        return ConnectResult(usedKey, verifier.newlyTrusted)
    }

    fun disconnect() {
        runCatching { sftp?.close() }
        runCatching { client?.disconnect() }
        sftp = null
        client = null
    }

    private fun requireClient() = client ?: error("Not connected")
    private fun requireSftp() = sftp ?: error("Not connected")

    fun homeDir(): String = requireSftp().canonicalize(".")

    fun canonicalize(path: String): String = requireSftp().canonicalize(path)

    /** Lists subdirectories of [path] (including symlinks that point to directories). */
    fun listDirs(path: String, showHidden: Boolean): List<DirEntry> {
        val s = requireSftp()
        return s.ls(path)
            .asSequence()
            .filter { showHidden || !it.name.startsWith(".") }
            .filter { info ->
                when (info.attributes.type) {
                    FileMode.Type.DIRECTORY -> true
                    FileMode.Type.SYMLINK -> runCatching {
                        s.stat(info.path).type == FileMode.Type.DIRECTORY
                    }.getOrDefault(false)
                    else -> false
                }
            }
            .map { DirEntry(it.name, it.path) }
            .sortedBy { it.name.lowercase() }
            .toList()
    }

    /** Runs [command] through the user's login shell so PATH matches an interactive login. */
    fun exec(command: String, timeoutSec: Long = 30): ExecResult {
        requireClient().startSession().use { session ->
            val cmd = session.exec("\"\${SHELL:-/bin/sh}\" -lc ${shellQuote(command)}")
            val out = IOUtils.readFully(cmd.inputStream).toString(Charsets.UTF_8.name())
            val err = IOUtils.readFully(cmd.errorStream).toString(Charsets.UTF_8.name())
            cmd.join(timeoutSec, TimeUnit.SECONDS)
            return ExecResult(cmd.exitStatus ?: -1, out, err)
        }
    }

    /** Appends this device's public key to the server's ~/.ssh/authorized_keys (like ssh-copy-id). */
    fun installPublicKey() {
        val key = shellQuote(keys.publicKeyOpenSsh())
        val r = exec(
            "mkdir -p ~/.ssh && chmod 700 ~/.ssh && touch ~/.ssh/authorized_keys && " +
                "chmod 600 ~/.ssh/authorized_keys && " +
                "(grep -qxF $key ~/.ssh/authorized_keys || echo $key >> ~/.ssh/authorized_keys)"
        )
        if (r.exitCode != 0) error("Failed to install key: ${r.stderr.ifBlank { r.stdout }}")
    }

    companion object {
        fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
