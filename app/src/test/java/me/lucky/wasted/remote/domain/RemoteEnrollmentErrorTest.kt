package me.lucky.wasted.remote.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Enrollment failures must reach the user as a specific, actionable reason. A rejected claim
 * previously surfaced only "request rejected", which told the owner nothing about what to fix.
 */
class RemoteEnrollmentErrorTest {

    @Test
    fun `expired token is reported as a conflict`() {
        val error = RemoteErrors.classify(
            statusCode = 400,
            sqlState = "P0001",
            isNetwork = false,
            message = "enrollment token has expired",
        )
        assertEquals(RemoteError.Conflict, error)
    }

    @Test
    fun `used token is reported as a conflict`() {
        val error = RemoteErrors.classify(
            statusCode = 400,
            sqlState = "P0001",
            isNetwork = false,
            message = "enrollment token was already used",
        )
        assertEquals(RemoteError.Conflict, error)
    }

    @Test
    fun `missing token is reported as validation`() {
        val error = RemoteErrors.classify(
            statusCode = 400,
            sqlState = "P0001",
            isNetwork = false,
            message = "enrollment token not found",
        )
        assertEquals(RemoteError.Validation, error)
    }

    /**
     * The device identity was cleaned up server-side (for example by the anonymous-identity cron
     * job) while the phone still held a valid JWT. The insert then violates the auth.users foreign
     * key and PostgREST answers 409. Reporting that as a generic rejection hides the only fix:
     * start a new anonymous session and retry.
     */
    @Test
    fun `foreign key violation on device insert is retryable authentication`() {
        val error = RemoteErrors.classify(
            statusCode = 409,
            sqlState = "23503",
            isNetwork = false,
            message = "insert or update on table \"devices\" violates foreign key " +
                "constraint \"devices_auth_user_id_fkey\"",
        )
        assertEquals(RemoteError.Authentication, error)
    }

    @Test
    fun `expired token message mentions expiry`() {
        val error = RemoteErrors.classify(
            statusCode = 400,
            sqlState = "P0001",
            isNetwork = false,
            message = "enrollment token has expired",
        )
        assertTrue(error is RemoteError.Conflict)
        assertEquals("state conflict", error.sanitizedReason)
    }
}
