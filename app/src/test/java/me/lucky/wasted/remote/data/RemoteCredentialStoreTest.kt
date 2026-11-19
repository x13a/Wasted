package me.lucky.wasted.remote.data

import me.lucky.wasted.testing.InMemorySharedPreferences
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class RemoteCredentialStoreTest {

    private fun store(prefs: InMemorySharedPreferences = InMemorySharedPreferences()) =
        RemoteCredentialStore(prefs)

    @Test
    fun `installation id is a stable uuid across instances`() {
        val prefs = InMemorySharedPreferences()
        val first = store(prefs).installationId
        val second = store(prefs).installationId
        assertEquals(first, second)
        UUID.fromString(first)
    }

    @Test
    fun `rotate installation id changes and persists`() {
        val prefs = InMemorySharedPreferences()
        val store = store(prefs)
        val before = store.installationId
        val rotated = store.rotateInstallationId()
        assertNotEquals(before, rotated)
        assertEquals(rotated, store(prefs).installationId)
    }

    @Test
    fun `defaults are unenrolled with notifications on`() {
        val store = store()
        assertFalse(store.enrolled)
        assertNull(store.deviceId)
        assertNull(store.accountId)
        assertNull(store.session)
        assertTrue(store.notificationsEnabled)
    }

    @Test
    fun `round trips identity and session`() {
        val store = store()
        store.enrolled = true
        store.deviceId = "device-1"
        store.accountId = "account-1"
        store.session = "{\"token\":\"value\"}"
        store.notificationsEnabled = true
        assertTrue(store.enrolled)
        assertEquals("device-1", store.deviceId)
        assertEquals("account-1", store.accountId)
        assertEquals("{\"token\":\"value\"}", store.session)
        assertTrue(store.notificationsEnabled)
    }

    @Test
    fun `clear session removes identity but keeps installation id`() {
        val store = store()
        store.enrolled = true
        store.deviceId = "device-1"
        store.accountId = "account-1"
        store.session = "session"
        store.lastSyncAt = 123L
        store.appliedGlobalSettingsRevision = 9L
        store.appliedDeviceSettingsRevision = 4L
        store.recoveryPending = true
        store.recoveryBlocked = true
        val installationId = store.installationId
        store.clearSession()
        assertFalse(store.enrolled)
        assertFalse(store.recoveryPending)
        assertFalse(store.recoveryBlocked)
        assertNull(store.deviceId)
        assertNull(store.accountId)
        assertNull(store.session)
        assertEquals(0L, store.lastSyncAt)
        assertEquals(0L, store.appliedGlobalSettingsRevision)
        assertEquals(0L, store.appliedDeviceSettingsRevision)
        assertEquals(installationId, store.installationId)
    }

    @Test
    fun `database passphrase is 32 bytes and stable`() {
        val prefs = InMemorySharedPreferences()
        val passphrase = store(prefs).databasePassphrase
        assertEquals(32, passphrase.size)
        assertArrayEquals(passphrase, store(prefs).databasePassphrase)
        assertFalse(passphrase.all { it == 0.toByte() })
    }
}
