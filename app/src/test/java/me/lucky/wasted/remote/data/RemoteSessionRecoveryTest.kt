package me.lucky.wasted.remote.data

import kotlinx.coroutines.runBlocking

import me.lucky.wasted.remote.domain.RemoteDeviceDto
import me.lucky.wasted.remote.domain.RemoteError
import me.lucky.wasted.remote.domain.RemoteException
import me.lucky.wasted.testing.InMemorySharedPreferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteSessionRecoveryTest {

    private class FakeAuth : RemoteSessionRecovery.AuthPort {
        var validSession = false
        var replaceCalls = 0
        var bindCalls = 0
        val failures = ArrayDeque<Throwable>()

        override suspend fun hasValidSession(): Boolean = validSession

        override suspend fun replaceAnonymousSession() {
            replaceCalls++
            validSession = true
        }

        override suspend fun bindDevice(
            deviceId: String,
            installationId: String,
        ): RemoteDeviceDto {
            bindCalls++
            failures.removeFirstOrNull()?.let { throw it }
            return RemoteDeviceDto(
                id = deviceId,
                accountId = "account-1",
                installationId = installationId,
                name = "Phone",
                enrolledAt = "t",
            )
        }
    }

    private val credentials = RemoteCredentialStore(InMemorySharedPreferences()).apply {
        enrolled = true
        deviceId = "device-1"
        accountId = "account-1"
        recoveryPending = true
    }
    private val auth = FakeAuth()
    private val recovery = RemoteSessionRecovery(
        credentials = credentials,
        auth = auth,
        classifyError = { RemoteError.Validation },
    )

    @Test
    fun `successful recovery rebinds the device and clears recovery state`() = runBlocking {
        val device = recovery.run()

        assertEquals("device-1", device?.id)
        assertEquals(1, auth.replaceCalls)
        assertEquals(1, auth.bindCalls)
        assertTrue(credentials.enrolled)
        assertFalse(credentials.recoveryPending)
        assertFalse(credentials.recoveryBlocked)
    }

    @Test
    fun `valid replacement session is reused instead of creating another identity`() = runBlocking {
        auth.validSession = true

        recovery.run()

        assertEquals(0, auth.replaceCalls)
        assertEquals(1, auth.bindCalls)
    }

    @Test
    fun `transient recovery failure stays pending and retries the same session`() = runBlocking {
        auth.failures.add(RemoteException(RemoteError.Network))
        val first = runCatching { recovery.run() }

        assertTrue(first.isFailure)
        assertTrue(credentials.recoveryPending)
        assertFalse(credentials.recoveryBlocked)
        assertEquals(1, auth.replaceCalls)

        val second = recovery.run()

        assertEquals("device-1", second?.id)
        assertEquals(1, auth.replaceCalls)
        assertFalse(credentials.recoveryPending)
    }

    @Test
    fun `permanent server rejection blocks further automatic recovery`() = runBlocking {
        auth.failures.add(RemoteException(RemoteError.Authentication))

        val outcome = runCatching { recovery.run() }

        assertTrue(outcome.isFailure)
        assertTrue(credentials.recoveryPending)
        assertTrue(credentials.recoveryBlocked)
    }

    @Test
    fun `missing stored device identity cannot mint an unbound session`() = runBlocking {
        credentials.deviceId = null

        val device = recovery.run()

        assertNull(device)
        assertEquals(0, auth.replaceCalls)
        assertEquals(0, auth.bindCalls)
        assertTrue(credentials.recoveryBlocked)
    }
}
