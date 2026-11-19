package me.lucky.wasted.p2p.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import me.lucky.wasted.p2p.models.Peer
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File

/**
 * SQLCipher-encrypted Room database for P2P peer state. Existing plaintext rows from the previous
 * database are imported once and then the legacy file is removed.
 */
@Database(
    entities = [Peer::class],
    version = 1,
    exportSchema = false
)
abstract class WastedP2PDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao

    companion object {
        private const val DATABASE_NAME = "wasted_p2p_secure.db"
        private const val LEGACY_DATABASE_NAME = "wasted_p2p.db"
        private const val TAG = "P2PDatabase"

        @Volatile
        private var instance: WastedP2PDatabase? = null

        fun getInstance(context: Context): WastedP2PDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }

        /** Explicitly resets local P2P pairing storage after user confirmation. */
        fun resetLocalState(context: Context): Boolean = synchronized(this) {
            runCatching {
                instance?.close()
                instance = null
                val secure = context.getDatabasePath(DATABASE_NAME)
                val legacy = context.getDatabasePath(LEGACY_DATABASE_NAME)
                val secureDeleted = deleteDatabaseFamily(secure)
                val legacyDeleted = deleteDatabaseFamily(legacy)
                if (!secureDeleted || !legacyDeleted) return@runCatching false
                val keyDeleted = P2PDatabaseKeyStore.new(context.applicationContext).reset()
                val revocationsCleared = P2PRevocationStore.clearLocal(context.applicationContext)
                keyDeleted && revocationsCleared
            }.getOrDefault(false)
        }

        private fun buildDatabase(context: Context): WastedP2PDatabase {
            Log.d(TAG, "Creating encrypted P2P database")
            var candidate: WastedP2PDatabase? = null
            try {
                System.loadLibrary("sqlcipher")
                val keyStore = P2PDatabaseKeyStore.new(context.applicationContext)
                val databaseFile = context.getDatabasePath(DATABASE_NAME)
                val passphrase = keyStore.passphraseOrNull(databaseExists = databaseFile.exists())
                    ?: throw P2PDatabaseUnavailableException(
                        "P2P database key is unavailable; refusing to rotate an existing encrypted database",
                    )
                val database = Room.databaseBuilder(
                    context.applicationContext,
                    WastedP2PDatabase::class.java,
                    DATABASE_NAME
                )
                    .openHelperFactory(SupportOpenHelperFactory(passphrase))
                    .addCallback(object : Callback() {
                        override fun onOpen(db: SupportSQLiteDatabase) {
                            importLegacyPeers(context.applicationContext, db, keyStore)
                        }
                    })
                    .build()
                candidate = database
                database.openHelper.writableDatabase
                Log.d(TAG, "Encrypted P2P database created")
                return database
            } catch (e: P2PDatabaseUnavailableException) {
                runCatching { candidate?.close() }
                throw e
            } catch (e: LinkageError) {
                runCatching { candidate?.close() }
                throw P2PDatabaseUnavailableException("P2P database linkage failed", e)
            } catch (e: Exception) {
                runCatching { candidate?.close() }
                throw P2PDatabaseUnavailableException("P2P database could not be initialized", e)
            }
        }

        private fun importLegacyPeers(
            context: Context,
            db: SupportSQLiteDatabase,
            keyStore: P2PDatabaseKeyStore,
        ) {
            val legacy = context.getDatabasePath(LEGACY_DATABASE_NAME)
            if (keyStore.isLegacyImportComplete()) {
                if (!deleteDatabaseFamily(legacy)) {
                    Log.w(TAG, "Residual legacy P2P database artifacts could not be removed; will retry")
                }
                return
            }
            if (!legacy.exists()) {
                if (!deleteDatabaseFamily(legacy)) {
                    Log.w(TAG, "Legacy P2P sidecars could not be removed; will retry")
                    return
                }
                if (!keyStore.markLegacyImportComplete()) {
                    Log.w(TAG, "Legacy import marker could not be persisted")
                }
                return
            }

            try {
                SQLiteDatabase.openDatabase(
                    legacy.path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                ).use { old ->
                    val cursor: android.database.Cursor = old.rawQuery(
                        "SELECT deviceId, deviceName, ipAddress, port, certificateHash, " +
                            "pairedAt, lastSeen, isConnected, pinHash FROM peers",
                        null,
                    )
                    cursor.use { c ->
                        while (c.moveToNext()) {
                            db.execSQL(
                                "INSERT OR IGNORE INTO peers " +
                                    "(deviceId, deviceName, ipAddress, port, certificateHash, " +
                                    "pairedAt, lastSeen, isConnected, pinHash) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                                arrayOf<Any?>(
                                    c.getString(0),
                                    c.getString(1),
                                    c.getString(2),
                                    c.getInt(3),
                                    // Legacy hashes were APK signatures, not TLS pins. Clear them
                                    // so the peer must go through a fresh pinned pairing.
                                    "",
                                    0L,
                                    c.getLong(6),
                                    0,
                                    "",
                                ),
                            )
                        }
                    }
                }
                if (!deleteDatabaseFamily(legacy)) {
                    Log.w(TAG, "Legacy P2P database artifacts could not be removed; will retry")
                    return
                }
                if (!keyStore.markLegacyImportComplete()) {
                    Log.w(TAG, "Legacy import completed but the marker could not be persisted")
                    return
                }
                Log.i(TAG, "Legacy plaintext P2P database imported and removed")
            } catch (e: Exception) {
                Log.e(TAG, "Legacy P2P database import failed; keeping it for retry")
            }
        }

        internal fun deleteDatabaseFamily(databaseFile: File): Boolean {
            val artifacts = listOf(
                databaseFile,
                File(databaseFile.path + "-wal"),
                File(databaseFile.path + "-shm"),
                File(databaseFile.path + "-journal"),
            )
            artifacts.forEach { artifact ->
                if (artifact.exists()) artifact.delete()
            }
            return artifacts.none { it.exists() }
        }
    }
}
