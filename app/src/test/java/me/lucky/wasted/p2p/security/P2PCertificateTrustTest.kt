package me.lucky.wasted.p2p.security

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.cert.CertificateException

class P2PCertificateTrustTest {

    private val first = HeldCertificate.Builder().build().certificate
    private val second = HeldCertificate.Builder().build().certificate

    @Test
    fun `hash is stable and distinguishes certificates`() {
        assertEquals(P2PCertificateTrust.hash(first), P2PCertificateTrust.hash(first))
        assert(first != second)
        assert(P2PCertificateTrust.hash(first) != P2PCertificateTrust.hash(second))
    }

    @Test
    fun `matching pinned certificate is accepted`() {
        P2PCertificateTrust.trustManager(P2PCertificateTrust.hash(first))
            .checkServerTrusted(arrayOf(first), "RSA")
    }

    @Test
    fun `different pinned certificate is rejected`() {
        val trust = P2PCertificateTrust.trustManager(P2PCertificateTrust.hash(first))
        assertThrows(CertificateException::class.java) {
            trust.checkServerTrusted(arrayOf(second), "RSA")
        }
    }
}
