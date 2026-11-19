package me.lucky.wasted.remote.data

import android.content.Context

/**
 * One process-wide Supabase client for the remote stack.
 *
 * Supabase rotates the refresh token on every refresh. Separate clients sharing the same
 * encrypted session can race: the first refresh invalidates the other client's token and the
 * loser ends with an expired session. A single client also serializes Auth's refresh job, so an
 * enrolled device keeps the same anonymous identity across foreground, worker, and UI use.
 */
object RemoteRuntime {
    private var sharedRepository: SupabaseRemoteRepository? = null

    @Synchronized
    fun repository(ctx: Context): SupabaseRemoteRepository {
        sharedRepository?.let { return it }
        return SupabaseRemoteRepository(
            RemoteConfigStore.new(ctx),
            RemoteCredentialStore.new(ctx),
        ).also { sharedRepository = it }
    }

    /** Call only when the configured project or enrollment identity is intentionally replaced. */
    @Synchronized
    fun reset() {
        sharedRepository?.close()
        sharedRepository = null
    }
}
