package com.cconnect.app

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class CconnectApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Android ships a stripped-down "BC" provider that lacks algorithms sshj needs
        // (e.g. X25519, Ed25519). Replace it with the full BouncyCastle provider.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)
    }
}
