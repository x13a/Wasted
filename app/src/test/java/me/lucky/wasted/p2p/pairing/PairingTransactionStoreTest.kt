package me.lucky.wasted.p2p.pairing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTransactionStoreTest {

    @Test
    fun `matching response is consumed once`() {
        val store = PairingTransactionStore()
        assertTrue(store.begin("peer", "cert", "nonce", 60_000))
        assertTrue(store.consume("peer", "cert", "nonce"))
        assertFalse(store.consume("peer", "cert", "nonce"))
    }

    @Test
    fun `wrong peer certificate or nonce is rejected`() {
        val store = PairingTransactionStore()
        assertTrue(store.begin("peer", "cert", "nonce", 60_000))
        assertFalse(store.consume("other", "cert", "nonce"))
        assertFalse(store.consume("peer", "other-cert", "nonce"))
        assertFalse(store.consume("peer", "cert", "other-nonce"))
        assertTrue(store.consume("peer", "cert", "nonce"))
    }

    @Test
    fun `expired transaction is rejected at the exact boundary`() {
        var now = 1_000L
        val store = PairingTransactionStore { now }
        assertTrue(store.begin("peer", "cert", "nonce", ttlMillis = 100))
        now = 1_100L
        assertFalse(store.consume("peer", "cert", "nonce"))
    }

    @Test
    fun `overlapping begin for the same peer is rejected`() {
        val store = PairingTransactionStore()
        assertTrue(store.begin("peer", "cert", "nonce-1", 60_000))
        assertFalse(store.begin("peer", "other-cert", "nonce-2", 60_000))
        assertTrue(store.consume("peer", "cert", "nonce-1"))
    }

    @Test
    fun `stale nonce cannot cancel a newer transaction`() {
        var now = 1_000L
        val store = PairingTransactionStore { now }
        assertTrue(store.begin("peer", "cert", "nonce-1", ttlMillis = 100))
        now = 1_101L
        assertTrue(store.begin("peer", "cert", "nonce-2", ttlMillis = 60_000))
        assertFalse(store.cancel("peer", "nonce-1"))
        assertTrue(store.consume("peer", "cert", "nonce-2"))
    }

    @Test
    fun `cancel and clear remove pending transactions`() {
        val store = PairingTransactionStore()
        assertTrue(store.begin("peer", "cert", "nonce", 60_000))
        store.cancel("peer")
        assertFalse(store.consume("peer", "cert", "nonce"))

        assertTrue(store.begin("peer", "cert", "nonce", 60_000))
        store.clear()
        assertFalse(store.consume("peer", "cert", "nonce"))
    }
}
