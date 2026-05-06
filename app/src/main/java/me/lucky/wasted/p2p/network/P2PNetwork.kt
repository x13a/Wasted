package me.lucky.wasted.p2p.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.lucky.wasted.p2p.database.PeerDao
import me.lucky.wasted.p2p.messaging.MessageQueue
import me.lucky.wasted.p2p.models.Message
import me.lucky.wasted.p2p.models.MessageType
import me.lucky.wasted.p2p.models.Peer
import me.lucky.wasted.p2p.security.SecurityManager
import java.io.IOException

/**
 * Orchestrates all P2P network operations.
 * Manages device discovery, connection establishment, message delivery, and settings sync.
 * 
 * Log pattern:
 * DEBUG: "Broadcasting to [N] peers via TLS"
 * DEBUG: "ACK received from [peer-name]"
 * ERROR: "Sync failed for peer [name]"
 * INFO: "Settings synced (latency: [ms])"
 */
class P2PNetwork(
    private val context: Context,
    private val peerDao: PeerDao
) {
    companion object {
        private const val TAG = "P2PNetwork"
        private const val DISCOVERY_INTERVAL_MS = 10000L
        private const val HEARTBEAT_INTERVAL_MS = 5000L
        private const val MESSAGE_DELIVERY_INTERVAL_MS = 1000L
        private const val SERVER_STARTUP_GRACE_MS = 1000L
        private const val COMMAND_RETRY_DELAY_MS = 750L
        private const val COMMAND_MAX_ATTEMPTS = 3
    }
    
    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val securityManager = SecurityManager(context)
    private val discovery = DeviceDiscovery(context, peerDao, securityManager)
    private val messageQueue = MessageQueue()
    var onIncomingMessage: suspend (Message) -> Unit = {}

    private val messageServer = MessageServer(
        peerDao = peerDao,
        messageQueue = messageQueue,
        securityManager = securityManager,
        deviceId = getDeviceId(),
        deviceName = getDeviceName(),
        onIncomingMessage = { message -> onIncomingMessage(message) },
        scope = scope
    )
    
    // Observable peer connections
    private val _connectedPeers = MutableStateFlow<List<Peer>>(emptyList())
    val connectedPeers: StateFlow<List<Peer>> = _connectedPeers
    
    private val _pairingState = MutableStateFlow<String>("Idle")
    val pairingState: StateFlow<String> = _pairingState
    
    private var discoveryJob: Job? = null
    private var heartbeatJob: Job? = null
    private var messageDeliveryJob: Job? = null
    private var peerFlowJob: Job? = null
    private var startupJob: Job? = null
    private var isInitialized = false

    /**
     * Initialize P2P network (start discovery, heartbeat, message delivery).
     */
    fun initialize() {
        if (isInitialized) {
            return
        }
        isInitialized = true
        Log.d(TAG, "Initializing P2P network")

        peerFlowJob = scope.launch {
            peerDao.getConnectedPeersFlow().collectLatest { peers ->
                _connectedPeers.value = peers
            }
        }

        messageServer.start()  // Start listening for peer connections

        startupJob = scope.launch {
            delay(SERVER_STARTUP_GRACE_MS)
            startDiscovery()
            startHeartbeat()
            startMessageDelivery()
            Log.i(TAG, "P2P network initialized")
        }

        Log.d(TAG, "Waiting for message server startup before discovery")
    }
    
    /**
     * Start periodic device discovery.
     */
    private fun startDiscovery() {
        discoveryJob = scope.launch {
            while (isActive) {
                try {
                    if (discovery.isWifiConnected()) {
                        Log.d(TAG, "Starting peer discovery cycle")
                        val discoveredPeers = discovery.discoverPeers()
                        if (discoveredPeers.isNotEmpty()) {
                            Log.i(TAG, "Discovery found ${discoveredPeers.size} reachable peer(s)")
                        }
                        
                        // Save new peers to database (but don't overwrite paired flag)
                        discoveredPeers.forEach { discovered ->
                            val existingPeer = peerDao.getPeerById(discovered.deviceId)
                            if (existingPeer == null) {
                                Log.i(TAG, "Discovered peer: ${discovered.deviceName}")
                                peerDao.insertPeer(discovered)
                            } else {
                                peerDao.insertPeer(
                                    existingPeer.copy(
                                        ipAddress = discovered.ipAddress,
                                        port = discovered.port,
                                        certificateHash = discovered.certificateHash,
                                        lastSeen = System.currentTimeMillis(),
                                        isConnected = true
                                    )
                                )
                            }
                        }
                    } else {
                        Log.d(TAG, "Wi-Fi not connected, skipping discovery")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Discovery error: ${e.message}", e)
                }
                
                delay(DISCOVERY_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Start periodic heartbeat to all connected peers.
     */
    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive) {
                try {
                    val connectedPeers = peerDao.getConnectedPeers().filter { it.pairedAt > 0L }
                    if (connectedPeers.isNotEmpty()) {
                        Log.d(TAG, "Sending heartbeat to ${connectedPeers.size} peer(s)")
                    }
                    
                    connectedPeers.forEach { peer ->
                        try {
                            val heartbeat = Message(
                                fromDeviceId = getDeviceId(),
                                toDeviceId = peer.deviceId,
                                type = MessageType.HEARTBEAT,
                                payload = "{\"timestamp\":${System.currentTimeMillis()}}",
                                requiresAck = false
                            )
                            
                            sendToPeer(peer, heartbeat)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Heartbeat failed for ${peer.deviceName}: ${e.message}")
                            // Mark peer as disconnected if heartbeat fails
                            peerDao.updateConnectionStatus(peer.deviceId, false, System.currentTimeMillis())
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Heartbeat cycle error: ${e.message}", e)
                }
                
                delay(HEARTBEAT_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Start message delivery worker.
     */
    private fun startMessageDelivery() {
        messageDeliveryJob = scope.launch {
            while (isActive) {
                try {
                    messageQueue.checkAndRetryFailedMessages()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Message delivery error: ${e.message}", e)
                }
                
                delay(MESSAGE_DELIVERY_INTERVAL_MS)
            }
        }
    }
    
    /**
     * Send message to peer.
     */
    suspend fun sendToPeer(peer: Peer, message: Message): Boolean {
        return withContext(Dispatchers.IO) {
            var socket: javax.net.ssl.SSLSocket? = null
            try {
                if (message.type != MessageType.HEARTBEAT) {
                    Log.d(TAG, "Sending ${message.type} to ${peer.deviceName} (${peer.ipAddress}:${peer.port})")
                }
                val startTime = System.currentTimeMillis()
                val socketFactory = securityManager.createSecureTlsClientSocketFactory()
                socket = socketFactory.createSocket(peer.ipAddress, peer.port) as javax.net.ssl.SSLSocket
                socket.soTimeout = 10000  // Match server's MESSAGE_READ_TIMEOUT_MS
                socket.startHandshake()

                val writer = socket.outputStream.bufferedWriter()
                val reader = socket.inputStream.bufferedReader()

                val handshake = "${getDeviceId()}|${getDeviceName()}|${securityManager.getAppSignatureCertificateHash()}\n"
                writer.write(handshake)
                writer.flush()

                val handshakeResponse = reader.readLine()
                if (handshakeResponse.isNullOrBlank()) {
                    throw IOException("Missing handshake response from ${peer.deviceName}")
                }

                val payload = com.google.gson.Gson().toJson(message) + "\n"
                writer.write(payload)
                writer.flush()

                if (message.requiresAck) {
                    val ack = reader.readLine()
                    if (ack.isNullOrBlank()) {
                        throw IOException("Missing ACK from ${peer.deviceName}")
                    }
                }

                val latency = System.currentTimeMillis() - startTime
                if (message.type != MessageType.HEARTBEAT) {
                    Log.i(TAG, "${message.type} delivered to ${peer.deviceName} (${latency}ms)")
                }
                messageQueue.acknowledgeMessage(message.messageId)
                messageQueue.removeFromQueue(message.messageId)
                peerDao.updateConnectionStatus(peer.deviceId, true, System.currentTimeMillis())
                true
            } catch (e: IOException) {
                Log.e(TAG, "Network error sending to ${peer.deviceName}: ${e.message}")
                messageQueue.failMessage(message.messageId, e.message ?: "IO error")
                peerDao.updateConnectionStatus(peer.deviceId, false, System.currentTimeMillis())
                false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message to ${peer.deviceName}: ${e.message}", e)
                messageQueue.failMessage(message.messageId, e.message ?: "Unknown error")
                peerDao.updateConnectionStatus(peer.deviceId, false, System.currentTimeMillis())
                false
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    suspend fun sendToPeerWithRetry(
        peer: Peer,
        message: Message,
        maxAttempts: Int = COMMAND_MAX_ATTEMPTS,
    ): Boolean {
        var attempt = 1
        while (attempt <= maxAttempts) {
            val success = sendToPeer(peer, message)
            if (success) {
                return true
            }

            if (attempt < maxAttempts && message.type != MessageType.HEARTBEAT) {
                Log.w(TAG, "Retrying ${message.type} to ${peer.deviceName} (attempt ${attempt + 1}/$maxAttempts)")
                delay(COMMAND_RETRY_DELAY_MS)
            }
            attempt += 1
        }
        return false
    }
    
    /**
     * Broadcast message to all connected peers.
     */
    suspend fun broadcastToPeers(message: Message) {
        withContext(Dispatchers.Default) {
            val connectedPeers = peerDao.getConnectedPeers().filter { it.pairedAt > 0L }
            
            if (connectedPeers.isEmpty()) {
                Log.w(TAG, "No paired peers to broadcast to")
                return@withContext
            }
            
            Log.d(TAG, "Broadcasting to ${connectedPeers.size} peers via TLS")
            val failedPeers = coroutineScope {
                connectedPeers.map { peer ->
                    async {
                        peer.deviceName.takeUnless { sendToPeerWithRetry(peer, message) }
                    }
                }.awaitAll().filterNotNull()
            }

            if (failedPeers.isEmpty()) {
                Log.i(TAG, "Broadcast delivered to ${connectedPeers.size} peer(s)")
            } else {
                Log.w(TAG, "Broadcast missed ${failedPeers.size} peer(s): ${failedPeers.joinToString()}")
            }
        }
    }
    
    /**
     * Get unique device identifier.
     */
    private fun getDeviceId(): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    }

    /**
     * Get device name.
     */
    private fun getDeviceName(): String {
        return android.os.Build.MODEL ?: "Unknown Device"
    }
    
    /**
     * Shutdown P2P network.
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down P2P network")
        messageServer.stop()
        startupJob?.cancel()
        discoveryJob?.cancel()
        heartbeatJob?.cancel()
        messageDeliveryJob?.cancel()
        peerFlowJob?.cancel()
        startupJob = null
        discoveryJob = null
        heartbeatJob = null
        messageDeliveryJob = null
        peerFlowJob = null
        discovery.stopDiscovery()
        messageQueue.shutdown()
        scope.cancel()
        // Recreate scope so the next initialize() call can launch new coroutines
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        isInitialized = false
        Log.i(TAG, "P2P network shut down")
    }
}
