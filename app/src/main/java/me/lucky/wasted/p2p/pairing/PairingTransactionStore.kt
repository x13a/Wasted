package me.lucky.wasted.p2p.pairing

import java.security.MessageDigest

/** Nonce-bound, single-use pairing transaction store. */
internal class PairingTransactionStore(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Pending(
        val expectedCertificateHash: String,
        val nonce: String,
        val expiresAt: Long,
    )

    private val lock = Any()
    private val pending = mutableMapOf<String, Pending>()

    fun begin(
        peerDeviceId: String,
        expectedCertificateHash: String,
        nonce: String,
        ttlMillis: Long,
    ): Boolean {
        if (expectedCertificateHash.isBlank() || nonce.isBlank()) return false
        synchronized(lock) {
            val existing = pending[peerDeviceId]
            if (existing != null && clock() < existing.expiresAt) return false
            pending[peerDeviceId] = Pending(
                expectedCertificateHash = expectedCertificateHash,
                nonce = nonce,
                expiresAt = clock() + ttlMillis,
            )
        }
        return true
    }

    fun consume(
        peerDeviceId: String,
        actualCertificateHash: String,
        nonce: String,
    ): Boolean = synchronized(lock) {
        val transaction = pending[peerDeviceId] ?: return false
        if (clock() >= transaction.expiresAt ||
            transaction.expectedCertificateHash != actualCertificateHash ||
            !MessageDigest.isEqual(
                transaction.nonce.toByteArray(Charsets.UTF_8),
                nonce.toByteArray(Charsets.UTF_8),
            )
        ) {
            return false
        }
        pending.remove(peerDeviceId)
        true
    }

    fun cancel(peerDeviceId: String, nonce: String? = null): Boolean = synchronized(lock) {
        val existing = pending[peerDeviceId] ?: return false
        if (nonce != null && existing.nonce != nonce) return false
        pending.remove(peerDeviceId)
        true
    }

    fun clear() {
        synchronized(lock) {
            pending.clear()
        }
    }
}
