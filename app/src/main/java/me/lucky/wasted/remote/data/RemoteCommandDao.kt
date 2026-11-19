package me.lucky.wasted.remote.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RemoteCommandDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCommand(command: RemoteCommandJournalEntity): Long

    @Query("SELECT * FROM remote_command_journal WHERE command_id = :commandId")
    suspend fun commandById(commandId: String): RemoteCommandJournalEntity?

    @Query("SELECT * FROM remote_command_journal WHERE state IN (:states)")
    suspend fun commandsInStates(states: List<String>): List<RemoteCommandJournalEntity>

    @Query(
        "UPDATE remote_command_journal SET state = 'expired', terminal_at = :now " +
            "WHERE state IN ('queued', 'received') AND command_id NOT IN (:activeCommandIds)",
    )
    suspend fun expireMissingPending(activeCommandIds: List<String>, now: Long): Int

    @Query(
        "UPDATE remote_command_journal SET state = :state, executing_at = :executingAt, " +
            "terminal_at = :terminalAt WHERE command_id = :commandId",
    )
    suspend fun updateCommandState(
        commandId: String,
        state: String,
        executingAt: Long?,
        terminalAt: Long?,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutbox(entry: RemoteOutboxEntity): Long

    @Query(
        "SELECT * FROM remote_outbox WHERE next_attempt_at <= :now " +
            "ORDER BY created_at LIMIT :limit",
    )
    suspend fun dueOutbox(now: Long, limit: Int): List<RemoteOutboxEntity>

    @Query(
        "UPDATE remote_outbox SET attempt_count = :attemptCount, " +
            "next_attempt_at = :nextAttemptAt WHERE id = :id",
    )
    suspend fun updateOutboxAttempt(id: String, attemptCount: Int, nextAttemptAt: Long)

    @Query("DELETE FROM remote_outbox WHERE id = :id")
    suspend fun deleteOutbox(id: String)
}
