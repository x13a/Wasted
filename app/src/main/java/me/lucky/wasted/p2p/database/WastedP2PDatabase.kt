package me.lucky.wasted.p2p.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import me.lucky.wasted.p2p.models.Peer

/**
 * Room database for Wasted P2P network.
 * Stores peer information, pairing state, and message history.
 */
@Database(
    entities = [Peer::class],
    version = 1,
    exportSchema = false
)
abstract class WastedP2PDatabase : RoomDatabase() {
    abstract fun peerDao(): PeerDao
    
    companion object {
        private const val DATABASE_NAME = "wasted_p2p.db"
        private const val TAG = "P2PDatabase"
        
        @Volatile
        private var instance: WastedP2PDatabase? = null
        
        fun getInstance(context: Context): WastedP2PDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }
        
        private fun buildDatabase(context: Context): WastedP2PDatabase {
            android.util.Log.d(TAG, "Creating P2P database")
            return Room.databaseBuilder(
                context.applicationContext,
                WastedP2PDatabase::class.java,
                DATABASE_NAME
            )
                .addMigrations()  // Add migrations as schema evolves
                .build()
                .also { android.util.Log.d(TAG, "P2P database created") }
        }
    }
}
