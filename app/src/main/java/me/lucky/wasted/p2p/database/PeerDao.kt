package me.lucky.wasted.p2p.database

import androidx.room.*
import me.lucky.wasted.p2p.models.Peer
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Peer management.
 * Handles all database operations for peer storage, pairing, and lookups.
 */
@Dao
interface PeerDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPeer(peer: Peer)
    
    @Delete
    suspend fun deletePeer(peer: Peer)
    
    @Query("SELECT * FROM peers WHERE deviceId = :deviceId")
    suspend fun getPeerById(deviceId: String): Peer?
    
    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun getAllPeersFlow(): Flow<List<Peer>>
    
    @Query("SELECT * FROM peers WHERE isConnected = 1")
    fun getConnectedPeersFlow(): Flow<List<Peer>>
    
    @Query("SELECT * FROM peers WHERE isConnected = 1 ORDER BY lastSeen DESC")
    suspend fun getConnectedPeers(): List<Peer>

    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    suspend fun getAllPeers(): List<Peer>
    
    @Query("UPDATE peers SET isConnected = :isConnected, lastSeen = :lastSeen WHERE deviceId = :deviceId")
    suspend fun updateConnectionStatus(deviceId: String, isConnected: Boolean, lastSeen: Long)
    
    @Query("UPDATE peers SET lastSeen = :lastSeen WHERE deviceId = :deviceId")
    suspend fun updateLastSeen(deviceId: String, lastSeen: Long)
    
    @Query("DELETE FROM peers WHERE deviceId = :deviceId")
    suspend fun unpairDevice(deviceId: String)
    
    @Query("SELECT COUNT(*) FROM peers")
    suspend fun getPeerCount(): Int
    
    @Query("SELECT * FROM peers WHERE deviceName LIKE '%' || :searchQuery || '%'")
    fun searchPeers(searchQuery: String): Flow<List<Peer>>

    @Query("SELECT COUNT(*) FROM peers WHERE pairedAt > 0")
    suspend fun getActivePairCount(): Int
}
