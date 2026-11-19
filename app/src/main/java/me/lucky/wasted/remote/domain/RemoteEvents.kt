package me.lucky.wasted.remote.domain

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** A user-facing remote event; notification delivery and gating happen in the Android layer. */
data class RemoteEvent(
    val kind: Kind,
    val key: String,
    val title: String,
    val message: String,
) {
    enum class Kind {
        SETTINGS_CHANGED,
        SYNC_SUCCEEDED,
        RESET_TIMER_APPLIED,
        LOCK_APPLIED,
        FORCE_RESET_INITIATED,
        DEBUG_BLOCKED,
        DEBUG_MODE_CHANGED,
        COMMAND_FAILED,
    }
}

fun interface RemoteEventSink {
    fun emit(event: RemoteEvent)
}

/** In-process events for an open Remote Management screen; no persistence or network. */
object RemoteEventBus {
    private val mutableEvents = MutableSharedFlow<RemoteEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events = mutableEvents.asSharedFlow()

    fun publish(event: RemoteEvent) {
        mutableEvents.tryEmit(event)
    }
}

/** In-process clock of successful syncs; the persisted value remains in RemoteCredentialStore. */
object RemoteSyncStatus {
    private val mutableLastSyncAt = MutableStateFlow(0L)
    val lastSyncAt: StateFlow<Long> = mutableLastSyncAt.asStateFlow()

    fun publish(lastSyncAt: Long) {
        mutableLastSyncAt.value = lastSyncAt
    }
}
