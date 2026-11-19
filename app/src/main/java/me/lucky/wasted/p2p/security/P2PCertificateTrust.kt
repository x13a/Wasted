package me.lucky.wasted.p2p.security

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** Pure certificate hashing and pin verification used by the P2P TLS layer. */
internal object P2PCertificateTrust {

    fun hash(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(certificate.encoded).joinToString("") { "%02x".format(it) }
    }

    fun trustManager(expectedServerHash: String?): X509TrustManager =
        object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) {
                // Client certificates are verified against the pinned peer in MessageServer.
            }

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?,
            ) {
                if (expectedServerHash.isNullOrBlank()) return
                val leaf = chain?.firstOrNull()
                    ?: throw CertificateException("Peer presented no certificate")
                if (hash(leaf) != expectedServerHash) {
                    throw CertificateException("Peer certificate does not match the pinned record")
                }
            }
        }
}
