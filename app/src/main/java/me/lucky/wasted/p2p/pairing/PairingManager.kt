package me.lucky.wasted.p2p.pairing

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.lucky.wasted.p2p.database.PeerDao
import me.lucky.wasted.p2p.models.Peer
import me.lucky.wasted.p2p.models.PairingState
import java.security.MessageDigest
import java.security.SecureRandom

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
        private const val MAX_PIN_ATTEMPTS = 5
        private const val PIN_LOCKOUT_MS = 30 * 1000L
        private const val PAIRING_TRANSACTION_VALIDITY_MS = 2 * 60 * 1000L
        private const val NONCE_BYTES = 32
    }

    private val secureRandom = SecureRandom()
    private var pinAttempts = 0
    private var pinLockoutUntil = 0L
    private val pairingTransactions = PairingTransactionStore()
    
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
        Log.d(TAG, "P2P state transition")
        pinAttempts = 0
        pinLockoutUntil = 0L
        val pin = (0 until PIN_LENGTH)
            .map { secureRandom.nextInt(10) }
            .joinToString("")
        
        pinGeneratedTime = System.currentTimeMillis()
        _currentPin.value = pin
        _pairingState.value = PairingState.PAIRING
        
        Log.d(TAG, "P2P state transition")
        return pin
    }
    
    /**
     * Validate pairing PIN from remote device.
     * Returns true if PIN matches and is still valid.
     */
    suspend fun validatePairingPin(remotePin: String): Boolean {
        Log.d(TAG, "P2P validation")
        val now = System.currentTimeMillis()
        if (now < pinLockoutUntil) {
            _pairingError.value = "Too many attempts. Wait before trying again."
            return false
        }
        
        val currentPin = _currentPin.value
        if (currentPin == null) {
            Log.w(TAG, "P2P validation unavailable")
            _pairingError.value = "No active pairing PIN"
            return false
        }
        
        val timeSinceGenerated = now - pinGeneratedTime
        if (timeSinceGenerated > PIN_VALIDITY_DURATION_MS) {
            Log.w(TAG, "P2P validation expired")
            _pairingError.value = "Pairing PIN expired"
            _currentPin.value = null
            return false
        }
        
        val isValid = MessageDigest.isEqual(
            currentPin.toByteArray(Charsets.UTF_8),
            remotePin.toByteArray(Charsets.UTF_8),
        )
        Log.d(TAG, "P2P validation ${if (isValid) "complete" else "failed"}")
        
        if (isValid) {
            pinAttempts = 0
            _currentPin.value = null
            return true
        }
        
        pinAttempts += 1
        if (pinAttempts >= MAX_PIN_ATTEMPTS) {
            pinLockoutUntil = now + PIN_LOCKOUT_MS
            _currentPin.value = null
            _pairingError.value = "Too many attempts. Generate a new PIN."
        }
        return false
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
            Log.d(TAG, "P2P state transition")
            
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
            
            Log.i(TAG, "P2P state transition")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "P2P state transition failed: ${e.javaClass.simpleName}")
            _pairingState.value = PairingState.PAIRING_FAILED
            _pairingError.value = e.message
            false
        }
    }
    
    /**
     * Cancel ongoing pairing attempt.
     */
    fun cancelPairing() {
        Log.d(TAG, "P2P state transition")
        _currentPin.value = null
        _pairingState.value = PairingState.UNPAIRED
        _pairingError.value = null
        pairingTransactions.clear()
    }

    fun hasActivePinSession(): Boolean = _currentPin.value != null

    fun cancelCurrentPinSession() {
        if (_currentPin.value == null && _pairingState.value != PairingState.PAIRING) return
        _currentPin.value = null
        _pairingState.value = PairingState.UNPAIRED
        _pairingError.value = null
    }
    
    /**
     * Unpair a device.
     */
    suspend fun unpairDevice(deviceId: String) {
        Log.d(TAG, "P2P state transition")
        peerDao.unpairDevice(deviceId)
    }
    
    fun getCurrentPin(): String? = _currentPin.value

    /** Starts a pairing transaction bound to one peer and its expected TLS certificate. */
    fun beginPairing(peerDeviceId: String, expectedCertificateHash: String): String? {
        if (expectedCertificateHash.isBlank()) return null
        val nonce = ByteArray(NONCE_BYTES)
            .also { secureRandom.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        if (!pairingTransactions.begin(
                peerDeviceId,
                expectedCertificateHash,
                nonce,
                PAIRING_TRANSACTION_VALIDITY_MS,
            )
        ) {
            return null
        }
        _pairingState.value = PairingState.PAIRING
        _pairingError.value = null
        return nonce
    }

    /** Atomically consumes an initiated pairing response if peer, certificate, nonce, and expiry match. */
    fun consumePairingResponse(
        peerDeviceId: String,
        actualCertificateHash: String,
        nonce: String,
    ): Boolean = pairingTransactions.consume(peerDeviceId, actualCertificateHash, nonce)

    fun cancelPendingPairing(peerDeviceId: String, nonce: String? = null): Boolean {
        if (!pairingTransactions.cancel(peerDeviceId, nonce)) return false
        if (_pairingState.value == PairingState.PAIRING) {
            _pairingState.value = PairingState.UNPAIRED
            _pairingError.value = null
        }
        return true
    }

    fun failPendingPairing(peerDeviceId: String, nonce: String, message: String): Boolean {
        if (!pairingTransactions.cancel(peerDeviceId, nonce)) return false
        _pairingState.value = PairingState.PAIRING_FAILED
        _pairingError.value = message
        return true
    }

    fun expirePendingPairing(peerDeviceId: String, nonce: String): Boolean =
        failPendingPairing(peerDeviceId, nonce, "Pairing timed out")

    fun markPairingFailed(message: String) {
        _pairingState.value = PairingState.PAIRING_FAILED
        _pairingError.value = message
    }
}
