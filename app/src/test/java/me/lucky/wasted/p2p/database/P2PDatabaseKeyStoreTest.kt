package me.lucky.wasted.p2p.database

import me.lucky.wasted.testing.InMemorySharedPreferences
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2PDatabaseKeyStoreTest {

    @Test
    fun `new database gets a stable 32-byte key`() {
        val prefs = InMemorySharedPreferences()
        val first = P2PDatabaseKeyStore(prefs).passphraseOrNull(databaseExists = false)
        val second = P2PDatabaseKeyStore(prefs).passphraseOrNull(databaseExists = false)

        assertTrue(first != null && first.size == 32)
        assertArrayEquals(first, second)
    }

    @Test
    fun `existing database with no stored key fails closed`() {
        val prefs = InMemorySharedPreferences()
        assertNull(P2PDatabaseKeyStore(prefs).passphraseOrNull(databaseExists = true))
    }

    @Test
    fun `existing database with a malformed key fails closed`() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("passphrase", "not-hex").commit()
        assertNull(P2PDatabaseKeyStore(prefs).passphraseOrNull(databaseExists = true))
    }

    @Test
    fun `new database replaces a malformed key`() {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString("passphrase", "not-hex").commit()
        val key = P2PDatabaseKeyStore(prefs).passphraseOrNull(databaseExists = false)
        assertTrue(key != null && key.size == 32)
    }

    @Test
    fun `new database does not return a key when commit fails`() {
        val prefs = InMemorySharedPreferences().apply { commitResult = false }
        assertNull(P2PDatabaseKeyStore(prefs).passphraseOrNull(databaseExists = false))
    }

    @Test
    fun `reset removes the key and migration marker`() {
        val prefs = InMemorySharedPreferences()
        val store = P2PDatabaseKeyStore(prefs)
        store.passphraseOrNull(databaseExists = false)
        store.markLegacyImportComplete()

        assertTrue(store.reset())
        assertFalse(store.isLegacyImportComplete())
        assertNull(store.passphraseOrNull(databaseExists = true))
    }
}
