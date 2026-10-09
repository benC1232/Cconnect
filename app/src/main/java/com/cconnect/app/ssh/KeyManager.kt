package com.cconnect.app.ssh

import android.content.Context
import android.util.Base64
import net.schmizz.sshj.common.Buffer
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/** Generates and stores this device's SSH key pair in app-private storage. */
class KeyManager(context: Context) {
    private val privFile = File(context.filesDir, "id_rsa.pk8")
    private val pubFile = File(context.filesDir, "id_rsa.x509")
    private var cached: KeyPair? = null

    @Synchronized
    fun keyPair(): KeyPair {
        cached?.let { return it }
        val kp = if (privFile.exists() && pubFile.exists()) load() else generate()
        cached = kp
        return kp
    }

    /** Public key in OpenSSH `authorized_keys` format. */
    fun publicKeyOpenSsh(): String {
        val blob = Buffer.PlainBuffer().putPublicKey(keyPair().public).compactData
        return "ssh-rsa " + Base64.encodeToString(blob, Base64.NO_WRAP) + " cconnect@android"
    }

    private fun load(): KeyPair {
        val kf = KeyFactory.getInstance("RSA")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
        val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
        return KeyPair(pub, priv)
    }

    private fun generate(): KeyPair {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        privFile.writeBytes(kp.private.encoded)
        pubFile.writeBytes(kp.public.encoded)
        return kp
    }
}
