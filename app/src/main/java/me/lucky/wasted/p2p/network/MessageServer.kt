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
        Log.d(TAG, "Starting TLS message server")
        serverJob = launch {
            try {
                Log.d(TAG, "Creating SSL server socket factory")
                val sslSocketFactory = securityManager.createSecureTlsServerSocketFactory()
                Log.d(TAG, "SSL factory created")
                
                try {
                    serverSocket = sslSocketFactory.createServerSocket(P2P_PORT) as SSLServerSocket
                } catch (e: java.net.BindException) {
                    Log.e(TAG, "CRITICAL: Cannot bind message server")
                    Log.e(TAG, "This means MessageServer cannot listen for incoming peer connections!")
                    throw e
                }
                
                serverSocket?.let { socket ->
                    socket.needClientAuth = true
                    Log.i(TAG, "Message server listening")
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
                                Log.e(TAG, "Error accepting connection: ${e.javaClass.simpleName}")
                            }
                        }
                    }
                } ?: Log.e(TAG, "ERROR: ServerSocket creation returned null!")
                
            } catch (e: Exception) {
                Log.e(TAG, "Server startup failed: ${e.javaClass.simpleName}")
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
        val sslSocket = clientSocket as? javax.net.ssl.SSLSocket ?: run {
            clientSocket.close()
            return
        }
        try {
            sslSocket.use { socket ->
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS.toInt()
                withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { socket.startHandshake() }
                    ?: return@use

                val peerCertificate = socket.session.peerCertificates
                    .firstOrNull() as? java.security.cert.X509Certificate
                    ?: run {
                        Log.w(TAG, "Handshake rejected: no client certificate")
                        return@use
                    }
                val actualCertHash = securityManager.certificateHash(peerCertificate)

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
                val claimedCertHash = parts[2]
                if (!claimedCertHash.equals(actualCertHash, ignoreCase = true)) {
                    Log.w(TAG, "Handshake rejected: certificate hash mismatch")
                    return@use
                }
                if (remotePeerId.isBlank() || remotePeerName.length !in 1..80) {
                    Log.w(TAG, "Handshake rejected: invalid peer identity")
                    return@use
                }
                val remoteIpAddress = socket.inetAddress.hostAddress ?: return@use

                val existingPeer = withContext(Dispatchers.IO) {
                    peerDao.getPeerById(remotePeerId)
                }

                if (existingPeer != null &&
                    existingPeer.pairedAt > 0L &&
                    !existingPeer.certificateHash.equals(actualCertHash, ignoreCase = true)
                ) {
                    Log.w(TAG, "Handshake rejected: paired peer certificate changed")
                    return@use
                }

                val peerRecord = Peer(
                    deviceId = remotePeerId,
                    deviceName = remotePeerName,
                    ipAddress = remoteIpAddress,
                    port = P2P_PORT,
                    certificateHash = actualCertHash,
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
                    Log.d(TAG, "Peer handshake accepted")
                }

                // Send response handshake
                val responseHandshake = "$deviceId|$deviceName|${securityManager.getTlsCertificateHash()}\n"
                socket.outputStream.bufferedWriter().apply {
                    write(responseHandshake)
                    flush()
                }
                if (existingPeer?.isConnected != true) {
                    Log.i(TAG, "Peer reachable")
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

                        val message = gson.fromJson(messageLine, Message::class.java)
                        if (message.fromDeviceId != remotePeerId ||
                            message.toDeviceId != deviceId
                        ) {
                            Log.w(TAG, "Message rejected: identity does not match the TLS peer")
                            break
                        }
                        if (message.type != MessageType.HEARTBEAT) {
                            Log.d(TAG, "Message received")
                        }

                        onIncomingMessage(message)

                        // Send ACK back to sender
                        if (message.requiresAck) {
                            sendAck(socket, message.messageId, remotePeerId)
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading peer message: ${e.javaClass.simpleName}")
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection error: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Send ACK message back to peer.
     */
    private suspend fun sendAck(
        socket: java.net.Socket,
        messageId: String,
        remotePeerId: String,
    ) {
        try {
            val ackMessage = Message(
                messageId = messageId,
                fromDeviceId = deviceId,
                toDeviceId = remotePeerId,
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
            Log.e(TAG, "Failed to send ACK: ${e.javaClass.simpleName}")
        }
    }
}
