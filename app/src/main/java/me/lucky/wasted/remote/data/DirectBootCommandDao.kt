package me.lucky.wasted.remote.data

import java.util.ArrayList
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Direct-boot command journal. SQLCipher/Room requires a credential-protected native cache
 * before first unlock, so the boot agent uses this process-local journal instead. The server
 * remains the durable source of pending commands and the normal engine resumes the encrypted
 * persistent journal immediately after unlock.
 */
class DirectBootCommandDao : RemoteCommandDao {
    private val commands = LinkedHashMap<String, RemoteCommandJournalEntity>()
    private val outbox = LinkedHashMap<String, RemoteOutboxEntity>()

    override suspend fun insertCommand(command: RemoteCommandJournalEntity): Long {
        if (!commands.containsKey(command.commandId)) commands[command.commandId] = command
        return 0
    }

    override suspend fun commandById(commandId: String): RemoteCommandJournalEntity? =
        commands[commandId]

    override suspend fun commandsInStates(states: List<String>): List<RemoteCommandJournalEntity> {
        val result = ArrayList<RemoteCommandJournalEntity>()
        for (command in commands.values) {
            if (command.state in states) result.add(command)
        }
        return result
    }

    override suspend fun expireMissingPending(activeCommandIds: List<String>, now: Long): Int {
        val expired = ArrayList<String>()
        for (command in commands.values) {
            if (command.state != "queued" && command.state != "received") continue
            if (command.commandId in activeCommandIds) continue
            expired.add(command.commandId)
        }
        for (id in expired) {
            val command = commands[id] ?: continue
            commands[id] = RemoteCommandJournalEntity(
                commandId = command.commandId,
                type = command.type,
                state = "expired",
                receivedAt = command.receivedAt,
                executingAt = command.executingAt,
                terminalAt = now,
            )
        }
        return expired.size
    }

    override suspend fun updateCommandState(
        commandId: String,
        state: String,
        executingAt: Long?,
        terminalAt: Long?,
    ): Int {
        val old = commands[commandId] ?: return 0
        commands[commandId] = RemoteCommandJournalEntity(
            commandId = old.commandId,
            type = old.type,
            state = state,
            receivedAt = old.receivedAt,
            executingAt = executingAt ?: old.executingAt,
            terminalAt = terminalAt ?: old.terminalAt,
        )
        return 1
    }

    override suspend fun insertOutbox(entry: RemoteOutboxEntity): Long {
        if (!outbox.containsKey(entry.id)) outbox[entry.id] = entry
        return 0
    }

    override suspend fun dueOutbox(now: Long, limit: Int): List<RemoteOutboxEntity> {
        val result = ArrayList<RemoteOutboxEntity>()
        for (entry in outbox.values) {
            if (entry.nextAttemptAt <= now) result.add(entry)
        }
        Collections.sort(result) { left, right -> left.createdAt.compareTo(right.createdAt) }
        if (result.size <= limit) return result
        return ArrayList(result.subList(0, limit))
    }

    override suspend fun updateOutboxAttempt(id: String, attemptCount: Int, nextAttemptAt: Long) {
        val entry = outbox[id] ?: return
        outbox[id] = RemoteOutboxEntity(
            id = entry.id,
            kind = entry.kind,
            commandId = entry.commandId,
            payload = entry.payload,
            createdAt = entry.createdAt,
            attemptCount = attemptCount,
            nextAttemptAt = nextAttemptAt,
        )
    }

    override suspend fun deleteOutbox(id: String) {
        outbox.remove(id)
    }
}
