package me.lucky.wasted.p2p.database

import android.content.Context
import java.io.File

/**
 * Serializes revocation marker updates across the encrypted and fallback stores, verifies readback,
 * and keeps a durable fail-closed latch when both stores fail.
 */
class P2PRevocationStore(
    private val encrypted: P2PDatabaseKeyStore,
    private val fallback: P2PRevocationFallbackStore,
    context: Context,
) {
    private val lock = Any()
    private val latch = File(context.filesDir, LATCH_FILE)

    companion object {
        private const val LATCH_FILE = "p2p_revocation_fail_closed"

        fun clearLocal(context: Context): Boolean {
            val encryptedCleared = runCatching {
                P2PDatabaseKeyStore.new(context).replacePendingRevocations(emptySet())
            }.getOrDefault(false)
            val fallbackCleared = runCatching {
                P2PRevocationFallbackStore(context).replace(emptySet())
            }.getOrDefault(false)
            val latchCleared = runCatching {
                val latch = File(context.filesDir, LATCH_FILE)
                !latch.exists() || latch.delete()
            }.getOrDefault(false)
            val encryptedEmpty = runCatching {
                P2PDatabaseKeyStore.new(context).pendingRevocations().isEmpty()
            }.getOrDefault(false)
            val fallbackEmpty = runCatching {
                P2PRevocationFallbackStore(context).pending().isEmpty()
            }.getOrDefault(false)
            return encryptedCleared && fallbackCleared && latchCleared &&
                encryptedEmpty && fallbackEmpty
        }
    }

    fun pending(): Set<String> = synchronized(lock) {
        encrypted.pendingRevocations() + fallback.pending()
    }

    fun isUnavailable(): Boolean = latch.exists()

    fun add(deviceId: String): Boolean = synchronized(lock) {
        val next = (encrypted.pendingRevocations() + fallback.pending()) + deviceId
        val encryptedOk = encrypted.replacePendingRevocations(next)
        val fallbackOk = fallback.replace(next)
        val verified = (encrypted.pendingRevocations() + fallback.pending()).containsAll(next)
        if (!encryptedOk && !fallbackOk || !verified) {
            markUnavailable()
            return false
        }
        true
    }

    fun remove(deviceId: String): Boolean = synchronized(lock) {
        val next = (encrypted.pendingRevocations() + fallback.pending()) - deviceId
        val encryptedOk = encrypted.replacePendingRevocations(next)
        val fallbackOk = fallback.replace(next)
        val verified = (encrypted.pendingRevocations() + fallback.pending()) == next
        if (!encryptedOk && !fallbackOk || !verified) {
            markUnavailable()
            return false
        }
        true
    }

    fun clearAll(): Boolean = synchronized(lock) {
        val encryptedOk = encrypted.replacePendingRevocations(emptySet())
        val fallbackOk = fallback.replace(emptySet())
        latch.delete()
        encryptedOk && fallbackOk
    }

    fun markUnavailable(): Boolean = runCatching {
        if (!latch.exists()) latch.writeText("1")
        latch.exists()
    }.getOrDefault(false)

    fun clearUnavailable(): Boolean = runCatching { latch.delete() || !latch.exists() }
        .getOrDefault(false)
}
