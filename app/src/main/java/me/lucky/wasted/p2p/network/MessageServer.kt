package me.lucky.wasted.p2p.network

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import me.lucky.wasted.p2p.database.PeerDao
import me.lucky.wasted.p2p.messaging.MessageQueue
import me.lucky.wasted.p2p.models.Message
import me.lucky.wasted.p2p.models.MessageType
import me.lucky.wasted.p2p.models.Peer
import me.lucky.wasted.p2p.security.SecurityManager
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLServerSocketFactory
import kotlin.coroutines.CoroutineContext
import java.net.SocketTimeoutException

/**
 * TLS-based message server that accepts incoming peer connections on port 9876.
 * Handles handshake verification, message deserialization, ACK tracking.
 */
class MessageServer(
    private val peerDao: PeerDao,
    private val messageQueue: MessageQueue,
    private val securityManager: SecurityManager,
    private val deviceId: String,
    private val deviceName: String,
    private val onIncomingMessage: suspend (Message) -> Unit,
    private val scope: CoroutineScope
) : CoroutineScope {

    override val coroutineContext: CoroutineContext = scope.coroutineContext + SupervisorJob()

    companion object {
        private const val TAG = "MessageServer"
        private const val P2P_PORT = 9876
        private const val HANDSHAKE_TIMEOUT_MS = 5000L
        private const val MESSAGE_READ_TIMEOUT_MS = 10000L
    }

    private var serverSocket: SSLServerSocket? = null
    private var serverJob: Job? = null
    private val gson = Gson()

    /**
     * Start listening for incoming peer connections.
     * Runs in background until stop() is called.
     */
    fun start() {
        Log.d(TAG, "Starting TLS message server on port $P2P_PORT")
        serverJob = launch {
            try {
                Log.d(TAG, "Creating SSL server socket factory")
                val sslSocketFactory = securityManager.createSecureTlsServerSocketFactory()
                Log.d(TAG, "SSL factory created, binding to port $P2P_PORT")
                
                try {
                    serverSocket = sslSocketFactory.createServerSocket(P2P_PORT) as SSLServerSocket
                } catch (e: java.net.BindException) {
                    Log.e(TAG, "CRITICAL: Cannot bind to port $P2P_PORT - ${e.message}", e)
                    Log.e(TAG, "This means MessageServer cannot listen for incoming peer connections!")
                    throw e
                }
                
                serverSocket?.let { socket ->
                    Log.i(TAG, "✓✓✓ SUCCESS: Server listening on port $P2P_PORT - READY FOR CONNECTIONS ✓✓✓")
                    socket.soTimeout = 60000 // 60 second accept timeout
                    
                    while (isActive) {
                        try {
                            val clientSocket = withTimeoutOrNull(60000) {
                                socket.accept()
                            } ?: continue

                            launch {
                                handlePeerConnection(clientSocket)
                            }
                        } catch (e: SocketTimeoutException) {
                            Log.d(TAG, "Accept timeout, continuing...")
                        } catch (e: Exception) {
                            if (isActive) {
                                Log.e(TAG, "Error accepting connection: ${e.message}")
                            }
                        }
                    }
                } ?: Log.e(TAG, "ERROR: ServerSocket creation returned null!")
                
            } catch (e: Exception) {
                Log.e(TAG, "✗ Server startup FAILED: ${e.javaClass.simpleName}: ${e.message}", e)
            } finally {
                serverSocket?.close()
                Log.d(TAG, "Message server stopped")
            }
        }
    }

    /**
     * Stop listening for incoming connections.
     */
    fun stop() {
        Log.d(TAG, "Stopping message server")
        serverJob?.cancel()
        serverSocket?.close()
    }

    /**
     * Handle a single peer connection: verify handshake, process messages, send ACKs.
     */
    private suspend fun handlePeerConnection(clientSocket: java.net.Socket) {
        try {
            clientSocket.use { socket ->
                // Set socket timeout to ensure blocking I/O respects timeouts
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS.toInt()
                
                // Read handshake with timeout
                val handshakeData = withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) {
                    socket.inputStream.bufferedReader().readLine()
                }
                
                if (handshakeData == null) {
                    Log.w(TAG, "Handshake failed: timeout")
                    return@use
                }

                val parts = handshakeData.split("|")
                if (parts.size != 3) {
                    Log.e(TAG, "Handshake failed: invalid format")
                    return@use
                }

                val remotePeerId = parts[0]
                val remotePeerName = parts[1]
                val remoteCertHash = parts[2]
                val remoteIpAddress = socket.inetAddress.hostAddress ?: return@use

                val existingPeer = withContext(Dispatchers.IO) {
                    peerDao.getPeerById(remotePeerId)
                }

                val peerRecord = Peer(
                    deviceId = remotePeerId,
                    deviceName = remotePeerName,
                    ipAddress = remoteIpAddress,
                    port = P2P_PORT,
                    certificateHash = remoteCertHash,
                    pairedAt = existingPeer?.pairedAt ?: 0L,
                    lastSeen = System.currentTimeMillis(),
                    isConnected = true,
                    pinHash = existingPeer?.pinHash ?: ""
                )

                withContext(Dispatchers.IO) {
                    peerDao.insertPeer(peerRecord)
                    peerDao.updateConnectionStatus(remotePeerId, true, System.currentTimeMillis())
                }

                if (existingPeer?.isConnected != true) {
                    Log.d(TAG, "Peer handshake accepted: $remotePeerName")
                }

                // Send response handshake
                val responseHandshake = "$deviceId|$deviceName|${securityManager.getAppSignatureCertificateHash()}\n"
                socket.outputStream.bufferedWriter().apply {
                    write(responseHandshake)
                    flush()
                }
                if (existingPeer?.isConnected != true) {
                    Log.i(TAG, "Peer reachable: $remotePeerName")
                }

                // Update socket timeout for message reads (longer than handshake timeout)
                socket.soTimeout = MESSAGE_READ_TIMEOUT_MS.toInt()

                // Read and process messages from peer
                val reader = socket.inputStream.bufferedReader()
                while (isActive) {
                    try {
                        val messageLine = withTimeoutOrNull(MESSAGE_READ_TIMEOUT_MS) {
                            reader.readLine()
                        }

                        if (messageLine == null) {
                            break
                        }

                        // Deserialize message
                        val message = gson.fromJson(messageLine, Message::class.java)
                        if (message.type != MessageType.HEARTBEAT) {
                            Log.d(TAG, "Message received from $remotePeerName: ${message.type}")
                        }

                        onIncomingMessage(message)

                        // Send ACK back to sender
                        if (message.requiresAck) {
                            sendAck(socket, message.messageId, remotePeerName)
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading message from $remotePeerName: ${e.message}")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection error: ${e.message}", e)
        }
    }

    /**
     * Send ACK message back to peer.
     */
    private suspend fun sendAck(
        socket: java.net.Socket,
        messageId: String,
        peerName: String
    ) {
        try {
            val ackMessage = Message(
                messageId = messageId,
                fromDeviceId = deviceId,
                toDeviceId = messageId.substringBefore("_"),
                type = MessageType.HEARTBEAT,
                payload = "ACK",
                timestamp = System.currentTimeMillis(),
                requiresAck = false,
                isAcked = true
            )
            val ackJson = gson.toJson(ackMessage) + "\n"
            socket.outputStream.bufferedWriter().apply {
                write(ackJson)
                flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ACK: ${e.message}")
        }
    }
}
