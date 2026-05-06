package me.lucky.wasted.p2p.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.*

/**
 * Represents a remote peer device in the P2P network.
 * Stores persistent peer information and pairing state.
 */
@Entity(tableName = "peers")
data class Peer(
    @PrimaryKey
    val deviceId: String,           // Unique device identifier (device hash)
    val deviceName: String,         // User-friendly device name
    val ipAddress: String,          // Current IP address on local network
    val port: Int,                  // TCP port for TLS connection
    val certificateHash: String,    // SHA-256 hash of APK signing certificate
    val pairedAt: Long,             // Timestamp of pairing (milliseconds)
    val lastSeen: Long,             // Timestamp of last successful connection
    val isConnected: Boolean = false, // Current connection status
    val pinHash: String = ""        // SHA-256 hash of pairing PIN (null after first pairing)
) {
    companion object {
        const val TAG = "P2PNetwork"
    }
}

/**
 * Message data model for P2P communication.
 * Supports settings sync, remote control, and acknowledgments.
 */
data class Message(
    val messageId: String = UUID.randomUUID().toString(),
    val fromDeviceId: String,
    val toDeviceId: String,
    val type: MessageType,
    val payload: String,            // JSON-encoded payload
    val timestamp: Long = System.currentTimeMillis(),
    val requiresAck: Boolean = true,
    val isAcked: Boolean = false
)

enum class MessageType {
    PAIRING_REQUEST,                // Initial pairing request
    PAIRING_RESPONSE,               // Pairing accept/reject
    UNPAIR_REQUEST,                 // Revoke trust on both devices
    SETTINGS_CHANGE,                // Settings value changed
    SETTINGS_REQUEST,               // Request current settings
    SETTINGS_RESPONSE,              // Send settings snapshot
    RESET_COMMAND,                  // Remote wipe/lock request
    RESET_ACK,                      // Acknowledgment of reset
    DEVICE_INFO,                    // Device capability advertisement
    HEARTBEAT,                      // Keepalive signal
    ERROR                           // Error message
}

/**
 * Settings sync payload (JSON-serialized in Message.payload)
 */
data class SettingsSyncPayload(
    val key: String,
    val value: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class DeviceSettingsSnapshot(
    val ownerDeviceId: String,
    val ownerDeviceName: String,
    val appEnabled: Boolean,
    val wipeDataEnabled: Boolean,
    val wipeEmbeddedSimEnabled: Boolean,
    val remoteResetConfirmationEnabled: Boolean,
    val triggerMask: Int,
    val inactivityTimeout: Long,
    val tileDelayMs: Long,
    val applicationOptionsMask: Int,
    val recastEnabled: Boolean,
    val recastAction: String,
    val recastReceiver: String,
    val recastExtraKey: String,
    val recastExtraValue: String,
    val deviceAdminActive: Boolean,
    val resetSupported: Boolean,
    val resetSupportMessage: String,
    val usbDetectionEnabled: Boolean,
    val autoLockEnabled: Boolean,
    val updatedAt: Long = System.currentTimeMillis()
)

data class SettingsUpdateCommand(
    val appEnabled: Boolean,
    val wipeDataEnabled: Boolean,
    val wipeEmbeddedSimEnabled: Boolean,
    val remoteResetConfirmationEnabled: Boolean,
    val triggerMask: Int,
    val inactivityTimeout: Long,
    val tileDelayMs: Long,
    val applicationOptionsMask: Int,
    val recastEnabled: Boolean,
    val recastAction: String,
    val recastReceiver: String,
    val recastExtraKey: String,
    val recastExtraValue: String,
    val usbDetectionEnabled: Boolean,
    val autoLockEnabled: Boolean,
    val requestedByDeviceId: String,
    val requestedByDeviceName: String,
    val requestedAt: Long = System.currentTimeMillis()
)

data class SettingsRequestPayload(
    val requestedByDeviceId: String,
    val requestedByDeviceName: String,
    val requestedAt: Long = System.currentTimeMillis()
)

/**
 * Remote reset command payload
 */
data class ResetCommandPayload(
    val type: String,               // "lock" or "wipe"
    val requiresUserConfirmation: Boolean = true
)

/**
 * Device pairing state
 */
enum class PairingState {
    UNPAIRED,
    PAIRING,
    PAIRED,
    PAIRING_FAILED
}
