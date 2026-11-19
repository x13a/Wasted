package me.lucky.wasted.p2p.protocol

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import me.lucky.wasted.Preferences
import me.lucky.wasted.Trigger
import me.lucky.wasted.admin.DeviceAdminManager
import me.lucky.wasted.settings.LocalSettingsApplier
import me.lucky.wasted.p2p.database.WastedP2PDatabase
import me.lucky.wasted.p2p.models.DeviceSettingsSnapshot
import me.lucky.wasted.p2p.models.Message
import me.lucky.wasted.p2p.models.MessageType
import me.lucky.wasted.p2p.models.Peer
import me.lucky.wasted.p2p.models.SettingsRequestPayload
import me.lucky.wasted.p2p.models.SettingsUpdateCommand
import me.lucky.wasted.p2p.network.P2PNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages settings synchronization across P2P network.
 * Handles broadcasting settings changes to all peers and receiving updates from peers.
 * 
 * Settings include:
 * - Inactivity timeout duration
 * - USB charging detection enabled
 * - Auto-lock enabled
 * - Device name
 * 
 * Log pattern:
 * DEBUG: "Settings change detected: [key]=[value]"
 * DEBUG: "Broadcasting to [N] peers via TLS"
 * DEBUG: "ACK received from [peer-name]"
 * INFO: "Settings synced (latency: [ms])"
 * ERROR: "Sync failed for peer [name]"
 */
class SettingsSyncManager(
    private val context: Context,
    private val p2pNetwork: P2PNetwork,
    private val scope: CoroutineScope
) : CoroutineScope by scope {

    companion object {
        private const val TAG = "SettingsSync"
    }

    private val gson = Gson()
    private val prefs = Preferences.new(context)
    private val applier by lazy { LocalSettingsApplier.create(context.applicationContext) }
    private val adminManager = DeviceAdminManager(context)
    private val peerDao = WastedP2PDatabase.getInstance(context).peerDao()

    // Observable settings state
    private val _inactivityTimeout = MutableStateFlow(getInactivityTimeout())
    val inactivityTimeout: StateFlow<Long> = _inactivityTimeout

    private val _usbDetectionEnabled = MutableStateFlow(isUsbDetectionEnabled())
    val usbDetectionEnabled: StateFlow<Boolean> = _usbDetectionEnabled

    private val _autoLockEnabled = MutableStateFlow(isAutoLockEnabled())
    val autoLockEnabled: StateFlow<Boolean> = _autoLockEnabled

    private val _lastSyncTime = MutableStateFlow(0L)
    val lastSyncTime: StateFlow<Long> = _lastSyncTime

    private val _localSettings = MutableStateFlow(currentSettingsSnapshot())
    val localSettings: StateFlow<DeviceSettingsSnapshot> = _localSettings

    private val _peerSettings = MutableStateFlow<Map<String, DeviceSettingsSnapshot>>(emptyMap())
    val peerSettings: StateFlow<Map<String, DeviceSettingsSnapshot>> = _peerSettings

    private val recentSettingsRequests = mutableMapOf<String, Long>()

    /**
     * Update inactivity timeout for this device and publish the snapshot to approved peers.
     */
    fun setInactivityTimeout(millis: Long) {
        saveLocalSettings(
            inactivityTimeout = millis,
            usbDetectionEnabled = _usbDetectionEnabled.value,
            autoLockEnabled = _autoLockEnabled.value,
        )
    }

    /**
     * Update USB detection setting for this device and publish the snapshot to approved peers.
     */
    fun setUsbDetectionEnabled(enabled: Boolean) {
        saveLocalSettings(
            inactivityTimeout = _inactivityTimeout.value,
            usbDetectionEnabled = enabled,
            autoLockEnabled = _autoLockEnabled.value,
        )
    }

    /**
     * Update inactivity trigger setting for this device and publish the snapshot to approved peers.
     */
    fun setAutoLockEnabled(enabled: Boolean) {
        saveLocalSettings(
            inactivityTimeout = _inactivityTimeout.value,
            usbDetectionEnabled = _usbDetectionEnabled.value,
            autoLockEnabled = enabled,
        )
    }

    /**
     * Save local settings and announce the current device snapshot to approved peers.
     * Dispatches all blocking prefs/IPC work to IO — safe to call from Main thread.
     */
    fun saveLocalSettings(
        inactivityTimeout: Long,
        usbDetectionEnabled: Boolean,
        autoLockEnabled: Boolean,
    ) {
        Log.d(TAG, "Saving local state")
        scope.launch {
            applyLocalSettings(
                withContext(Dispatchers.IO) { currentSettingsSnapshot() }.copy(
                    inactivityTimeout = inactivityTimeout,
                    usbDetectionEnabled = usbDetectionEnabled,
                    autoLockEnabled = autoLockEnabled,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            announceLocalSettings()
        }
    }

    fun saveLocalSettings(settings: DeviceSettingsSnapshot) {
        Log.d(TAG, "Saving full local state")
        scope.launch {
            applyLocalSettings(settings.copy(updatedAt = System.currentTimeMillis()))
            announceLocalSettings()
        }
    }

    suspend fun sendSettingsToPeer(
        peer: Peer,
        settings: DeviceSettingsSnapshot,
    ): Boolean {
        val payload = SettingsUpdateCommand(
            appEnabled = settings.appEnabled,
            wipeDataEnabled = settings.wipeDataEnabled,
            wipeEmbeddedSimEnabled = settings.wipeEmbeddedSimEnabled,
            remoteResetConfirmationEnabled = settings.remoteResetConfirmationEnabled,
            triggerMask = settings.triggerMask,
            inactivityTimeout = settings.inactivityTimeout,
            tileDelayMs = settings.tileDelayMs,
            applicationOptionsMask = settings.applicationOptionsMask,
            recastEnabled = settings.recastEnabled,
            recastAction = settings.recastAction,
            recastReceiver = settings.recastReceiver,
            recastExtraKey = settings.recastExtraKey,
            recastExtraValue = settings.recastExtraValue,
            usbDetectionEnabled = settings.usbDetectionEnabled,
            autoLockEnabled = settings.autoLockEnabled,
            requestedByDeviceId = getDeviceId(),
            requestedByDeviceName = getDeviceName(),
        )
        val message = Message(
            fromDeviceId = getDeviceId(),
            toDeviceId = peer.deviceId,
            type = MessageType.SETTINGS_CHANGE,
            payload = gson.toJson(payload),
            timestamp = System.currentTimeMillis(),
            requiresAck = true,
        )

        val success = p2pNetwork.sendToPeerWithRetry(peer, message)
        if (success) {
            _lastSyncTime.value = System.currentTimeMillis()
        }
        return success
    }

    suspend fun requestPeerSettings(peer: Peer, force: Boolean = false): Boolean {
        if (peer.pairedAt <= 0L) {
            return false
        }

        val now = System.currentTimeMillis()
        val lastRequestAt = recentSettingsRequests[peer.deviceId] ?: 0L
        if (!force && _peerSettings.value.containsKey(peer.deviceId)) {
            return true
        }
        if (!force && now - lastRequestAt < 3_000L) {
            return true
        }

        recentSettingsRequests[peer.deviceId] = now
        val payload = SettingsRequestPayload(
            requestedByDeviceId = getDeviceId(),
            requestedByDeviceName = getDeviceName(),
        )
        val message = Message(
            fromDeviceId = getDeviceId(),
            toDeviceId = peer.deviceId,
            type = MessageType.SETTINGS_REQUEST,
            payload = gson.toJson(payload),
            timestamp = now,
            requiresAck = true,
        )
        return p2pNetwork.sendToPeerWithRetry(peer, message)
    }

    suspend fun announceLocalSettings(targetPeer: Peer? = null) {
        try {
            // currentSettingsSnapshot does DPM + PackageManager IPC — must not run on Main
            val snapshot = withContext(Dispatchers.IO) { currentSettingsSnapshot() }
            val message = Message(
                fromDeviceId = getDeviceId(),
                toDeviceId = targetPeer?.deviceId ?: "broadcast",
                type = MessageType.SETTINGS_RESPONSE,
                payload = gson.toJson(snapshot),
                timestamp = System.currentTimeMillis(),
                requiresAck = false,
            )

            if (targetPeer == null) {
                p2pNetwork.broadcastToPeers(message)
                _lastSyncTime.value = System.currentTimeMillis()
                Log.i(TAG, "P2P state announced")
            } else {
                p2pNetwork.sendToPeerWithRetry(targetPeer, message)
            }
        } catch (e: Exception) {
            Log.e(TAG, "P2P state update failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Handle incoming request for this device's current settings.
     */
    suspend fun handleSettingsRequestMessage(message: Message) {
        try {
            val peer = peerDao.getPeerById(message.fromDeviceId) ?: return
            Log.d(TAG, "Sending P2P state")
            announceLocalSettings(peer)
        } catch (e: Exception) {
            Log.e(TAG, "P2P state update failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Handle incoming settings snapshot from a peer.
     */
    suspend fun handleSettingsResponseMessage(message: Message) {
        try {
            val snapshot = gson.fromJson(message.payload, DeviceSettingsSnapshot::class.java)
            _peerSettings.value = _peerSettings.value.toMutableMap().apply {
                put(snapshot.ownerDeviceId, snapshot)
            }
            recentSettingsRequests.remove(snapshot.ownerDeviceId)
            Log.d(TAG, "Stored P2P state")
        } catch (e: Exception) {
            Log.e(TAG, "P2P state update failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Handle incoming targeted settings change for this device.
     */
    suspend fun handleSettingsChangeMessage(message: Message): String? {
        try {
            val payload = gson.fromJson(message.payload, SettingsUpdateCommand::class.java)
            Log.d(TAG, "Applying remote update")
            applyLocalSettings(
                withContext(Dispatchers.IO) { currentSettingsSnapshot() }.copy(
                    appEnabled = payload.appEnabled,
                    wipeDataEnabled = payload.wipeDataEnabled,
                    wipeEmbeddedSimEnabled = payload.wipeEmbeddedSimEnabled,
                    remoteResetConfirmationEnabled = payload.remoteResetConfirmationEnabled,
                    triggerMask = payload.triggerMask,
                    inactivityTimeout = payload.inactivityTimeout,
                    tileDelayMs = payload.tileDelayMs,
                    applicationOptionsMask = payload.applicationOptionsMask,
                    recastEnabled = payload.recastEnabled,
                    recastAction = payload.recastAction,
                    recastReceiver = payload.recastReceiver,
                    recastExtraKey = payload.recastExtraKey,
                    recastExtraValue = payload.recastExtraValue,
                    usbDetectionEnabled = payload.usbDetectionEnabled,
                    autoLockEnabled = payload.autoLockEnabled,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            announceLocalSettings()
            Log.d(TAG, "Remote update applied locally")
            return payload.requestedByDeviceName
        } catch (e: Exception) {
            Log.e(TAG, "P2P state update failed: ${e.javaClass.simpleName}")
        }
        return null
    }

    fun forgetPeerSettings(deviceId: String) {
        recentSettingsRequests.remove(deviceId)
        _peerSettings.value = _peerSettings.value.toMutableMap().apply {
            remove(deviceId)
        }
    }

    /**
     * Writes prefs + triggers component-enable IPC on Dispatchers.IO to avoid ANR.
     * Tink crypto writes + PackageManager.setComponentEnabledSetting() are blocking IPC
     * that can take 100-500ms each — must NEVER run on the main thread.
     */
    private suspend fun applyLocalSettings(settings: DeviceSettingsSnapshot) {
        Log.d(TAG, "Applying local state")
        withContext(Dispatchers.IO) {
            applier.applySnapshot(settings)
        }
        // StateFlow updates are thread-safe; emit after IO work is done
        _inactivityTimeout.value = settings.inactivityTimeout
        _usbDetectionEnabled.value = settings.usbDetectionEnabled
        _autoLockEnabled.value = settings.autoLockEnabled
        _localSettings.value = withContext(Dispatchers.IO) { currentSettingsSnapshot() }
        Log.d(TAG, "Local state applied")
    }

    private fun currentSettingsSnapshot(): DeviceSettingsSnapshot {
        val resetSupport = adminManager.getResetSupport()
        return DeviceSettingsSnapshot(
            ownerDeviceId = getDeviceId(),
            ownerDeviceName = getDeviceName(),
            appEnabled = prefs.isEnabled,
            wipeDataEnabled = prefs.isWipeData,
            wipeEmbeddedSimEnabled = prefs.isWipeEmbeddedSim,
            remoteResetConfirmationEnabled = prefs.remoteResetConfirmationEnabled,
            triggerMask = prefs.triggers,
            inactivityTimeout = getInactivityTimeout(),
            tileDelayMs = prefs.triggerTileDelay,
            applicationOptionsMask = prefs.triggerApplicationOptions,
            recastEnabled = prefs.isRecastEnabled,
            recastAction = prefs.recastAction,
            recastReceiver = prefs.recastReceiver,
            recastExtraKey = prefs.recastExtraKey,
            recastExtraValue = prefs.recastExtraValue,
            deviceAdminActive = adminManager.isActive(),
            resetSupported = resetSupport.isSupported,
            resetSupportMessage = resetSupport.userMessage,
            usbDetectionEnabled = isUsbDetectionEnabled(),
            autoLockEnabled = isAutoLockEnabled(),
            updatedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Get current inactivity timeout.
     */
    private fun getInactivityTimeout(): Long {
        return prefs.triggerLockCount * 60_000L
    }

    /**
     * Get USB detection enabled state.
     */
    private fun isUsbDetectionEnabled(): Boolean {
        return prefs.triggers.and(Trigger.USB.value) != 0
    }

    /**
     * Get auto-lock enabled state.
     */
    private fun isAutoLockEnabled(): Boolean {
        return prefs.triggers.and(Trigger.LOCK.value) != 0
    }

    /**
     * Get device ID for identifying source of settings change.
     */
    private fun getDeviceId(): String {
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    }

    private fun getDeviceName(): String {
        return android.os.Build.MODEL ?: "Unknown Device"
    }
}
