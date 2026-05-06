package me.lucky.wasted.p2p.messaging

import android.util.Log
import kotlinx.coroutines.*
import me.lucky.wasted.p2p.models.Message
import me.lucky.wasted.p2p.models.MessageType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Manages message queue for P2P communication.
 * Handles queuing, delivery, retries, and ACK tracking.
 * 
 * Log pattern:
 * DEBUG: "Broadcasting to [N] peers via TLS"
 * DEBUG: "ACK received from [peer-name]"
 * ERROR: "Sync failed for peer [name]"
 * INFO: "Settings synced (latency: [ms])"
 */
class MessageQueue {
    companion object {
        private const val TAG = "MessageQueue"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1000L
        private const val ACK_TIMEOUT_MS = 5000L
    }
    
    private val messageQueue = CopyOnWriteArrayList<Message>()
    private val pendingAcks = ConcurrentHashMap<String, Long>() // messageId -> expiryTime
    private val messageCallbacks = ConcurrentHashMap<String, MessageCallback>() // messageId -> callback
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * Enqueue a message for delivery.
     */
    fun enqueueMessage(message: Message, callback: MessageCallback? = null) {
        if (message.type != MessageType.HEARTBEAT) {
            Log.d(TAG, "Queueing ${message.type} ${message.messageId} to ${message.toDeviceId}")
        }
        
        messageQueue.add(message)
        if (callback != null) {
            messageCallbacks[message.messageId] = callback
        }
        
        if (message.requiresAck) {
            pendingAcks[message.messageId] = System.currentTimeMillis() + ACK_TIMEOUT_MS
        }
    }
    
    /**
     * Mark message as acknowledged by peer.
     */
    fun acknowledgeMessage(messageId: String) {
        pendingAcks.remove(messageId)
        messageCallbacks[messageId]?.onAcknowledged(messageId)
        messageCallbacks.remove(messageId)
    }
    
    /**
     * Mark message as failed.
     */
    fun failMessage(messageId: String, reason: String) {
        Log.e(TAG, "Message $messageId failed: $reason")
        
        pendingAcks.remove(messageId)
        messageCallbacks[messageId]?.onFailed(messageId, reason)
        messageCallbacks.remove(messageId)
    }
    
    /**
     * Broadcast message to all connected peers.
     * Actual TLS delivery is handled by P2PNetwork.broadcastToPeers().
     * This method just logs — do not enqueue here; callers should use enqueueMessage() directly.
     */
    suspend fun broadcastMessage(message: Message, connectedPeerCount: Int) {
        if (message.type != MessageType.HEARTBEAT) {
            Log.d(TAG, "Broadcasting ${message.type} ${message.messageId} to $connectedPeerCount peer(s)")
        }
    }
    
    /**
     * Get queued messages for a specific peer.
     */
    fun getQueuedMessagesForPeer(peerId: String): List<Message> {
        return messageQueue.filter { it.toDeviceId == peerId }
    }
    
    /**
     * Remove delivered message from queue.
     */
    fun removeFromQueue(messageId: String) {
        messageQueue.removeAll { it.messageId == messageId }
    }
    
    /**
     * Check for expired ACK timeouts and retry.
     */
    suspend fun checkAndRetryFailedMessages() {
        val now = System.currentTimeMillis()
        val expiredAcks = pendingAcks.filter { (_, expiryTime) -> now > expiryTime }
        
        expiredAcks.forEach { (messageId, _) ->
            Log.w(TAG, "ACK timeout for message $messageId, retrying...")
            pendingAcks.remove(messageId)
            // Actual retry logic happens in P2PNetwork
        }
    }
    
    /**
     * Get queue statistics.
     */
    fun getQueueStats(): QueueStats {
        return QueueStats(
            queuedMessages = messageQueue.size,
            pendingAcks = pendingAcks.size,
            callbacks = messageCallbacks.size
        )
    }
    
    /**
     * Clear all queued messages (e.g., on network loss).
     */
    fun clearQueue() {
        messageQueue.clear()
        pendingAcks.clear()
        messageCallbacks.clear()
    }
    
    /**
     * Shutdown message queue.
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down message queue")
        scope.cancel()
        clearQueue()
    }
}

/**
 * Callback for message delivery results.
 */
interface MessageCallback {
    fun onAcknowledged(messageId: String)
    fun onFailed(messageId: String, reason: String)
}

/**
 * Queue statistics for monitoring.
 */
data class QueueStats(
    val queuedMessages: Int,
    val pendingAcks: Int,
    val callbacks: Int
)
