package me.lucky.wasted.ui

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import me.lucky.wasted.R
import me.lucky.wasted.p2p.models.Peer
import me.lucky.wasted.p2p.protocol.RemoteControlManager
import me.lucky.wasted.p2p.protocol.SettingsSyncManager
import android.widget.LinearLayout

/**
 * Fragment showing controls for individual connected device.
 * Displays device settings and remote control buttons (lock, wipe, reset).
 */
class DeviceControlFragment : Fragment() {
    
    companion object {
        private const val TAG = "DeviceControlUI"
        private const val ARG_DEVICE_ID = "deviceId"
        private const val ARG_DEVICE_NAME = "deviceName"
    }
    
    private var deviceId: String? = null
    private var deviceName: String? = null
    
    private lateinit var remoteControlManager: RemoteControlManager
    private lateinit var settingsSyncManager: SettingsSyncManager
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            deviceId = it.getString(ARG_DEVICE_ID)
            deviceName = it.getString(ARG_DEVICE_NAME)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Create simple programmatic UI for device control
        val root = LinearLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }
        
        // Device name header
        val nameView = TextView(requireContext()).apply {
            text = "Device: $deviceName"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            textSize = 18f
        }
        root.addView(nameView)
        
        // Settings display
        val settingsView = TextView(requireContext()).apply {
            text = "Settings\nInactivity Timeout: 5min\nUSB Detection: Enabled\nAuto-Lock: Enabled"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(0, 16, 0, 16)
            textSize = 14f
        }
        root.addView(settingsView)
        
        // Lock button
        val lockButton = Button(requireContext()).apply {
            text = "Lock Device"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { showLockConfirmation() }
        }
        root.addView(lockButton)
        
        // Remote Reset button
        val resetButton = Button(requireContext()).apply {
            text = "Reset Device"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(android.graphics.Color.RED)
            setOnClickListener { showResetConfirmation() }
        }
        root.addView(resetButton)
        
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize managers (inject from parent activity/viewmodel in production)
        // For now, create dummy instances
        Log.d(TAG, "Device control fragment created for $deviceName")
    }
    
    private fun showLockConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Lock Device?")
            .setMessage("Lock '$deviceName' remotely?")
            .setPositiveButton("Lock") { _, _ ->
                Log.i(TAG, "User confirmed lock for $deviceName")
                // Call remoteControlManager.lockDeviceLocally()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun showResetConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset Device?")
            .setMessage("Reset '$deviceName'? This will wipe all data and cannot be undone.")
            .setPositiveButton("Reset") { _, _ ->
                Log.i(TAG, "User confirmed reset for $deviceName")
                // Call remoteControlManager.sendRemoteReset(peer)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

/**
 * Dialog for local reset confirmation.
 * Shows when user taps "Reset" on local device.
 */
class LocalResetConfirmationDialog : DialogFragment() {
    
    companion object {
        private const val TAG = "ResetDialog"
    }
    
    private var onConfirm: (() -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    
    fun setCallbacks(onConfirm: () -> Unit, onCancel: () -> Unit) {
        this.onConfirm = onConfirm
        this.onCancel = onCancel
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return AlertDialog.Builder(requireContext())
            .setTitle("Reset Device?")
            .setMessage("Wipe all data? This cannot be undone.")
            .setPositiveButton("Reset") { _, _ ->
                Log.i(TAG, "Reset confirmed by user")
                onConfirm?.invoke()
            }
            .setNegativeButton("Cancel") { _, _ ->
                Log.d(TAG, "Reset cancelled by user")
                onCancel?.invoke()
            }
            .create()
    }
}

/**
 * Dialog for remote reset confirmation from peer.
 * Shows when device receives reset command from peer.
 */
class RemoteResetConfirmationDialog : DialogFragment() {
    
    companion object {
        private const val TAG = "RemoteResetDialog"
        private const val ARG_PEER_NAME = "peerName"
    }
    
    private var onConfirm: (() -> Unit)? = null
    private var onDecline: (() -> Unit)? = null
    
    fun setCallbacks(onConfirm: () -> Unit, onDecline: () -> Unit) {
        this.onConfirm = onConfirm
        this.onDecline = onDecline
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val peerName = arguments?.getString(ARG_PEER_NAME) ?: "Remote Device"
        
        return AlertDialog.Builder(requireContext())
            .setTitle("Remote Reset Request")
            .setMessage("'$peerName' is requesting to reset this device. Wipe all data?")
            .setPositiveButton("Confirm Reset") { _, _ ->
                Log.i(TAG, "Remote reset confirmed for $peerName")
                onConfirm?.invoke()
            }
            .setNegativeButton("Decline") { _, _ ->
                Log.d(TAG, "Remote reset declined from $peerName")
                onDecline?.invoke()
            }
            .create()
    }
}
