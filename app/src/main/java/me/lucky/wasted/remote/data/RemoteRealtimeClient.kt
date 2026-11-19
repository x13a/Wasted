package me.lucky.wasted.remote.data

import android.util.Log

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Keeps one Realtime channel alive and re-creates it after a disconnect.
 *
 * Every change — and every (re)subscribe — is only a "sync required" invalidation signal;
 * the authoritative state always comes from device_reconcile. A dead channel must never be
 * treated as started, otherwise commands silently fall back to the 60-second heartbeat.
 */
class RemoteRealtimeClient(
    private val clientProvider: () -> SupabaseClient?,
) {
    companion object {
        private const val TAG = "RemoteRealtime"
        private const val CHANNEL_TOPIC = "wasted:remote-sync"
        private const val SUBSCRIBE_TIMEOUT_MS = 15_000L
        private const val RECONNECT_DELAY_MS = 5_000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L
        private const val STABLE_CONNECTION_MS = 30_000L
    }

    private val mutex = Mutex()

    @Volatile
    private var connection: Job? = null

    @Volatile
    private var currentChannel: RealtimeChannel? = null

    private var deviceId: String? = null
    private var accountId: String? = null
    private var generation = 0

    val isStarted: Boolean
        get() = connection?.isActive == true

    suspend fun start(
        scope: CoroutineScope,
        deviceId: String,
        accountId: String?,
        onSyncRequired: () -> Unit,
    ) = mutex.withLock {
        if (connection?.isActive == true && this.deviceId == deviceId && this.accountId == accountId) {
            return@withLock
        }

        stopLocked()

        val client = clientProvider() ?: return@withLock
        runCatching { client.auth.awaitInitialization() }
        val session = client.auth.currentSessionOrNull()
        if (session == null || session.expiresAt <= kotlin.time.Clock.System.now()) {
            // Reconcile/heartbeat still runs without Realtime. Never subscribe with an expired
            // or missing session; the SDK's reconnect path must not run without a valid socket.
            Log.w(TAG, "Realtime skipped: no valid authenticated session")
            return@withLock
        }

        this.deviceId = deviceId
        this.accountId = accountId
        connection = scope.launch {
            runConnectionLoop(client, deviceId, accountId, onSyncRequired)
        }
    }

    private suspend fun runConnectionLoop(
        client: SupabaseClient,
        deviceId: String,
        accountId: String?,
        onSyncRequired: () -> Unit,
    ) {
        var failures = 0
        while (currentCoroutineContext().isActive) {
            val session = runCatching { client.auth.currentSessionOrNull() }.getOrNull()
            if (session == null || session.expiresAt <= kotlin.time.Clock.System.now()) {
                Log.w(TAG, "Realtime paused: no valid authenticated session")
                delay(reconnectDelay(failures++))
                continue
            }

            try {
                val connectedAt = System.currentTimeMillis()
                connectOnce(client, deviceId, accountId, onSyncRequired)
                failures = if (System.currentTimeMillis() - connectedAt >= STABLE_CONNECTION_MS) {
                    0
                } else {
                    failures + 1
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Realtime subscribe timed out; retrying")
                failures++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Realtime connection lost; retrying", e)
                failures++
            }
            delay(reconnectDelay(failures))
        }
    }

    private fun reconnectDelay(failures: Int): Long =
        (RECONNECT_DELAY_MS * (1L shl failures.coerceAtMost(4))).coerceAtMost(RECONNECT_MAX_DELAY_MS)

    private suspend fun connectOnce(
        client: SupabaseClient,
        deviceId: String,
        accountId: String?,
        onSyncRequired: () -> Unit,
    ) = coroutineScope {
        val topic = "$CHANNEL_TOPIC:$deviceId:${++generation}"
        val channel = client.channel(topic)
        currentChannel = channel

        val commandsFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "device_commands"
            filter("device_id", FilterOperator.EQ, deviceId)
        }
        val settingsFlow = accountId?.let { account ->
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "account_settings"
                filter("account_id", FilterOperator.EQ, account)
            }
        }
        val deviceSettingsFlow = channel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "device_settings"
            filter("device_id", FilterOperator.EQ, deviceId)
        }

        try {
            withTimeout(SUBSCRIBE_TIMEOUT_MS) {
                channel.subscribe(blockUntilSubscribed = true)
            }
            Log.i(TAG, "Realtime channel joined")
            onSyncRequired()

            val collectors = listOfNotNull(
                launch { commandsFlow.collect { onSyncRequired() } },
                settingsFlow?.let { flow ->
                    launch { flow.collect { onSyncRequired() } }
                },
                launch { deviceSettingsFlow.collect { onSyncRequired() } },
            )
            try {
                channel.status.first { it == RealtimeChannel.Status.UNSUBSCRIBED }
            } finally {
                collectors.forEach { it.cancel() }
            }
        } finally {
            if (currentChannel === channel) currentChannel = null
            runCatching { client.realtime.removeChannel(channel) }
            Log.i(TAG, "Realtime channel removed")
        }
    }

    suspend fun stop() = mutex.withLock {
        stopLocked()
    }

    private suspend fun stopLocked() {
        val active = connection
        connection = null
        deviceId = null
        accountId = null
        active?.cancelAndJoin()

        val channel = currentChannel
        currentChannel = null
        if (channel != null) {
            clientProvider()?.let { client ->
                runCatching { client.realtime.removeChannel(channel) }
            }
        }
    }
}
