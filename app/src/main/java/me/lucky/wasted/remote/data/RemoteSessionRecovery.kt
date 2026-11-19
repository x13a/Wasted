package me.lucky.wasted.remote.data

import kotlinx.coroutines.CancellationException

import me.lucky.wasted.remote.domain.RemoteDeviceDto
import me.lucky.wasted.remote.domain.RemoteError
import me.lucky.wasted.remote.domain.RemoteException

/**
 * Rebinds an enrolled device after its anonymous Auth session is lost.
 * Transient failures remain pending so the next sync retries with the same replacement session;
 * only a server-side permanent rejection blocks further attempts.
 */
internal class RemoteSessionRecovery(
    private val credentials: RemoteCredentialStore,
    private val auth: AuthPort,
    private val classifyError: (Throwable) -> RemoteError,
) {
    interface AuthPort {
        suspend fun hasValidSession(): Boolean
        suspend fun replaceAnonymousSession()
        suspend fun bindDevice(deviceId: String, installationId: String): RemoteDeviceDto
    }

    suspend fun run(): RemoteDeviceDto? {
        val deviceId = credentials.deviceId ?: run {
            credentials.recoveryBlocked = true
            return null
        }
        val installationId = credentials.installationId
        credentials.recoveryPending = true
        try {
            if (!auth.hasValidSession()) auth.replaceAnonymousSession()
            val device = auth.bindDevice(deviceId, installationId)
            credentials.deviceId = device.id
            credentials.accountId = device.accountId
            credentials.enrolled = true
            credentials.recoveryPending = false
            credentials.recoveryBlocked = false
            return device
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteException) {
            blockIfPermanent(e.error)
            throw e
        } catch (e: Throwable) {
            val error = classifyError(e)
            blockIfPermanent(error)
            throw RemoteException(error, e)
        }
    }

    private fun blockIfPermanent(error: RemoteError) {
        if (error !is RemoteError.Network &&
            error !is RemoteError.RateLimited &&
            error !is RemoteError.Server &&
            error !is RemoteError.BackendUnavailable
        ) {
            credentials.recoveryBlocked = true
        }
    }
}
