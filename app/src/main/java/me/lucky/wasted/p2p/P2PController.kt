package me.lucky.wasted.p2p

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lucky.wasted.p2p.database.P2PDatabaseKeyStore
import me.lucky.wasted.p2p.database.P2PRevocationFallbackStore
import me.lucky.wasted.p2p.database.P2PRevocationStore
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
        val certificateHash: String,
    )

    companion object {
        private const val TAG = "P2PController"
        private const val PAIRING_TIMEOUT_MS = 2 * 60 * 1000L

        @Volatile
        private var instance: P2PController? = null

        fun getInstance(context: Context): P2PController {
            return instance ?: synchronized(this) {
                instance ?: P2PController(context.applicationContext).also { instance = it }
            }
        }

        /** Creates the controller only when local P2P storage is available. */
        fun tryGetInstance(context: Context): Result<P2PController> =
            runCatching { getInstance(context) }

        /** Returns existing singleton without creating a new one. */
        fun instanceOrNull(): P2PController? = instance

        /** Drops the singleton after local P2P storage has been reset. */
        fun resetInstance() {
            synchronized(this) {
                instance?.stop()
                instance = null
            }
        }
    }

    private val appContext = context.applicationContext
    // Default dispatcher — network/DB ops inside launched coroutines run off the main thread
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = Gson()
    private val peerDao = WastedP2PDatabase.getInstance(appContext).peerDao()
    private val revocationStore = P2PRevocationStore(
        P2PDatabaseKeyStore.new(appContext),
        P2PRevocationFallbackStore(appContext),
        appContext,
    )
    private val revokedPeers = java.util.Collections.synchronizedSet(mutableSetOf<String>())

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
    private data class OutboundPairing(
        val peerId: String,
        val nonce: String,
        val job: Job,
    )
    private val pairingLock = Any()
    private var outboundPairing: OutboundPairing? = null

    init {
        revokedPeers.addAll(revocationStore.pending())
        network.onIncomingMessage = { message ->
            handleIncomingMessage(message)
        }
    }

    fun start() {
        if (started) return
        if (revocationStore.isUnavailable()) {
            Log.e(TAG, "P2P startup blocked by fail-closed revocation latch")
            return
        }
        started = true
        scope.launch {
            if (!retryPendingRevocations()) {
                started = false
                Log.e(TAG, "P2P startup blocked by unreconciled revocation")
                return@launch
            }
            network.initialize()
            Log.i(TAG, "P2PController started")
        }
    }

    private suspend fun retryPendingRevocations(): Boolean {
        return try {
            var allConfirmed = true
            revocationStore.pending().forEach { deviceId ->
                peerDao.unpairDevice(deviceId)
                if (revocationStore.remove(deviceId)) {
                    revokedPeers.remove(deviceId)
                } else {
                    allConfirmed = false
                }
            }
            if (allConfirmed && revokedPeers.isEmpty()) {
                revocationStore.clearUnavailable()
                true
            } else {
                false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun stop() {
        if (!started) return
        started = false
        network.shutdown()
        Log.i(TAG, "P2PController stopped")
    }

    fun generatePairingPin(): String = synchronized(pairingLock) {
        val attempt = outboundPairing
        outboundPairing = null
        if (attempt != null) {
            attempt.job.cancel()
            pairingManager.cancelPendingPairing(attempt.peerId, attempt.nonce)
        }
        pairingManager.generatePairingPin()
    }

    fun getOrCreatePairingPin(): String {
        return pairingManager.getCurrentPin() ?: generatePairingPin()
    }

    fun cancelPairing() {
        val attempt = synchronized(pairingLock) {
            outboundPairing.also { outboundPairing = null }
        }
        if (attempt != null) {
            attempt.job.cancel()
            pairingManager.cancelPendingPairing(attempt.peerId, attempt.nonce)
        } else {
            pairingManager.cancelCurrentPinSession()
        }
    }

    fun isRevocationBlocked(): Boolean = revocationStore.isUnavailable()

    fun buildPairingQrPayload(pin: String = getOrCreatePairingPin()): String {
        return gson.toJson(
            PairingQrPayload(
                deviceId = getDeviceId(),
                deviceName = getDeviceName(),
                pin = pin,
                certificateHash = network.getTlsCertificateHash(),
            )
        )
    }

    suspend fun pairFromQrPayload(qrPayload: String): ActionResult {
        return try {
            val payload = gson.fromJson(qrPayload, PairingQrPayload::class.java)
            if (payload.deviceId == getDeviceId()) {
                return ActionResult(false, "Scanned your own pairing code")
            }
            if (payload.certificateHash.isBlank()) {
                return ActionResult(false, "Update the app on both phones and show a fresh pairing code.")
            }

            val peer = peerDao.getPeerById(payload.deviceId)
                ?: return ActionResult(false, "Device not discovered yet. Keep both devices on the same network and try again.")

            if (peer.pairedAt > 0L && peer.certificateHash != payload.certificateHash) {
                return ActionResult(false, "Pairing code does not match the paired device certificate.")
            }
            if (peer.certificateHash.isNotBlank() &&
                peer.certificateHash != payload.certificateHash
            ) {
                return ActionResult(false, "Pairing code does not match the discovered device certificate.")
            }

            val pinnedPeer = if (peer.certificateHash == payload.certificateHash) {
                peer
            } else {
                peer.copy(certificateHash = payload.certificateHash).also {
                    peerDao.insertPeer(it)
                }
            }
            sendPairingRequest(pinnedPeer, payload.pin, payload.certificateHash)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult(false, "Invalid QR payload")
        }
    }

    fun sendPairingRequest(
        peer: Peer,
        pin: String,
        pinnedCertificateHash: String? = null,
    ): ActionResult = synchronized(pairingLock) {
        if (outboundPairing != null) {
            return ActionResult(false, "Another pairing request is already in progress.")
        }
        if (pairingManager.hasActivePinSession()) {
            return ActionResult(false, "A pairing PIN is already active on this phone.")
        }
        val expectedCertificateHash =
            pinnedCertificateHash?.takeIf { it.isNotBlank() } ?: peer.certificateHash
        if (expectedCertificateHash.isBlank()) {
            return ActionResult(false, "Pairing certificate is unavailable. Try discovery again.")
        }
        val nonce = pairingManager.beginPairing(peer.deviceId, expectedCertificateHash)
        if (nonce == null) {
            return ActionResult(
                false,
                "A pairing request is already in progress or the certificate is unavailable.",
            )
        }

        val sendJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val payload = mapOf(
                    "pin" to pin,
                    "deviceName" to getDeviceName(),
                    "deviceId" to getDeviceId(),
                    "nonce" to nonce,
                )

                val message = Message(
                    fromDeviceId = getDeviceId(),
                    toDeviceId = peer.deviceId,
                    type = MessageType.PAIRING_REQUEST,
                    payload = gson.toJson(payload),
                    requiresAck = true,
                )

                val success = network.sendToPeerWithRetry(
                    peer,
                    message,
                    pinnedCertificateHash = expectedCertificateHash,
                )
                if (!success) {
                    val failed = pairingManager.failPendingPairing(
                        peer.deviceId,
                        nonce,
                        "Could not reach the device for pairing",
                    )
                    if (failed) {
                        Log.e(TAG, "P2P request send failed")
                        _uiMessages.emit("Could not reach ${peer.deviceName} for pairing")
                    }
                }
                if (success) {
                    Log.i(TAG, "P2P request sent")
                    _uiMessages.emit("Pairing request sent to ${peer.deviceName}")
                    delay(PAIRING_TIMEOUT_MS)
                    if (pairingManager.expirePendingPairing(peer.deviceId, nonce)) {
                        _uiMessages.emit("Pairing timed out for ${peer.deviceName}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failed = pairingManager.failPendingPairing(
                    peer.deviceId,
                    nonce,
                    "Pairing failed to start",
                )
                if (failed) {
                    Log.e(TAG, "P2P request failed: ${e.javaClass.simpleName}")
                    _uiMessages.emit("Pairing failed to start for ${peer.deviceName}")
                }
            }
        }
        outboundPairing = OutboundPairing(peer.deviceId, nonce, sendJob)
        sendJob.invokeOnCompletion {
            clearOutbound(peer.deviceId, nonce, cancelJob = false)
        }
        sendJob.start()
        ActionResult(true, "Pairing request sent to ${peer.deviceName}")
    }

    private fun clearOutbound(peerId: String, nonce: String, cancelJob: Boolean) {
        val attempt = synchronized(pairingLock) {
            val current = outboundPairing
            if (current?.peerId == peerId && current.nonce == nonce) {
                outboundPairing = null
                current
            } else {
                null
            }
        }
        if (cancelJob) attempt?.job?.cancel()
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
        if (revokedPeers.contains(message.fromDeviceId)) return
        val peer = peerDao.getPeerById(message.fromDeviceId)
        val isPairedPeer = (peer?.pairedAt ?: 0L) > 0L

        when (message.type) {
            MessageType.UNPAIR_REQUEST -> if (isPairedPeer) handleIncomingUnpairRequest(message)
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
            if (peer.pairedAt <= 0L) return
            pairingManager.unpairDevice(peer.deviceId)
            settingsSyncManager.forgetPeerSettings(peer.deviceId)
            Log.i(TAG, "P2P state transition")
            _uiMessages.emit("${peer.deviceName} removed this phone from approved devices")
        } catch (e: Exception) {
            Log.e(TAG, "P2P state update failed: ${e.javaClass.simpleName}")
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
            Log.e(TAG, "P2P state update failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun handleIncomingPairingRequest(message: Message) {
        try {
            val payload = gson.fromJson(message.payload, Map::class.java)
            val pin = payload["pin"] as? String ?: return
            val nonce = payload["nonce"] as? String ?: return
            if (!nonce.matches(Regex("^[0-9a-f]{64}$"))) return
            val peer = peerDao.getPeerById(message.fromDeviceId) ?: return
            val isValid = pairingManager.validatePairingPin(pin)
            val paired = isValid && pairingManager.completePairing(
                remoteDeviceId = peer.deviceId,
                remoteDeviceName = peer.deviceName,
                remoteIpAddress = peer.ipAddress,
                remotePort = peer.port,
                remoteCertificateHash = peer.certificateHash,
            )

            if (!paired) {
                if (isValid) {
                    pairingManager.markPairingFailed("Could not save pairing")
                }
                val reason = if (isValid) "could not be saved" else "wrong or expired code"
                _uiMessages.emit("Rejected pairing attempt from ${peer.deviceName}: $reason")
            }

            val responsePayload = mapOf(
                "accepted" to paired,
                "deviceName" to getDeviceName(),
                "nonce" to nonce,
            )
            val response = Message(
                fromDeviceId = getDeviceId(),
                toDeviceId = peer.deviceId,
                type = MessageType.PAIRING_RESPONSE,
                payload = gson.toJson(responsePayload),
                requiresAck = false,
            )
            val responseDelivered = try {
                network.sendToPeerWithRetry(peer, response)
            } catch (e: CancellationException) {
                if (paired) {
                    withContext(NonCancellable) { rollbackPairing(peer) }
                }
                throw e
            } catch (e: Exception) {
                false
            }

            if (paired && responseDelivered) {
                _uiMessages.emit("${peer.deviceName} paired successfully")
                Log.i(TAG, "P2P state transition")
                settingsSyncManager.announceLocalSettings(peer)
                settingsSyncManager.requestPeerSettings(peer, force = true)
            } else if (paired) {
                rollbackPairing(peer)
            } else {
                Log.w(TAG, "P2P state transition rejected")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "P2P state update failed: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun rollbackPairing(peer: Peer) {
        val rollbackSucceeded = try {
            pairingManager.unpairDevice(peer.deviceId)
            val remaining = peerDao.getPeerById(peer.deviceId)
            remaining == null || remaining.pairedAt <= 0L
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        pairingManager.markPairingFailed("Could not confirm pairing")
        if (rollbackSucceeded) {
            val removed = try {
                revocationStore.remove(peer.deviceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (removed) {
                revokedPeers.remove(peer.deviceId)
            } else {
                revokedPeers.add(peer.deviceId)
            }
        } else {
            revokedPeers.add(peer.deviceId)
            val persisted = try {
                revocationStore.add(peer.deviceId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!persisted) {
                revocationStore.markUnavailable()
                Log.e(TAG, "Pairing revocation marker could not be persisted")
            }
            network.shutdown()
            Log.e(TAG, "P2P disabled after failed pairing rollback")
        }
        _uiMessages.emit("Could not confirm pairing with ${peer.deviceName}")
        Log.w(TAG, "P2P state transition rejected")
    }

    private suspend fun handleIncomingPairingResponse(message: Message) {
        try {
            val payload = gson.fromJson(message.payload, Map::class.java)
            val accepted = payload["accepted"] as? Boolean ?: false
            val nonce = payload["nonce"] as? String ?: return
            val peer = peerDao.getPeerById(message.fromDeviceId) ?: return

            if (!pairingManager.consumePairingResponse(
                    peer.deviceId,
                    peer.certificateHash,
                    nonce,
                )
            ) {
                Log.w(TAG, "P2P state message rejected")
                return
            }
            clearOutbound(peer.deviceId, nonce, cancelJob = true)

            if (accepted) {
                val paired = pairingManager.completePairing(
                    remoteDeviceId = peer.deviceId,
                    remoteDeviceName = peer.deviceName,
                    remoteIpAddress = peer.ipAddress,
                    remotePort = peer.port,
                    remoteCertificateHash = peer.certificateHash,
                )
                if (paired) {
                    Log.i(TAG, "P2P state transition")
                    _uiMessages.emit("${peer.deviceName} approved this phone")
                    settingsSyncManager.announceLocalSettings(peer)
                    settingsSyncManager.requestPeerSettings(peer, force = true)
                } else {
                    pairingManager.markPairingFailed("Could not save pairing")
                    _uiMessages.emit("Could not save pairing with ${peer.deviceName}")
                }
            } else {
                Log.w(TAG, "P2P state transition denied")
                pairingManager.markPairingFailed("Pairing declined")
                _uiMessages.emit("${peer.deviceName} rejected the pairing code")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "P2P state update failed: ${e.javaClass.simpleName}")
        }
    }

    private fun getDeviceId(): String {
        return Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
    }

    private fun getDeviceName(): String {
        return android.os.Build.MODEL ?: "Unknown Device"
    }
}