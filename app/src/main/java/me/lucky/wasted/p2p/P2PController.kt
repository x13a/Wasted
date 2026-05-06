package me.lucky.wasted.p2p

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.lucky.wasted.p2p.database.WastedP2PDatabase
import me.lucky.wasted.p2p.models.DeviceSettingsSnapshot
import me.lucky.wasted.p2p.models.Message
import me.lucky.wasted.p2p.models.MessageType
import me.lucky.wasted.p2p.models.PairingState
import me.lucky.wasted.p2p.models.Peer
import me.lucky.wasted.p2p.network.P2PNetwork
import me.lucky.wasted.p2p.pairing.PairingManager
import me.lucky.wasted.p2p.protocol.RemoteControlManager
import me.lucky.wasted.p2p.protocol.SettingsSyncManager

class P2PController private constructor(context: Context) {

    data class ActionResult(
        val ok: Boolean,
        val message: String,
    )

    data class PairingQrPayload(
        val deviceId: String,
        val deviceName: String,
        val pin: String,
    )

    companion object {
        private const val TAG = "P2PController"

        @Volatile
        private var instance: P2PController? = null

        fun getInstance(context: Context): P2PController {
            return instance ?: synchronized(this) {
                instance ?: P2PController(context.applicationContext).also { instance = it }
            }
        }

        /** Returns existing singleton without creating a new one. */
        fun instanceOrNull(): P2PController? = instance
    }

    private val appContext = context.applicationContext
    // Default dispatcher — network/DB ops inside launched coroutines run off the main thread
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = Gson()
    private val peerDao = WastedP2PDatabase.getInstance(appContext).peerDao()

    val network = P2PNetwork(appContext, peerDao)
    val pairingManager = PairingManager(appContext, peerDao)
    val settingsSyncManager = SettingsSyncManager(appContext, network, scope)
    val remoteControlManager = RemoteControlManager(appContext, network, scope)

    val connectedPeers: StateFlow<List<Peer>> = network.connectedPeers
    val allPeers: Flow<List<Peer>> = peerDao.getAllPeersFlow()
    val pairingState: StateFlow<PairingState> = pairingManager.pairingState
    val currentPin: StateFlow<String?> = pairingManager.currentPin
    val pairingError: StateFlow<String?> = pairingManager.pairingError
    val localSettings: StateFlow<DeviceSettingsSnapshot> = settingsSyncManager.localSettings
    val peerSettings: StateFlow<Map<String, DeviceSettingsSnapshot>> = settingsSyncManager.peerSettings
    val pendingReset = remoteControlManager.pendingReset
    private val _uiMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val uiMessages: SharedFlow<String> = _uiMessages

    private var started = false

    init {
        network.onIncomingMessage = { message ->
            handleIncomingMessage(message)
        }
    }

    fun start() {
        if (started) return
        started = true
        network.initialize()
        Log.i(TAG, "P2PController started")
    }

    fun stop() {
        if (!started) return
        started = false
        network.shutdown()
        Log.i(TAG, "P2PController stopped")
    }

    fun generatePairingPin(): String = pairingManager.generatePairingPin()

    fun cancelPairing() {
        pairingManager.cancelPairing()
    }

    fun getOrCreatePairingPin(): String {
        return pairingManager.getCurrentPin() ?: pairingManager.generatePairingPin()
    }

    fun buildPairingQrPayload(pin: String = getOrCreatePairingPin()): String {
        return gson.toJson(
            PairingQrPayload(
                deviceId = getDeviceId(),
                deviceName = getDeviceName(),
                pin = pin,
            )
        )
    }

    suspend fun pairFromQrPayload(qrPayload: String): ActionResult {
        return try {
            val payload = gson.fromJson(qrPayload, PairingQrPayload::class.java)
            if (payload.deviceId == getDeviceId()) {
                return ActionResult(false, "Scanned your own pairing code")
            }

            val peer = peerDao.getPeerById(payload.deviceId)
                ?: return ActionResult(false, "Device not discovered yet. Keep both devices on the same network and try again.")

            sendPairingRequest(peer, payload.pin)
            ActionResult(true, "Pairing request sent to ${peer.deviceName}")
        } catch (e: Exception) {
            ActionResult(false, "Invalid QR payload")
        }
    }

    fun sendPairingRequest(peer: Peer, pin: String) {
        scope.launch {
            try {
                val payload = mapOf(
                    "pin" to pin,
                    "deviceName" to getDeviceName(),
                    "deviceId" to getDeviceId(),
                )

                val message = Message(
                    fromDeviceId = getDeviceId(),
                    toDeviceId = peer.deviceId,
                    type = MessageType.PAIRING_REQUEST,
                    payload = gson.toJson(payload),
                    requiresAck = true,
                )

                val success = network.sendToPeerWithRetry(peer, message)
                if (success) {
                    Log.i(TAG, "Pairing request sent to ${peer.deviceName}")
                    _uiMessages.emit("Pairing request sent to ${peer.deviceName}")
                } else {
                    Log.e(TAG, "Failed to send pairing request to ${peer.deviceName}")
                    _uiMessages.emit("Could not reach ${peer.deviceName} for pairing")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pairing request error: ${e.message}", e)
                _uiMessages.emit("Pairing failed to start for ${peer.deviceName}")
            }
        }
    }

    fun unpairPeer(peer: Peer) {
        scope.launch {
            val payload = mapOf(
                "deviceName" to getDeviceName(),
                "deviceId" to getDeviceId(),
            )
            val message = Message(
                fromDeviceId = getDeviceId(),
                toDeviceId = peer.deviceId,
                type = MessageType.UNPAIR_REQUEST,
                payload = gson.toJson(payload),
                requiresAck = true,
            )

            val remoteNotified = network.sendToPeerWithRetry(peer, message)
            pairingManager.unpairDevice(peer.deviceId)
            settingsSyncManager.forgetPeerSettings(peer.deviceId)
            if (remoteNotified) {
                _uiMessages.emit("${peer.deviceName} was unpaired on both phones")
            } else {
                _uiMessages.emit("${peer.deviceName} removed here. Remote phone could not be notified right now")
            }
        }
    }

    fun saveLocalSettings(
        inactivityTimeout: Long,
        usbDetectionEnabled: Boolean,
        autoLockEnabled: Boolean,
    ) {
        settingsSyncManager.saveLocalSettings(inactivityTimeout, usbDetectionEnabled, autoLockEnabled)
        remoteControlManager.handleRemoteResetConfirmationSettingChanged(
            settingsSyncManager.localSettings.value.remoteResetConfirmationEnabled,
        )
    }

    fun saveLocalSettings(settings: DeviceSettingsSnapshot) {
        settingsSyncManager.saveLocalSettings(settings)
        remoteControlManager.handleRemoteResetConfirmationSettingChanged(
            settings.remoteResetConfirmationEnabled,
        )
    }

    fun announceCurrentSettings() {
        scope.launch {
            settingsSyncManager.announceLocalSettings()
        }
    }

    fun requestPeerSettings(peer: Peer, force: Boolean = false) {
        scope.launch {
            val success = settingsSyncManager.requestPeerSettings(peer, force)
            if (force && !success) {
                _uiMessages.emit("Could not request current settings from ${peer.deviceName}")
            }
        }
    }

    fun updatePeerSettings(
        peer: Peer,
        settings: DeviceSettingsSnapshot,
    ) {
        scope.launch {
            val success = settingsSyncManager.sendSettingsToPeer(
                peer = peer,
                settings = settings,
            )
            if (success) {
                _uiMessages.emit("Settings update sent to ${peer.deviceName}")
            } else {
                _uiMessages.emit("Could not send settings update to ${peer.deviceName}")
            }
        }
    }

    suspend fun getAllKnownPeers(): List<Peer> = peerDao.getAllPeers()

    private suspend fun handleIncomingMessage(message: Message) {
        val peer = peerDao.getPeerById(message.fromDeviceId)
        val isPairedPeer = (peer?.pairedAt ?: 0L) > 0L

        when (message.type) {
            MessageType.UNPAIR_REQUEST -> handleIncomingUnpairRequest(message)
            MessageType.SETTINGS_REQUEST -> if (isPairedPeer) settingsSyncManager.handleSettingsRequestMessage(message)
            MessageType.SETTINGS_RESPONSE -> if (isPairedPeer) settingsSyncManager.handleSettingsResponseMessage(message)
            MessageType.SETTINGS_CHANGE -> if (isPairedPeer) handleIncomingSettingsChange(message)
            MessageType.RESET_ACK -> if (isPairedPeer) handleIncomingResetAck(message)
            MessageType.RESET_COMMAND -> if (isPairedPeer) remoteControlManager.handleResetCommandMessage(message)
            MessageType.PAIRING_REQUEST -> handleIncomingPairingRequest(message)
            MessageType.PAIRING_RESPONSE -> handleIncomingPairingResponse(message)
            else -> Unit
        }
    }

    private suspend fun handleIncomingUnpairRequest(message: Message) {
        try {
            val peer = peerDao.getPeerById(message.fromDeviceId) ?: return
            pairingManager.unpairDevice(peer.deviceId)
            settingsSyncManager.forgetPeerSettings(peer.deviceId)
            Log.i(TAG, "Unpair received from ${peer.deviceName}")
            _uiMessages.emit("${peer.deviceName} removed this phone from approved devices")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle unpair request: ${e.message}", e)
        }
    }

    private suspend fun handleIncomingSettingsChange(message: Message) {
        val requesterName = settingsSyncManager.handleSettingsChangeMessage(message)
        remoteControlManager.handleRemoteResetConfirmationSettingChanged(
            settingsSyncManager.localSettings.value.remoteResetConfirmationEnabled,
        )
        if (!requesterName.isNullOrBlank()) {
            _uiMessages.emit("$requesterName updated this phone's Wasted settings")
        }
    }

    private suspend fun handleIncomingResetAck(message: Message) {
        try {
            val payload = gson.fromJson(message.payload, Map::class.java)
            val status = payload["status"] as? String ?: return
            val deviceName = payload["deviceName"] as? String ?: "Remote device"
            val reason = payload["reason"] as? String
            when (status) {
                "confirmed" -> _uiMessages.emit("$deviceName confirmed the reset request")
                "declined" -> _uiMessages.emit("$deviceName declined the reset request")
                "failed" -> _uiMessages.emit(reason ?: "$deviceName could not start the reset")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle reset ACK: ${e.message}", e)
        }
    }

    private suspend fun handleIncomingPairingRequest(message: Message) {
        try {
            val payload = gson.fromJson(message.payload, Map::class.java)
            val pin = payload["pin"] as? String ?: return
            val peer = peerDao.getPeerById(message.fromDeviceId) ?: return
            val isValid = pairingManager.validatePairingPin(pin)

            if (isValid) {
                pairingManager.completePairing(
                    remoteDeviceId = peer.deviceId,
                    remoteDeviceName = peer.deviceName,
                    remoteIpAddress = peer.ipAddress,
                    remotePort = peer.port,
                    remoteCertificateHash = peer.certificateHash,
                )
                _uiMessages.emit("${peer.deviceName} paired successfully")
            } else {
                _uiMessages.emit("Rejected pairing attempt from ${peer.deviceName}: wrong or expired code")
            }

            val responsePayload = mapOf(
                "accepted" to isValid,
                "deviceName" to getDeviceName(),
            )
            val response = Message(
                fromDeviceId = getDeviceId(),
                toDeviceId = peer.deviceId,
                type = MessageType.PAIRING_RESPONSE,
                payload = gson.toJson(responsePayload),
                requiresAck = false,
            )
            network.sendToPeerWithRetry(peer, response)

            if (isValid) {
                Log.i(TAG, "Pairing approved for ${peer.deviceName}")
                settingsSyncManager.announceLocalSettings(peer)
                settingsSyncManager.requestPeerSettings(peer, force = true)
            } else {
                Log.w(TAG, "Pairing rejected for ${peer.deviceName}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle pairing request: ${e.message}", e)
        }
    }

    private suspend fun handleIncomingPairingResponse(message: Message) {
        try {
            val payload = gson.fromJson(message.payload, Map::class.java)
            val accepted = payload["accepted"] as? Boolean ?: false
            val peer = peerDao.getPeerById(message.fromDeviceId) ?: return

            if (accepted) {
                pairingManager.completePairing(
                    remoteDeviceId = peer.deviceId,
                    remoteDeviceName = peer.deviceName,
                    remoteIpAddress = peer.ipAddress,
                    remotePort = peer.port,
                    remoteCertificateHash = peer.certificateHash,
                )
                Log.i(TAG, "Pairing completed with ${peer.deviceName}")
                _uiMessages.emit("${peer.deviceName} approved this phone")
                settingsSyncManager.announceLocalSettings(peer)
                settingsSyncManager.requestPeerSettings(peer, force = true)
            } else {
                Log.w(TAG, "Pairing denied by ${peer.deviceName}")
                _uiMessages.emit("${peer.deviceName} rejected the pairing code")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle pairing response: ${e.message}", e)
        }
    }

    private fun getDeviceId(): String {
        return Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
    }

    private fun getDeviceName(): String {
        return android.os.Build.MODEL ?: "Unknown Device"
    }
}