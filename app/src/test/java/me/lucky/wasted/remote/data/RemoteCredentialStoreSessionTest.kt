package me.lucky.wasted.remote.data

import me.lucky.wasted.testing.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Switching to a different Supabase project must drop the stored anonymous session.
 *
 * The session is written by [EncryptedSessionManager] and is not scoped to a project, so a JWT
 * obtained from one project stays on disk after the Project URL changes. Its user id does not exist
 * in the new database, so the next claim fails on the `devices.auth_user_id` foreign key with
 * HTTP 409. Clearing on change keeps the stored identity consistent with the configured project.
 */
class RemoteCredentialStoreSessionTest {

    private fun store(prefs: InMemorySharedPreferences = InMemorySharedPreferences()) =
        RemoteCredentialStore(prefs)

    @Test
    fun `clearSession drops the session and the enrollment binding`() {
        val store = store()
        store.session = "{\"access_token\":\"jwt\"}"
        store.deviceId = "device-1"
        store.accountId = "account-1"
        store.enrolled = true

        store.clearSession()

        assertNull("session must be dropped", store.session)
        assertNull(store.deviceId)
        assertNull(store.accountId)
        assertFalse(store.enrolled)
    }

    /** The installation id is the device's stable identity and must survive a project switch. */
    @Test
    fun `clearSession keeps the installation id`() {
        val store = store()
        val installationId = store.installationId

        store.clearSession()

        assertEquals(installationId, store.installationId)
    }
}
