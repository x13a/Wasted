package me.lucky.wasted.remote.domain

import kotlin.math.pow

/** Bounded exponential backoff with optional jitter; only retryable errors are retried. */
class RemoteRetryPolicy(
    val maxAttempts: Int = 3,
    private val baseDelayMillis: Long = 1_000,
    private val maxDelayMillis: Long = 60_000,
    private val jitter: (Long) -> Long = { it },
) {

    fun shouldRetry(error: RemoteError): Boolean = when (error) {
        is RemoteError.Network, is RemoteError.RateLimited, is RemoteError.Server -> true
        else -> false
    }

    fun delayMillis(attempt: Int, error: RemoteError): Long {
        if (error is RemoteError.RateLimited && error.retryAfterMillis != null) {
            return error.retryAfterMillis.coerceIn(0, maxDelayMillis)
        }
        val exponential = (baseDelayMillis * 2.0.pow(attempt.coerceAtLeast(0).toDouble())).toLong()
        return jitter(exponential.coerceAtMost(maxDelayMillis))
    }
}
