package me.lucky.wasted.remote.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRetryPolicyTest {

    private val policy = RemoteRetryPolicy()

    @Test
    fun `retryable and fatal errors are classified correctly`() {
        assertTrue(policy.shouldRetry(RemoteError.Network))
        assertTrue(policy.shouldRetry(RemoteError.RateLimited()))
        assertTrue(policy.shouldRetry(RemoteError.Server(503)))
        assertFalse(policy.shouldRetry(RemoteError.Authentication))
        assertFalse(policy.shouldRetry(RemoteError.RlsDenied))
        assertFalse(policy.shouldRetry(RemoteError.Conflict))
        assertFalse(policy.shouldRetry(RemoteError.Validation))
        assertFalse(policy.shouldRetry(RemoteError.Unknown("x")))
    }

    @Test
    fun `delay grows exponentially and is capped`() {
        assertEquals(1_000L, policy.delayMillis(0, RemoteError.Network))
        assertEquals(2_000L, policy.delayMillis(1, RemoteError.Network))
        assertEquals(4_000L, policy.delayMillis(2, RemoteError.Server(500)))
        assertEquals(60_000L, policy.delayMillis(10, RemoteError.Network))
    }

    @Test
    fun `rate limit retry-after wins over exponential delay`() {
        assertEquals(5_000L, policy.delayMillis(0, RemoteError.RateLimited(5_000L)))
        assertEquals(60_000L, policy.delayMillis(0, RemoteError.RateLimited(120_000L)))
    }

    @Test
    fun `jitter is applied to exponential delay`() {
        val jittered = RemoteRetryPolicy(jitter = { it + 100 })
        assertEquals(1_100L, jittered.delayMillis(0, RemoteError.Network))
    }

    @Test
    fun `classification maps transport signals`() {
        assertEquals(RemoteError.Network, RemoteErrors.classify(null, null, isNetwork = true, message = null))
        assertEquals(
            RemoteError.RateLimited(),
            RemoteErrors.classify(429, null, isNetwork = false, message = null),
        )
        assertEquals(
            RemoteError.Server(503),
            RemoteErrors.classify(503, null, isNetwork = false, message = null),
        )
        assertEquals(
            RemoteError.Authentication,
            RemoteErrors.classify(401, null, isNetwork = false, message = null),
        )
        assertEquals(
            RemoteError.RlsDenied,
            RemoteErrors.classify(403, "42501", isNetwork = false, message = null),
        )
        assertEquals(
            RemoteError.Conflict,
            RemoteErrors.classify(400, "P0001", isNetwork = false, message = "settings revision conflict"),
        )
        assertEquals(
            RemoteError.BackendUnavailable,
            RemoteErrors.classify(404, "PGRST202", isNetwork = false, message = null),
        )
        assertEquals(
            RemoteError.Validation,
            RemoteErrors.classify(400, "P0001", isNetwork = false, message = "unsupported action type"),
        )
    }
}
