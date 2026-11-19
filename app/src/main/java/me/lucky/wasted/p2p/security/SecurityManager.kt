package me.lucky.wasted.p2p.security

import android.content.Context
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLSocketFactory

/**
 * TLS identities and per-peer certificate pinning for local P2P communication.
 *
 * Every device has a self-signed certificate stored through Tink/Keystore. Pairing pins the peer's
 * leaf-certificate SHA-256; paired connections only trust that certificate. The server still accepts
 * any client certificate at the TLS layer because it learns the device ID from the handshake, but
 * [me.lucky.wasted.p2p.network.MessageServer] verifies the presented certificate against the stored
 * pin before accepting a handshake or dispatching a message.
 */
class SecurityManager(private val context: Context) {
    companion object {
        private const val TAG = "SecurityManager"
    }

    private val certificateManager = CertificateManager(context)
    private val unpinnedClientFactory by lazy { buildClientSocketFactory(null) }
    private val pinnedClientFactories = ConcurrentHashMap<String, SSLSocketFactory>()

    fun createSecureTlsServerSocketFactory(): SSLServerSocketFactory =
        buildServerSocketFactory()

    /**
     * @param pinnedCertificateHash SHA-256 leaf certificate hash expected for a paired peer.
     * Null/blank is only for discovery and first-time pairing.
     */
    fun createSecureTlsClientSocketFactory(
        pinnedCertificateHash: String? = null,
    ): SSLSocketFactory {
        if (pinnedCertificateHash.isNullOrBlank()) return unpinnedClientFactory
        return pinnedClientFactories.getOrPut(pinnedCertificateHash) {
            buildClientSocketFactory(pinnedCertificateHash)
        }
    }

    fun getTlsCertificateHash(): String =
        P2PCertificateTrust.hash(certificateManager.getOrCreateDeviceCertificate().certificate)

    fun certificateHash(certificate: X509Certificate): String =
        P2PCertificateTrust.hash(certificate)

    private fun buildServerSocketFactory(): SSLServerSocketFactory {
        val keyManagers = keyManagers()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(
            keyManagers,
            arrayOf(P2PCertificateTrust.trustManager(expectedServerHash = null)),
            SecureRandom(),
        )
        Log.i(TAG, "TLS server socket factory ready")
        return sslContext.serverSocketFactory
    }

    private fun buildClientSocketFactory(pinnedCertificateHash: String?): SSLSocketFactory {
        val keyManagers = keyManagers()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(
            keyManagers,
            arrayOf(P2PCertificateTrust.trustManager(expectedServerHash = pinnedCertificateHash)),
            SecureRandom(),
        )
        return sslContext.socketFactory
    }

    private fun keyManagers(): Array<javax.net.ssl.KeyManager> {
        val deviceCert = certificateManager.getOrCreateDeviceCertificate()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType())
        keyStore.load(null)
        keyStore.setKeyEntry(
            "device-key",
            deviceCert.keyPair.private,
            "".toCharArray(),
            arrayOf(deviceCert.certificate),
        )
        val keyManagerFactory = KeyManagerFactory.getInstance(
            KeyManagerFactory.getDefaultAlgorithm(),
        )
        keyManagerFactory.init(keyStore, "".toCharArray())
        return keyManagerFactory.keyManagers
    }
}
