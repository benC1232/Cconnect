package com.cconnect.app.ssh

import android.content.Context
import android.util.Base64
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey

/** Trust-on-first-use host key store, keyed by host:port. */
class HostKeyStore(context: Context) {
    private val prefs = context.getSharedPreferences("known_hosts", Context.MODE_PRIVATE)

    fun forget(host: String, port: Int) {
        prefs.edit().remove("$host:$port").apply()
    }

    fun newVerifier() = Verifier()

    inner class Verifier : HostKeyVerifier {
        /** Set when the host was unknown and its key was just trusted. */
        var newlyTrusted: String? = null
            private set
        var mismatch = false
            private set

        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val id = "$hostname:$port"
            val fp = fingerprint(key)
            val known = prefs.getString(id, null)
            return when (known) {
                null -> {
                    prefs.edit().putString(id, fp).apply()
                    newlyTrusted = fp
                    true
                }
                fp -> true
                else -> {
                    mismatch = true
                    false
                }
            }
        }

        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    companion object {
        fun fingerprint(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            val digest = MessageDigest.getInstance("SHA-256").digest(blob)
            return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        }
    }
}
