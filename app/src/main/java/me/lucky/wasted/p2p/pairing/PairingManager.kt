package me.lucky.wasted.p2p.pairing

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.lucky.wasted.p2p.database.PeerDao
import me.lucky.wasted.p2p.models.Peer
import me.lucky.wasted.p2p.models.PairingState
import java.security.MessageDigest
import java.util.*

/**
 * Manages pairing process between two devices.
 * Handles PIN generation, validation, and secure certificate verification.
 * 
 * Log pattern:
 * DEBUG: "mDNS discovery started"
 * DEBUG: "Peer found: [device-name]"
 * INFO: "Pairing dialog shown"
 * DEBUG: "PIN validation: [status]"
 * INFO: "Pairing successful"
 */
class PairingManager(
    private val context: Context,
    private val peerDao: PeerDao
) {
    companion object {
        private const val TAG = "PairingManager"
        private const val PIN_LENGTH = 6
        private const val PIN_VALIDITY_DURATION_MS = 5 * 60 * 1000 // 5 minutes
    }
    
    private val _pairingState = MutableStateFlow<PairingState>(PairingState.UNPAIRED)
    val pairingState: StateFlow<PairingState> = _pairingState
    
    private val _currentPin = MutableStateFlow<String?>(null)
    val currentPin: StateFlow<String?> = _currentPin
    
    private val _pairingError = MutableStateFlow<String?>(null)
    val pairingError: StateFlow<String?> = _pairingError
    
    private var pinGeneratedTime: Long = 0
    
    /**
     * Generate a secure random PIN for pairing.
     * PIN is valid for 5 minutes.
     */
    fun generatePairingPin(): String {
        Log.d(TAG, "Generating new pairing PIN")
        val pin = (0 until PIN_LENGTH)
            .map { Random().nextInt(10) }
            .joinToString("")
        
        pinGeneratedTime = System.currentTimeMillis()
        _currentPin.value = pin
        _pairingState.value = PairingState.PAIRING
        
        Log.d(TAG, "PIN generated, valid until ${pinGeneratedTime + PIN_VALIDITY_DURATION_MS}")
        return pin
    }
    
    /**
     * Validate pairing PIN from remote device.
     * Returns true if PIN matches and is still valid.
     */
    suspend fun validatePairingPin(remotePin: String): Boolean {
        Log.d(TAG, "PIN validation: comparing pins")
        
        val currentPin = _currentPin.value
        if (currentPin == null) {
            Log.w(TAG, "PIN validation: no active PIN")
            _pairingError.value = "No active pairing PIN"
            return false
        }
        
        val timeSinceGenerated = System.currentTimeMillis() - pinGeneratedTime
        if (timeSinceGenerated > PIN_VALIDITY_DURATION_MS) {
            Log.w(TAG, "PIN validation: PIN expired after ${timeSinceGenerated}ms")
            _pairingError.value = "Pairing PIN expired"
            _currentPin.value = null
            return false
        }
        
        val isValid = currentPin == remotePin
        Log.d(TAG, "PIN validation: ${if (isValid) "PASS" else "FAIL"}")
        
        return isValid
    }
    
    /**
     * Complete pairing with remote device.
     * Stores peer information and certificate hash.
     */
    suspend fun completePairing(
        remoteDeviceId: String,
        remoteDeviceName: String,
        remoteIpAddress: String,
        remotePort: Int,
        remoteCertificateHash: String
    ): Boolean {
        return try {
            Log.d(TAG, "Completing pairing with device: $remoteDeviceName")
            
            val peer = Peer(
                deviceId = remoteDeviceId,
                deviceName = remoteDeviceName,
                ipAddress = remoteIpAddress,
                port = remotePort,
                certificateHash = remoteCertificateHash,
                pairedAt = System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis(),
                isConnected = false
            )
            
            peerDao.insertPeer(peer)
            _pairingState.value = PairingState.PAIRED
            _currentPin.value = null
            _pairingError.value = null
            
            Log.i(TAG, "Pairing successful with $remoteDeviceName")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Pairing failed: ${e.message}", e)
            _pairingState.value = PairingState.PAIRING_FAILED
            _pairingError.value = e.message
            false
        }
    }
    
    /**
     * Cancel ongoing pairing attempt.
     */
    fun cancelPairing() {
        Log.d(TAG, "Pairing cancelled")
        _currentPin.value = null
        _pairingState.value = PairingState.UNPAIRED
        _pairingError.value = null
    }
    
    /**
     * Unpair a device.
     */
    suspend fun unpairDevice(deviceId: String) {
        Log.d(TAG, "Unpairing device: $deviceId")
        peerDao.unpairDevice(deviceId)
    }
    
    /**
     * Get SHA-256 hash of a certificate (for verification).
     */
    fun getCertificateHash(certificateData: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(certificateData)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Check if two certificate hashes match (APK signing verification).
     */
    fun verifyCertificateMatch(localHash: String, remoteHash: String): Boolean {
        val matches = localHash == remoteHash
        Log.d(TAG, "Certificate verification: ${if (matches) "PASS" else "FAIL"}")
        return matches
    }

    fun getCurrentPin(): String? = _currentPin.value
}
