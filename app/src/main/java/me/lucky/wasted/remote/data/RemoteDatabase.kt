package me.lucky.wasted.remote.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Entity(tableName = "remote_command_journal")
data class RemoteCommandJournalEntity(
    @PrimaryKey
    @ColumnInfo(name = "command_id") val commandId: String,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "state") val state: String,
    @ColumnInfo(name = "received_at") val receivedAt: Long,
    @ColumnInfo(name = "executing_at") val executingAt: Long?,
    @ColumnInfo(name = "terminal_at") val terminalAt: Long?,
)

@Entity(tableName = "remote_outbox")
data class RemoteOutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "kind") val kind: String,
    @ColumnInfo(name = "command_id") val commandId: String?,
    @ColumnInfo(name = "payload") val payload: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "attempt_count") val attemptCount: Int,
    @ColumnInfo(name = "next_attempt_at") val nextAttemptAt: Long,
)

/**
 * Separate SQLCipher-encrypted database for remote journal/outbox state.
 * Kept separate from the encrypted P2P database; the passphrase is wrapped
 * by Tink/Android Keystore.
 */
@Database(
    entities = [RemoteCommandJournalEntity::class, RemoteOutboxEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class RemoteDatabase : RoomDatabase() {
    abstract fun remoteCommandDao(): RemoteCommandDao

    companion object {
        private const val DB_NAME = "remote_secure.db"

        @Volatile
        private var instance: RemoteDatabase? = null

        private fun directBootContext(ctx: Context): Context =
            DirectBootStorage.context(ctx)

        fun getInstance(ctx: Context): RemoteDatabase = instance ?: synchronized(this) {
            instance ?: build(directBootContext(ctx)).also { instance = it }
        }

        /** Reset Remote Enrollment: drops the encrypted journal/outbox database entirely. */
        fun clear(ctx: Context) {
            synchronized(this) {
                instance?.close()
                instance = null
            }
            directBootContext(ctx).deleteDatabase(DB_NAME)
        }

        private fun build(ctx: Context): RemoteDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = RemoteCredentialStore.new(ctx).databasePassphrase
            return Room.databaseBuilder(ctx, RemoteDatabase::class.java, DB_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .build()
        }
    }
}
