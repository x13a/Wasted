package me.lucky.wasted.p2p.security

import android.content.Context
import android.util.Log
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import me.lucky.wasted.security.LegacyEncryptedPreferencesReader
import me.lucky.wasted.security.TinkEncryptedSharedPreferences
import java.security.cert.X509Certificate

/**
 * Manages self-signed certificates for P2P local network authentication.
 * Generates certificate on first run, stores securely, and provides for TLS.
 *
 * Industry standard: Self-signed certs + certificate pinning for local networks.
 */
class CertificateManager(private val context: Context) {

    companion object {
        private const val TAG = "CertificateManager"
        private const val PREFS_NAME = "wasted_p2p_certs"
        private const val KEY_CERT_PEM = "device_cert_pem"
        private const val KEY_KEY_PEM = "device_key_pem"
        private const val CN_PREFIX = "wasted-p2p-device"
    }

    private val encryptedPrefs = TinkEncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        legacyEntriesProvider = {
            LegacyEncryptedPreferencesReader.readEntries(context, PREFS_NAME)
        },
    )

    /**
     * Get or create device certificate (stored securely).
     * This certificate is used to identify the device to peers.
     */
    fun getOrCreateDeviceCertificate(): HeldCertificate {
        val storedCertPem = encryptedPrefs.getString(KEY_CERT_PEM, null)
        val storedKeyPem = encryptedPrefs.getString(KEY_KEY_PEM, null)
        
        return if (storedCertPem != null && storedKeyPem != null) {
            HeldCertificate.decode("$storedCertPem\n$storedKeyPem")
        } else {
            val certificate = HeldCertificate.Builder()
                .commonName("$CN_PREFIX-${System.currentTimeMillis() % 10000}")
                .addSubjectAlternativeName("127.0.0.1")
                .addSubjectAlternativeName("localhost")
                .duration(365 * 10, java.util.concurrent.TimeUnit.DAYS)
                .build()
            
            // Store securely
            val certPem = certificate.certificatePem()
            val keyPem = certificate.privateKeyPkcs8Pem()
            
            encryptedPrefs.edit().apply {
                putString(KEY_CERT_PEM, certPem)
                putString(KEY_KEY_PEM, keyPem)
                apply()
            }
            
            Log.i(TAG, "Device certificate generated and stored")
            certificate
        }
    }

    /**
     * Get HandshakeCertificates for mutual TLS.
     * This allows the device to present its cert and trust peer certs.
     */
    fun getHandshakeCertificates(trustedPeerCertificate: X509Certificate? = null): HandshakeCertificates {
        val deviceCert = getOrCreateDeviceCertificate()
        val builder = HandshakeCertificates.Builder()
            .heldCertificate(deviceCert)
        
        if (trustedPeerCertificate != null) {
            builder.addTrustedCertificate(trustedPeerCertificate)
        }
        
        return builder.build()
    }

    /**
     * Get certificate in PEM format for exchange in handshake.
     */
    fun getDeviceCertificatePem(): String {
        return getOrCreateDeviceCertificate().certificatePem()
    }

    /**
     * Decode certificate from PEM string (for peer certificates received in handshake).
     */
    fun decodeCertificateFromPem(certPem: String): X509Certificate {
        return HeldCertificate.decode(certPem).certificate
    }
}
