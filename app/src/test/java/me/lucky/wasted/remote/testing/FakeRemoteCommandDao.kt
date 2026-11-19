package me.lucky.wasted.remote.testing

import me.lucky.wasted.remote.data.RemoteCommandDao
import me.lucky.wasted.remote.data.RemoteCommandJournalEntity
import me.lucky.wasted.remote.data.RemoteOutboxEntity

class FakeRemoteCommandDao : RemoteCommandDao {
    val commands = LinkedHashMap<String, RemoteCommandJournalEntity>()
    val outbox = LinkedHashMap<String, RemoteOutboxEntity>()

    override suspend fun insertCommand(command: RemoteCommandJournalEntity): Long =
        if (commands.containsKey(command.commandId)) {
            -1
        } else {
            commands[command.commandId] = command
            1
        }

    override suspend fun commandById(commandId: String): RemoteCommandJournalEntity? =
        commands[commandId]

    override suspend fun commandsInStates(states: List<String>): List<RemoteCommandJournalEntity> =
        commands.values.filter { it.state in states }

    override suspend fun expireMissingPending(activeCommandIds: List<String>, now: Long): Int {
        var changed = 0
        for ((id, command) in commands.entries.toList()) {
            if (command.state !in listOf("queued", "received") || id in activeCommandIds) continue
            commands[id] = command.copy(state = "expired", terminalAt = now)
            changed++
        }
        return changed
    }

    override suspend fun updateCommandState(
        commandId: String,
        state: String,
        executingAt: Long?,
        terminalAt: Long?,
    ): Int {
        val existing = commands[commandId] ?: return 0
        commands[commandId] = existing.copy(
            state = state,
            executingAt = executingAt,
            terminalAt = terminalAt,
        )
        return 1
    }

    override suspend fun insertOutbox(entry: RemoteOutboxEntity): Long =
        if (outbox.containsKey(entry.id)) {
            -1
        } else {
            outbox[entry.id] = entry
            1
        }

    override suspend fun dueOutbox(now: Long, limit: Int): List<RemoteOutboxEntity> =
        outbox.values
            .filter { it.nextAttemptAt <= now }
            .sortedBy { it.createdAt }
            .take(limit)

    override suspend fun updateOutboxAttempt(id: String, attemptCount: Int, nextAttemptAt: Long) {
        outbox[id]?.let { outbox[id] = it.copy(attemptCount = attemptCount, nextAttemptAt = nextAttemptAt) }
    }

    override suspend fun deleteOutbox(id: String) {
        outbox.remove(id)
    }
}
