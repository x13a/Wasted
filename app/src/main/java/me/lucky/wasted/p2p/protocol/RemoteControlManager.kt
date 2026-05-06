package me.lucky.wasted.p2p.protocol

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.lucky.wasted.Preferences
import me.lucky.wasted.admin.DeviceAdminManager
import me.lucky.wasted.p2p.database.WastedP2PDatabase
import me.lucky.wasted.p2p.models.Message
import me.lucky.wasted.p2p.models.MessageType
import me.lucky.wasted.p2p.network.P2PNetwork
import me.lucky.wasted.p2p.models.Peer

/**
 * Manages remote device control via P2P network.
 * Handles remote reset commands, device locking, and wipe operations.
 * 
 * Log pattern:
 * INFO: "Reset command sent to [peer]"
 * DEBUG: "Reset command received from [peer]"
 * INFO: "Device lock triggered"
 * ERROR: "Device Admin not active" (if applicable)
 */
class RemoteControlManager(
    private val context: Context,
    private val p2pNetwork: P2PNetwork,
    private val scope: CoroutineScope
) : CoroutineScope by scope {

    data class ResetExecutionResult(
        val success: Boolean,
        val userMessage: String,
        val ackStatus: String,
        val ackReason: String? = null,
    )

    companion object {
        private const val TAG = "RemoteControl"
    }

    private val gson = Gson()
    private val adminManager = DeviceAdminManager(context)
    private val prefs = Preferences.new(context)
    private val peerDao = WastedP2PDatabase.getInstance(context).peerDao()

    // Observable reset state
    private val _pendingReset = MutableStateFlow<Pair<String, String>?>(null)  // peerId to peerName
    val pendingReset: StateFlow<Pair<String, String>?> = _pendingReset

    /**
     * Send reset command to remote peer.
     * Peer will show confirmation dialog before executing reset.
     */
    fun sendRemoteReset(peer: Peer) {
        Log.d(TAG, "Reset button clicked (remote)")
        
        scope.launch {
            try {
                val payload = mapOf(
                    "action" to "reset",
                    "timestamp" to System.currentTimeMillis(),
                    "deviceId" to getDeviceId(),
                    "deviceName" to getDeviceName()
                )
                
                val message = Message(
                    fromDeviceId = getDeviceId(),
                    toDeviceId = peer.deviceId,
                    type = MessageType.RESET_COMMAND,
                    payload = gson.toJson(payload),
                    timestamp = System.currentTimeMillis(),
                    requiresAck = true
                )

                val success = p2pNetwork.sendToPeerWithRetry(peer, message)
                if (success) {
                    Log.i(TAG, "Reset command sent to ${peer.deviceName}")
                } else {
                    Log.e(TAG, "Failed to send reset command to ${peer.deviceName}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send reset command to ${peer.deviceName}: ${e.message}", e)
            }
        }
    }

    /**
     * Execute local reset after user confirmation.
     */
    fun executeLocalReset(): ResetExecutionResult {
        Log.d(TAG, "Reset button clicked (local)")

        val resetSupport = adminManager.getResetSupport()
        if (!resetSupport.isSupported) {
            Log.e(TAG, "Reset is not supported on this phone: ${resetSupport.userMessage}")
            return ResetExecutionResult(
                success = false,
                userMessage = resetSupport.userMessage,
                ackStatus = "failed",
                ackReason = resetSupport.userMessage,
            )
        }

        return try {
            Log.d(TAG, "User confirmed reset")
            Log.i(TAG, "wipeData() called")
            adminManager.wipeData()
            Log.i(TAG, "Device reset initiated")
            ResetExecutionResult(
                success = true,
                userMessage = "Device reset requested",
                ackStatus = "confirmed",
            )
        } catch (e: IllegalStateException) {
            val reason = if ((e.message ?: "").contains("system user", ignoreCase = true)) {
                "This Android user does not allow factory reset through Device Admin. Emulator system users commonly reject wipe requests."
            } else {
                e.message ?: "Reset is not allowed on this device"
            }
            Log.e(TAG, "Failed to execute local reset: $reason", e)
            ResetExecutionResult(
                success = false,
                userMessage = reason,
                ackStatus = "failed",
                ackReason = reason,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute local reset: ${e.message}", e)
            ResetExecutionResult(
                success = false,
                userMessage = "Reset could not be started on this device",
                ackStatus = "failed",
                ackReason = e.message ?: "Reset could not be started on this device",
            )
        }
    }

    /**
     * Handle incoming reset command from peer.
     * Shows confirmation dialog before executing.
     */
    suspend fun handleResetCommandMessage(message: Message) {
        try {
            val payload = gson.fromJson(message.payload, Map::class.java)
            Log.d(TAG, "Reset command received from ${message.fromDeviceId}")

            val peerName = payload["deviceName"] as? String ?: "Remote Device"
            if (!prefs.remoteResetConfirmationEnabled) {
                Log.d(TAG, "Remote reset confirmation disabled; executing immediately")
                val result = executeLocalReset()
                sendResetAck(message.fromDeviceId, peerName, result.ackStatus, result.ackReason)
                return
            }

            // Set pending reset to trigger UI confirmation dialog
            _pendingReset.value = message.fromDeviceId to peerName

            Log.d(TAG, "Showing reset confirmation dialog")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle reset command: ${e.message}", e)
        }
    }

    fun handleRemoteResetConfirmationSettingChanged(enabled: Boolean) {
        if (enabled) {
            return
        }

        scope.launch {
            val (peerId, peerName) = _pendingReset.value ?: return@launch
            Log.d(TAG, "Remote reset confirmation disabled while request was pending; auto-declining")
            sendResetAck(
                peerId,
                peerName,
                "declined",
                "Remote reset confirmation was turned off before approval",
            )
            _pendingReset.value = null
        }
    }

    /**
     * User confirmed remote reset from peer.
     */
    fun confirmRemoteReset() {
        scope.launch {
            try {
                val (peerId, peerName) = _pendingReset.value ?: return@launch
                
                Log.d(TAG, "User confirmed reset from $peerName")
                val result = executeLocalReset()
                sendResetAck(peerId, peerName, result.ackStatus, result.ackReason)
                _pendingReset.value = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to confirm remote reset: ${e.message}", e)
            }
        }
    }

    /**
     * User declined remote reset from peer.
     */
    fun declineRemoteReset() {
        scope.launch {
            val (peerId, peerName) = _pendingReset.value ?: return@launch
            sendResetAck(peerId, peerName, "declined")
            _pendingReset.value = null
            Log.d(TAG, "User declined remote reset")
        }
    }

    /**
     * Lock device locally.
     */
    fun lockDeviceLocally(): Boolean {
        Log.d(TAG, "lockNow() called")
        
        try {
            if (adminManager.isActive()) {
                Log.d(TAG, "DevicePolicyManager.lockNow() invoked")
                adminManager.lockNow()
                Log.i(TAG, "Device lock triggered")
                return true
            } else {
                Log.e(TAG, "Device Admin not active")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock device: ${e.message}", e)
        }
        return false
    }

    /**
     * Get device ID for identifying source.
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

    private suspend fun sendResetAck(peerId: String, peerName: String, status: String, reason: String? = null) {
        val peer = peerDao.getPeerById(peerId) ?: return
        val payload = mapOf(
            "status" to status,
            "deviceName" to getDeviceName(),
            "deviceId" to getDeviceId(),
            "reason" to reason,
        )
        val message = Message(
            fromDeviceId = getDeviceId(),
            toDeviceId = peer.deviceId,
            type = MessageType.RESET_ACK,
            payload = gson.toJson(payload),
            timestamp = System.currentTimeMillis(),
            requiresAck = false,
        )
        val delivered = p2pNetwork.sendToPeerWithRetry(peer, message)
        if (delivered) {
            Log.i(TAG, "Reset $status ACK sent to $peerName")
        }
    }
}
