package me.lucky.wasted.p2p.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import me.lucky.wasted.p2p.database.PeerDao
import me.lucky.wasted.p2p.models.Peer
import me.lucky.wasted.p2p.security.SecurityManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import javax.net.ssl.SSLSocket

/**
 * Discovers peer devices on local Wi-Fi network.
 * Scans local subnet for services listening on P2P port.
 * 
 * Log pattern:
 * DEBUG: "mDNS discovery started"
 * DEBUG: "Peer found: [device-name]"
 * DEBUG: "Scanning network: [subnet]"
 * ERROR: "[device-ip]:[port] - connection failed"
 */
class DeviceDiscovery(
    private val context: Context,
    private val peerDao: PeerDao,
    private val securityManager: SecurityManager
) {
    companion object {
        private const val TAG = "DeviceDiscovery"
        private const val P2P_PORT = 9876  // P2P communication port
        private const val CONNECTION_TIMEOUT_MS = 1000
        private const val MAX_PARALLEL_SCANS = 16
    }
    
    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    /**
     * Start discovering peers on local network.
     * Returns list of discovered peer candidates.
     */
    suspend fun discoverPeers(): List<Peer> {
        return withContext(Dispatchers.Default) {
            Log.d(TAG, "mDNS discovery started")
            
            val discoveredPeers = mutableListOf<Peer>()
            val localSubnet = getLocalNetworkSubnet()
            
            if (localSubnet != null) {
                Log.d(TAG, "Scanning network: $localSubnet")
                discoveredPeers.addAll(scanSubnet(localSubnet))
            } else {
                Log.w(TAG, "Could not determine local network subnet")
            }
            
            Log.d(TAG, "Discovery complete: ${discoveredPeers.size} peers found")
            discoveredPeers
        }
    }
    
    /**
     * Scan subnet for listening P2P services.
     */
    private suspend fun scanSubnet(subnet: String): List<Peer> {
        return withContext(Dispatchers.Default) {
            val results = mutableListOf<Peer>()
            val tasks = mutableListOf<Deferred<Peer?>>()
            val localIpAddress = getLocalIpAddress()
            val localDeviceId = getDeviceId()
            val localDeviceName = getDeviceName()
            val certificateHash = securityManager.getAppSignatureCertificateHash()
            val sslSocketFactory = securityManager.createSecureTlsClientSocketFactory()
            
            // Scan IPs in subnet (e.g., 192.168.1.1 - 192.168.1.254)
            val baseParts = subnet.split(".")
            if (baseParts.size == 3) {
                val base = baseParts.joinToString(".")
                
                // Launch concurrent scans in batches.
                for (i in 1..254) {
                    if (tasks.size >= MAX_PARALLEL_SCANS) {
                        val completed = tasks.awaitAll()
                        results.addAll(completed.filterNotNull())
                        tasks.clear()
                    }
                    
                    val ip = "$base.$i"
                    if (ip == localIpAddress) {
                        continue
                    }

                    tasks.add(async {
                        tryConnectToPeer(
                            ip = ip,
                            localDeviceId = localDeviceId,
                            localDeviceName = localDeviceName,
                            certificateHash = certificateHash,
                            sslSocketFactory = sslSocketFactory
                        )
                    })
                }
                
                // Wait for remaining tasks
                val completed = tasks.awaitAll()
                results.addAll(completed.filterNotNull())
            }
            
            results
        }
    }
    
    /**
     * Try to connect to IP on P2P port.
     * Returns Peer if successful, null otherwise.
     */
    private suspend fun tryConnectToPeer(
        ip: String,
        localDeviceId: String,
        localDeviceName: String,
        certificateHash: String,
        sslSocketFactory: javax.net.ssl.SSLSocketFactory
    ): Peer? {
        return try {
            withTimeoutOrNull(CONNECTION_TIMEOUT_MS.toLong()) {
                val socket = withContext(Dispatchers.IO) {
                    val sslSocket = sslSocketFactory.createSocket(ip, P2P_PORT) as SSLSocket
                    sslSocket.soTimeout = CONNECTION_TIMEOUT_MS
                    sslSocket.startHandshake()
                    sslSocket
                }
                
                socket.use {
                    val handshake = "$localDeviceId|$localDeviceName|$certificateHash\n"
                    val discoveredPeer = withContext(Dispatchers.IO) {
                        socket.outputStream.write(handshake.toByteArray())
                        socket.outputStream.flush()
                        
                        val response = socket.inputStream.bufferedReader().readLine()
                        if (response != null) {
                            val parts = response.split("|")
                            if (parts.size >= 3) {
                                if (parts[0] == localDeviceId) {
                                    return@withContext null
                                }

                                Log.d(TAG, "Peer found: ${parts[1]}")
                                return@withContext Peer(
                                    deviceId = parts[0],
                                    deviceName = parts[1],
                                    ipAddress = ip,
                                    port = P2P_PORT,
                                    certificateHash = parts[2],
                                    pairedAt = 0L,
                                    lastSeen = System.currentTimeMillis(),
                                    isConnected = true
                                )
                            }
                        } else {
                            Log.d(TAG, "$ip:$P2P_PORT - peer accepted TLS but returned no handshake response")
                        }

                        null
                    }

                    discoveredPeer
                }
            }
        } catch (e: Exception) {
            if (shouldLogFailure(e.message)) {
                Log.w(TAG, "$ip:$P2P_PORT - connection failed: ${e.message}")
            }
            null
        }
    }

    private fun shouldLogFailure(message: String?): Boolean {
        if (message.isNullOrBlank()) {
            return false
        }

        return !message.contains("ECONNREFUSED") &&
            !message.contains("Host unreachable") &&
            !message.contains("timed out", ignoreCase = true)
    }

    private fun getLocalIpAddress(): String? {
        return findLocalIpv4Address()?.hostAddress
    }
    
    /**
     * Get local network subnet (e.g., "192.168.1").
     */
    private fun getLocalNetworkSubnet(): String? {
        return getLocalIpAddress()?.substringBeforeLast('.', missingDelimiterValue = "")?.takeIf { it.isNotEmpty() }
    }
    
    /**
     * Get unique device identifier (Android ID).
     */
    private fun getDeviceId(): String {
        return android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        )
    }
    
    /**
     * Get device name (Build.MODEL or device name setting).
     */
    private fun getDeviceName(): String {
        return android.os.Build.MODEL
    }
    
    /**
     * Check if device is connected to Wi-Fi.
     */
    fun isWifiConnected(): Boolean {
        return findLocalIpv4Address() != null
    }

    private fun findLocalIpv4Address(): Inet4Address? {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { networkInterface ->
                    runCatching { networkInterface.isUp && !networkInterface.isLoopback && !networkInterface.isVirtual }
                        .getOrDefault(false)
                }
                .sortedByDescending { networkInterface ->
                    val name = networkInterface.name.orEmpty()
                    when {
                        name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan") -> 3
                        name.startsWith("eth") -> 2
                        else -> 1
                    }
                }
                .flatMap { networkInterface ->
                    Collections.list(networkInterface.inetAddresses).asSequence()
                }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { address ->
                    !address.isLoopbackAddress && address.isSiteLocalAddress
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get local IP address: ${e.message}")
            null
        }
    }
    
    /**
     * Stop discovery and cancel internal scope.
     * A fresh scope is created so this instance can be reused after re-initialize().
     */
    fun stopDiscovery() {
        Log.d(TAG, "Stopping discovery")
        scope.cancel()
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    }
}
