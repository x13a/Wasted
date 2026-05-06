package me.lucky.wasted.p2p

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.lucky.wasted.Application as WastedApp
import me.lucky.wasted.ApplicationOption
import me.lucky.wasted.Preferences
import me.lucky.wasted.R
import me.lucky.wasted.Trigger
import me.lucky.wasted.Utils
import me.lucky.wasted.admin.DeviceAdminManager
import me.lucky.wasted.databinding.FragmentP2pNetworkBinding
import me.lucky.wasted.p2p.models.DeviceSettingsSnapshot
import me.lucky.wasted.p2p.models.PairingState
import me.lucky.wasted.p2p.models.Peer
import me.lucky.wasted.shizuku.ShizukuManager
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.regex.Pattern

class P2PNetworkFragment : Fragment() {

    companion object {
        private const val TAG = "P2PNetworkFragment"
        private const val MODIFIER_DAYS = 'd'
        private const val MODIFIER_HOURS = 'h'
        private const val MODIFIER_MINUTES = 'm'
    }

    private var _binding: FragmentP2pNetworkBinding? = null
    private val binding get() = _binding!!

    private lateinit var controller: P2PController
    private lateinit var adminManager: DeviceAdminManager
    private var remoteResetDialogVisible = false
    private var remoteResetDialog: androidx.appcompat.app.AlertDialog? = null
    private var renderedPeers: List<Peer> = emptyList()
    private var peerSettingsSnapshots: Map<String, DeviceSettingsSnapshot> = emptyMap()
    private val lockCountPattern by lazy {
        Pattern.compile("^[1-9]\\d*[$MODIFIER_DAYS$MODIFIER_HOURS$MODIFIER_MINUTES]$")
    }

    private val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateLocalDeviceActionsState()
        controller.announceCurrentSettings()
        if (it.resultCode == Activity.RESULT_OK && adminManager.isActive()) {
            showMessage("Device Admin enabled for this phone")
        } else if (!adminManager.isActive()) {
            showMessage("Device Admin is still disabled on this phone")
        }
    }

    private val scanQrLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents ?: return@registerForActivityResult
        viewLifecycleOwner.lifecycleScope.launch {
            val actionResult = controller.pairFromQrPayload(contents)
            showMessage(actionResult.message)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentP2pNetworkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        controller = P2PController.getInstance(requireContext())
        adminManager = DeviceAdminManager(requireContext())
        // Only start P2P network if the user has explicitly enabled it
        if (Preferences.new(requireContext()).p2pEnabled) {
            controller.start()
        }
        setupUi()
        observeState()
        updateLocalDeviceActionsState()
    }

    override fun onResume() {
        super.onResume()
        if (this::adminManager.isInitialized) {
            updateLocalDeviceActionsState()
            refreshP2pSetupCard()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupUi() = with(binding) {
        // ── P2P enabled toggle ──────────────────────────────────────────────────
        val prefs = Preferences.new(requireContext())
        p2pEnabledSwitch.isChecked = prefs.p2pEnabled
        setP2pContentEnabled(prefs.p2pEnabled)
        p2pEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            // Update visual state immediately on main thread
            setP2pContentEnabled(isChecked)
            // Dispatch I/O work off main thread to avoid ANR
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                Preferences.new(requireContext()).p2pEnabled = isChecked
                Utils(requireContext()).updateForegroundRequiredEnabled()
                withContext(Dispatchers.Main) {
                    if (isChecked) controller.start() else controller.stop()
                }
            }
        }
        // ───────────────────────────────────────────────────────────────────────
        helpButton.setOnClickListener { showHelpDialog() }
        pairingHelpButton.setOnClickListener { showPairingHelpDialog() }
        settingsInfoButton.setOnClickListener { showSettingsHelpDialog() }
        localActionsInfoButton.setOnClickListener { showLocalActionsHelpDialog() }
        enableAdminButton.setOnClickListener { requestDeviceAdmin() }
        p2pSetupAction.setOnClickListener { jumpToMainTab() }

        localTimeoutEditText.doAfterTextChanged {
            validateLocalTimeoutInput()
        }

        localTileDelaySlider.addOnChangeListener { _, value, _ ->
            localTileDelayValue.text = formatTileDelayLabel((value * 1000).toLong())
        }

        localWipeDataSwitch.setOnCheckedChangeListener { _, isChecked ->
            localWipeEmbeddedSimSwitch.isEnabled = isChecked
            if (!isChecked) {
                localWipeEmbeddedSimSwitch.isChecked = false
            }
        }

        localApplicationSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateLocalApplicationOptionsState(isChecked)
        }

        localRecastSwitch.setOnCheckedChangeListener { _, isChecked ->
            updateLocalRecastInputsState(isChecked)
        }

        generatePinButton.setOnClickListener {
            val pin = controller.generatePairingPin()
            pairingPinValue.text = pin.chunked(3).joinToString(" ")
            pairingStatusText.text = "Share this PIN or QR with a device you trust. It stays valid for 5 minutes."
            showMessage("Pairing PIN ready")
        }

        showQrButton.setOnClickListener {
            val pin = controller.getOrCreatePairingPin()
            pairingPinValue.text = pin.chunked(3).joinToString(" ")
            showQrDialog(controller.buildPairingQrPayload(pin))
        }

        scanQrButton.setOnClickListener {
            val options = ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the other device's pairing QR")
                .setBeepEnabled(true)
                .setOrientationLocked(true)
                .setCaptureActivity(PortraitCaptureActivity::class.java)
            scanQrLauncher.launch(options)
        }

        applySettingsButton.setOnClickListener {
            val currentSnapshot = controller.localSettings.value
            val updatedSettings = buildSettingsSnapshot(
                baseSnapshot = currentSnapshot,
                appEnabled = localAppEnabledSwitch.isChecked,
                wipeDataEnabled = localWipeDataSwitch.isChecked,
                wipeEmbeddedSimEnabled = localWipeEmbeddedSimSwitch.isChecked,
                remoteResetConfirmationEnabled = localRemoteResetConfirmationSwitch.isChecked,
                timeoutInput = localTimeoutEditText.text?.toString()?.trim().orEmpty(),
                panicKitEnabled = localPanicKitSwitch.isChecked,
                tileEnabled = localTileSwitch.isChecked,
                tileDelayMs = (localTileDelaySlider.value * 1000).toLong(),
                shortcutEnabled = localShortcutSwitch.isChecked,
                broadcastEnabled = localBroadcastSwitch.isChecked,
                notificationEnabled = localNotificationSwitch.isChecked,
                usbEnabled = localUsbSwitch.isChecked,
                inactivityEnabled = localLockSwitch.isChecked,
                applicationEnabled = localApplicationSwitch.isChecked,
                signalEnabled = localSignalSwitch.isChecked,
                telegramEnabled = localTelegramSwitch.isChecked,
                threemaEnabled = localThreemaSwitch.isChecked,
                sessionEnabled = localSessionSwitch.isChecked,
                recastEnabled = localRecastSwitch.isChecked,
                recastAction = localRecastActionEditText.text?.toString()?.trim().orEmpty(),
                recastReceiver = localRecastReceiverEditText.text?.toString()?.trim().orEmpty(),
                recastExtraKey = localRecastExtraKeyEditText.text?.toString()?.trim().orEmpty(),
                recastExtraValue = localRecastExtraValueEditText.text?.toString()?.trim().orEmpty(),
            )
            if (updatedSettings == null) {
                localTimeoutInputLayout.error = getString(R.string.trigger_lock_time_error)
                showMessage(getString(R.string.trigger_lock_time_error))
                return@setOnClickListener
            }

            controller.saveLocalSettings(updatedSettings)
            showMessage("This phone's Wasted settings were saved and shared with approved phones")
        }

        lockDeviceButton.setOnClickListener {
            if (!adminManager.isActive()) {
                showAdminRequiredDialog("Lock This Device")
                return@setOnClickListener
            }
            val locked = controller.remoteControlManager.lockDeviceLocally()
            showMessage(if (locked) "Device lock requested" else "Device Admin is not active")
        }

        localResetButton.setOnClickListener {
            if (!adminManager.isActive()) {
                showAdminRequiredDialog("Reset This Device")
                return@setOnClickListener
            }

            val resetSupport = adminManager.getResetSupport()
            if (!resetSupport.isSupported) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Reset unavailable")
                    .setMessage(resetSupport.userMessage)
                    .setPositiveButton("OK", null)
                    .show()
                return@setOnClickListener
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Reset this device?")
                .setMessage("This wipes device data and cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset") { _, _ ->
                    val result = controller.remoteControlManager.executeLocalReset()
                    showMessage(result.userMessage)
                }
                .show()
        }
    }

    /**
     * Enables or disables all P2P content cards (everything below the header toggle card).
     * Uses recursive alpha + isEnabled so every leaf view responds correctly.
     */
    private fun setP2pContentEnabled(enabled: Boolean) {
        val cards = binding.p2pContentCards
        for (i in 1 until cards.childCount) { // index 0 = header card (always active)
            setViewTreeEnabled(cards.getChildAt(i), enabled)
        }
    }

    private fun setViewTreeEnabled(view: View, enabled: Boolean) {
        view.alpha = if (enabled) 1.0f else 0.38f
        view.isEnabled = enabled
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setViewTreeEnabled(view.getChildAt(i), enabled)
            }
        }
    }

    private fun observeState() {
        // repeatOnLifecycle(STARTED): all collectors pause when the fragment is not visible
        // (app backgrounded / screen off). This stops the 5-second heartbeat DB updates
        // from triggering renderPeers() on the main thread in the background, which was
        // a primary cause of the ANR.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    controller.connectedPeers.collectLatest { peers ->
                        val approvedPeers = peers.filter { it.pairedAt > 0L }
                        binding.statusHeadline.text = when (approvedPeers.size) {
                            0 -> "No approved device connected yet"
                            1 -> "1 approved device connected"
                            else -> "${approvedPeers.size} approved devices connected"
                        }
                        binding.statusDetail.text = when (approvedPeers.size) {
                            0 -> "Keep both phones on the same Wi-Fi or hotspot. Pair a phone below before you change its settings or send a reset request."
                            else -> "Approved phones show their own current settings here and can be updated one by one."
                        }
                    }
                }
                launch {
                    controller.allPeers.collectLatest { peers ->
                        renderedPeers = peers
                        renderPeers(peers)
                    }
                }
                launch {
                    controller.peerSettings.collectLatest { snapshots ->
                        peerSettingsSnapshots = snapshots
                        renderPeers(renderedPeers)
                    }
                }
                launch {
                    controller.currentPin.collectLatest { pin ->
                        binding.pairingPinValue.text = pin?.chunked(3)?.joinToString(" ") ?: "PIN not generated yet"
                    }
                }
                launch {
                    controller.pairingState.collectLatest { state ->
                        binding.pairingStatusText.text = when (state) {
                            PairingState.UNPAIRED -> "🔓 Ready to pair. Tap 'Generate PIN' or 'Show QR' to start. Tap on info icon for full setup guide."
                            PairingState.PAIRING -> "⏳ Pairing active. Ask the other device to enter this PIN or scan the QR. Valid for 5 minutes."
                            PairingState.PAIRED -> "✓ Paired! Both phones can now sync settings. To enable full factory reset: set Device Owner on BOTH phones (see setup guide)."
                            PairingState.PAIRING_FAILED -> "❌ Pairing failed. Check the PIN/QR and network connection, then try again."
                        }
                    }
                }
                launch {
                    controller.pairingError.collectLatest { error ->
                        if (!error.isNullOrBlank()) {
                            showMessage(error)
                        }
                    }
                }
                launch {
                    controller.uiMessages.collectLatest { message ->
                        showMessage(message)
                    }
                }
                launch {
                    controller.localSettings.collectLatest { snapshot ->
                        controller.remoteControlManager.handleRemoteResetConfirmationSettingChanged(
                            snapshot.remoteResetConfirmationEnabled,
                        )
                        renderLocalSettings(snapshot)
                    }
                }
                launch {
                    controller.settingsSyncManager.lastSyncTime.collectLatest { timestamp ->
                        binding.lastSyncText.text = if (timestamp == 0L) {
                            "No device status shared yet"
                        } else {
                            "Last local settings update shared at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))}"
                        }
                    }
                }
                launch {
                    controller.pendingReset.collectLatest { pending ->
                        if (pending == null) {
                            remoteResetDialog?.dismiss()
                            remoteResetDialog = null
                            remoteResetDialogVisible = false
                            return@collectLatest
                        }
                        if (remoteResetDialogVisible) {
                            return@collectLatest
                        }
                        remoteResetDialogVisible = true
                        remoteResetDialog = MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Remote reset approval")
                            .setMessage("${pending.second} asked to reset this device. This action is irreversible.")
                            .setNegativeButton("Decline") { _, _ ->
                                controller.remoteControlManager.declineRemoteReset()
                                remoteResetDialogVisible = false
                            }
                            .setPositiveButton("Confirm") { _, _ ->
                                controller.remoteControlManager.confirmRemoteReset()
                                remoteResetDialogVisible = false
                            }
                            .setOnDismissListener {
                                remoteResetDialogVisible = false
                                remoteResetDialog = null
                            }
                            .show()
                    }
                }
            }
        }
    }

    private fun renderPeers(peers: List<Peer>) {
        binding.peerContainer.removeAllViews()
        binding.peerEmptyState.isVisible = peers.isEmpty()

        peers.forEach { peer ->
            val card = com.google.android.material.card.MaterialCardView(requireContext()).apply {
                radius = 20f
                cardElevation = 1f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    bottomMargin = 12.dp
                }
            }

            val content = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(18.dp, 18.dp, 18.dp, 18.dp)
            }

            val title = TextView(requireContext()).apply {
                text = peer.deviceName
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
            }
            content.addView(title)

            val subtitle = TextView(requireContext()).apply {
                val approved = if (peer.pairedAt > 0L) "Approved" else "Not paired"
                val status = if (peer.isConnected) "Reachable" else "Offline"
                text = "$approved • $status • ${peer.ipAddress}:${peer.port}"
                textSize = 13f
            }
            content.addView(subtitle)

            val detail = TextView(requireContext()).apply {
                if (peer.pairedAt > 0L && peer.isConnected && peerSettingsSnapshots[peer.deviceId] == null) {
                    controller.requestPeerSettings(peer)
                }

                text = if (peer.pairedAt > 0L) {
                    buildPeerSettingsText(peer)
                } else {
                    "Pair this phone first before it can receive synced settings or reset requests."
                }
                textSize = 13f
                setPadding(0, 10.dp, 0, 0)
            }
            content.addView(detail)

            val buttonRow = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 14.dp, 0, 0)
                weightSum = 3f
            }

            if (peer.pairedAt <= 0L) {
                val pairButton = createPeerActionButton("Enter Code").apply {
                    text = "Enter Code"
                    setOnClickListener { showManualPairDialog(peer) }
                }
                buttonRow.addView(pairButton)

                val scanButton = createPeerActionButton("Scan QR").apply {
                    text = "Scan QR"
                    setOnClickListener { launchQrScanner() }
                }
                buttonRow.addView(scanButton)
            } else {
                val editSettingsButton = createPeerActionButton("Edit Settings").apply {
                    setOnClickListener {
                        val snapshot = peerSettingsSnapshots[peer.deviceId]
                        if (snapshot == null) {
                            controller.requestPeerSettings(peer, force = true)
                            showMessage("Requesting current settings from ${peer.deviceName}")
                        } else {
                            showPeerSettingsDialog(peer, snapshot)
                        }
                    }
                }
                buttonRow.addView(editSettingsButton)

                val refreshButton = createPeerActionButton("Refresh").apply {
                    setOnClickListener {
                        controller.requestPeerSettings(peer, force = true)
                        showMessage("Refreshing settings from ${peer.deviceName}")
                    }
                }
                buttonRow.addView(refreshButton)
            }

            val peerSnapshot = peerSettingsSnapshots[peer.deviceId]
            val resetButton = createPeerActionButton("Remote Reset").apply {
                text = "Remote Reset"
                isEnabled = peer.pairedAt > 0L && peerSnapshot?.resetSupported != false
                setOnClickListener {
                    val resetMessage = when (peerSnapshot?.remoteResetConfirmationEnabled) {
                        true -> "Send a protected reset request to ${peer.deviceName}. ${peer.deviceName} must still confirm before wiping."
                        false -> "Send a reset request to ${peer.deviceName}. ${peer.deviceName} is currently set to execute remote resets immediately without showing a confirmation dialog there."
                        null -> "Send a reset request to ${peer.deviceName}. If that phone requires confirmation for remote reset, it will ask there before wiping."
                    }
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Reset ${peer.deviceName}?")
                        .setMessage(resetMessage)
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Send") { _, _ ->
                            controller.remoteControlManager.sendRemoteReset(peer)
                            showMessage("Reset request queued for ${peer.deviceName}")
                        }
                        .show()
                }
            }
            buttonRow.addView(resetButton)
            normalizeButtonRow(buttonRow)

            content.addView(buttonRow)

            if (peer.pairedAt > 0L) {
                val secondaryRow = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 10.dp, 0, 0)
                }
                val unpairButton = createWideActionButton("Unpair ${peer.deviceName}").apply {
                    setOnClickListener {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Unpair ${peer.deviceName}?")
                            .setMessage("This removes approval for ${peer.deviceName}. It will stay visible on the network, but it must be paired again before its settings can be changed or it can receive reset requests.")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Unpair") { _, _ ->
                                controller.unpairPeer(peer)
                            }
                            .show()
                    }
                }
                secondaryRow.addView(unpairButton)
                content.addView(secondaryRow)
            }

            card.addView(content)
            binding.peerContainer.addView(card)
        }
    }

    private fun showPeerSettingsDialog(peer: Peer, snapshot: DeviceSettingsSnapshot) {
        val header = TextView(requireContext()).apply {
            text = "Changes apply only to ${peer.deviceName}. The target phone saves them locally and every approved phone updates its live view when that phone reports back."
            textSize = 14f
        }

        val appEnabledSwitch = SwitchMaterial(requireContext()).apply {
            text = "Enable Wasted"
            isChecked = snapshot.appEnabled
        }

        val wipeDataSwitch = SwitchMaterial(requireContext()).apply {
            text = "Wipe data on trigger"
            isChecked = snapshot.wipeDataEnabled
        }

        val wipeEmbeddedSimSwitch = SwitchMaterial(requireContext()).apply {
            text = "Wipe eSIM with reset"
            isChecked = snapshot.wipeEmbeddedSimEnabled
            isEnabled = snapshot.wipeDataEnabled
        }
        val remoteResetConfirmationSwitch = SwitchMaterial(requireContext()).apply {
            text = "Require confirmation before remote reset"
            isChecked = snapshot.remoteResetConfirmationEnabled
        }
        wipeDataSwitch.setOnCheckedChangeListener { _, isChecked ->
            wipeEmbeddedSimSwitch.isEnabled = isChecked
            if (!isChecked) {
                wipeEmbeddedSimSwitch.isChecked = false
            }
        }

        val timeoutInputLayout = TextInputLayout(requireContext()).apply {
            helperText = getString(R.string.trigger_lock_time_helper_text)
            isHelperTextEnabled = true
            isErrorEnabled = true
            setPadding(0, 8.dp, 0, 0)
        }

        val timeoutInput = TextInputEditText(timeoutInputLayout.context).apply {
            hint = getString(R.string.trigger_lock_time_hint)
            setText(formatTimeoutInput((snapshot.inactivityTimeout / 60_000L).toInt().coerceAtLeast(1)))
        }
        timeoutInputLayout.addView(timeoutInput)
        timeoutInput.doAfterTextChanged {
            timeoutInputLayout.error = if (isValidTimeoutInput(it?.toString().orEmpty())) null else getString(R.string.trigger_lock_time_error)
        }

        val usbSwitch = SwitchMaterial(requireContext()).apply {
            text = "Enable USB trigger"
            isChecked = snapshot.usbDetectionEnabled
        }

        val lockSwitch = SwitchMaterial(requireContext()).apply {
            text = "Enable inactivity trigger"
            isChecked = snapshot.autoLockEnabled
        }

        val panicKitSwitch = SwitchMaterial(requireContext()).apply {
            text = "Enable PanicKit trigger"
            isChecked = hasFlag(snapshot.triggerMask, Trigger.PANIC_KIT.value)
        }

        val tileSwitch = SwitchMaterial(requireContext()).apply {
            text = "Enable tile trigger"
            isChecked = hasFlag(snapshot.triggerMask, Trigger.TILE.value)
        }

        val tileDelayLabel = TextView(requireContext()).apply {
            text = formatTileDelayLabel(snapshot.tileDelayMs)
            setPadding(0, 8.dp, 0, 0)
        }

        val tileDelaySlider = Slider(requireContext()).apply {
            valueFrom = 0f
            valueTo = 3f
            stepSize = 0.5f
            value = (snapshot.tileDelayMs / 1000f).coerceIn(0f, 3f)
            addOnChangeListener { _, value, _ ->
                tileDelayLabel.text = formatTileDelayLabel((value * 1000).toLong())
            }
        }

        val shortcutSwitch = SwitchMaterial(requireContext()).apply {
            text = "Enable shortcut trigger"
            isChecked = hasFlag(snapshot.triggerMask, Trigger.SHORTCUT.value)
        }

        val broadcastSwitch = SwitchMaterial(requireContext()).apply {
            text = "Enable broadcast trigger"
            isChecked = hasFlag(snapshot.triggerMask, Trigger.BROADCAST.value)
        }

        val notificationSwitch = SwitchMaterial(requireContext()).apply {
            text = "Enable notification trigger"
            isChecked = hasFlag(snapshot.triggerMask, Trigger.NOTIFICATION.value)
        }

        val applicationSwitch = SwitchMaterial(requireContext()).apply {
            text = "Enable fake application trigger"
            isChecked = hasFlag(snapshot.triggerMask, Trigger.APPLICATION.value)
        }

        val signalSwitch = SwitchMaterial(requireContext()).apply {
            text = "Signal"
            isChecked = hasFlag(snapshot.applicationOptionsMask, ApplicationOption.SIGNAL.value)
        }

        val telegramSwitch = SwitchMaterial(requireContext()).apply {
            text = "Telegram"
            isChecked = hasFlag(snapshot.applicationOptionsMask, ApplicationOption.TELEGRAM.value)
        }

        val threemaSwitch = SwitchMaterial(requireContext()).apply {
            text = "Threema"
            isChecked = hasFlag(snapshot.applicationOptionsMask, ApplicationOption.THREEMA.value)
        }

        val sessionSwitch = SwitchMaterial(requireContext()).apply {
            text = "Session"
            isChecked = hasFlag(snapshot.applicationOptionsMask, ApplicationOption.SESSION.value)
        }

        val appOptionSwitches = listOf(signalSwitch, telegramSwitch, threemaSwitch, sessionSwitch)
        appOptionSwitches.forEach { it.isEnabled = applicationSwitch.isChecked }
        applicationSwitch.setOnCheckedChangeListener { _, isChecked ->
            appOptionSwitches.forEach { option -> option.isEnabled = isChecked }
        }

        val recastSwitch = SwitchMaterial(requireContext()).apply {
            text = "Enable recast broadcast"
            isChecked = snapshot.recastEnabled
        }

        val recastActionInput = EditText(requireContext()).apply {
            hint = "Action"
            setText(snapshot.recastAction)
        }

        val recastReceiverInput = EditText(requireContext()).apply {
            hint = "Receiver"
            setText(snapshot.recastReceiver)
        }

        val recastExtraKeyInput = EditText(requireContext()).apply {
            hint = "Extra key"
            setText(snapshot.recastExtraKey)
        }

        val recastExtraValueInput = EditText(requireContext()).apply {
            hint = "Extra value"
            setText(snapshot.recastExtraValue)
        }

        val recastInputs = listOf(recastActionInput, recastReceiverInput, recastExtraKeyInput, recastExtraValueInput)
        recastInputs.forEach { it.isEnabled = recastSwitch.isChecked }
        recastSwitch.setOnCheckedChangeListener { _, isChecked ->
            recastInputs.forEach { input -> input.isEnabled = isChecked }
        }

        val adminState = TextView(requireContext()).apply {
            text = if (snapshot.deviceAdminActive) {
                "Device Admin active on ${peer.deviceName}"
            } else {
                "Device Admin inactive on ${peer.deviceName}; lock and local reset actions on that phone will not work until it is enabled there."
            }
            setPadding(0, 12.dp, 0, 0)
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 8.dp, 20.dp, 0)
            addView(header)
            addView(appEnabledSwitch)
            addView(wipeDataSwitch)
            addView(wipeEmbeddedSimSwitch)
            addView(remoteResetConfirmationSwitch)
            addView(createSectionLabel("Trigger Settings"))
            addView(timeoutInputLayout)
            addView(panicKitSwitch)
            addView(tileSwitch)
            addView(tileDelayLabel)
            addView(tileDelaySlider)
            addView(shortcutSwitch)
            addView(broadcastSwitch)
            addView(notificationSwitch)
            addView(usbSwitch)
            addView(lockSwitch)
            addView(applicationSwitch)
            addView(signalSwitch)
            addView(telegramSwitch)
            addView(threemaSwitch)
            addView(sessionSwitch)
            addView(createSectionLabel("Recast"))
            addView(recastSwitch)
            addView(recastActionInput)
            addView(recastReceiverInput)
            addView(recastExtraKeyInput)
            addView(recastExtraValueInput)
            addView(adminState)
        }

        val scrollView = ScrollView(requireContext()).apply {
            addView(content)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Edit ${peer.deviceName} Settings")
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save", null)
            .show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val updatedSettings = buildSettingsSnapshot(
                baseSnapshot = snapshot,
                appEnabled = appEnabledSwitch.isChecked,
                wipeDataEnabled = wipeDataSwitch.isChecked,
                wipeEmbeddedSimEnabled = wipeEmbeddedSimSwitch.isChecked,
                remoteResetConfirmationEnabled = remoteResetConfirmationSwitch.isChecked,
                timeoutInput = timeoutInput.text?.toString()?.trim().orEmpty(),
                panicKitEnabled = panicKitSwitch.isChecked,
                tileEnabled = tileSwitch.isChecked,
                tileDelayMs = (tileDelaySlider.value * 1000).toLong(),
                shortcutEnabled = shortcutSwitch.isChecked,
                broadcastEnabled = broadcastSwitch.isChecked,
                notificationEnabled = notificationSwitch.isChecked,
                usbEnabled = usbSwitch.isChecked,
                inactivityEnabled = lockSwitch.isChecked,
                applicationEnabled = applicationSwitch.isChecked,
                signalEnabled = signalSwitch.isChecked,
                telegramEnabled = telegramSwitch.isChecked,
                threemaEnabled = threemaSwitch.isChecked,
                sessionEnabled = sessionSwitch.isChecked,
                recastEnabled = recastSwitch.isChecked,
                recastAction = recastActionInput.text?.toString()?.trim().orEmpty(),
                recastReceiver = recastReceiverInput.text?.toString()?.trim().orEmpty(),
                recastExtraKey = recastExtraKeyInput.text?.toString()?.trim().orEmpty(),
                recastExtraValue = recastExtraValueInput.text?.toString()?.trim().orEmpty(),
            )
            if (updatedSettings == null) {
                timeoutInputLayout.error = getString(R.string.trigger_lock_time_error)
                return@setOnClickListener
            }

            controller.updatePeerSettings(peer = peer, settings = updatedSettings)
            dialog.dismiss()
        }
    }

    private fun showManualPairDialog(peer: Peer) {
        val copy = TextView(requireContext()).apply {
            text = "Ask ${peer.deviceName} to generate a 6-digit code or QR on its Pair A Device section. You can type the code here or scan the QR instead."
            textSize = 14f
        }

        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Enter 6-digit PIN"
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 8.dp, 20.dp, 0)
            addView(copy)
            addView(input)
        }

        val scrollView = ScrollView(requireContext()).apply {
            addView(content)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Pair with ${peer.deviceName}")
            .setView(scrollView)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Scan QR") { _, _ ->
                launchQrScanner()
            }
            .setPositiveButton("Send Request", null)
            .show()

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pin = input.text?.toString()?.trim().orEmpty()
            if (pin.length != 6) {
                showMessage("PIN must be 6 digits")
            } else {
                controller.sendPairingRequest(peer, pin)
                dialog.dismiss()
            }
        }
    }

    private fun showQrDialog(payload: String) {
        val size = (resources.displayMetrics.widthPixels * 0.72f).toInt().coerceAtLeast(260.dp)
        val imageView = ImageView(requireContext()).apply {
            layoutParams = FrameLayout.LayoutParams(size, size)
            setImageBitmap(renderQrCode(payload, size))
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        val pinText = TextView(requireContext()).apply {
            text = "Code: ${controller.getOrCreatePairingPin().chunked(3).joinToString(" ")}"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 12.dp, 0, 0)
        }

        val bodyText = TextView(requireContext()).apply {
            text = "Open Pair A Device on the other phone and scan this QR. If camera access is not convenient, type the code shown below instead."
            textSize = 14f
            setPadding(0, 12.dp, 0, 0)
        }

        val content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20.dp, 20.dp, 20.dp, 28.dp)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            addView(imageView)
            addView(pinText)
            addView(bodyText)
        }

        val dialog = BottomSheetDialog(requireContext())
        dialog.setContentView(content)
        dialog.show()
    }

    private fun renderQrCode(content: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun showHelpDialog() {
        showScrollableInfoDialog(
            title = "Peer Control Help",
            body =
                "🔒 DEVICE OWNER (ANDROID 14+ ONLY):\n" +
                "On Android 14+, set up Device Owner to enable full factory reset capability. On Android 13 and below, Device Admin alone is sufficient.\n\n" +
                "🔗 PAIRING FLOW:\n" +
                "1. One phone: Tap 'Generate PIN' or 'Show QR'\n" +
                "2. Other phone: Scan QR or enter PIN code\n" +
                "3. Pairing completes automatically (no approval needed)\n\n" +
                "✓ ONCE PAIRED:\n" +
                "• View each phone's settings\n" +
                "• Edit another phone's settings\n" +
                "• Send remote reset requests (confirmation optional per-device)\n" +
                "• Peer discovery is automatic on same local network\n\n" +
                "🔐 SAFETY:\n" +
                "• Remote reset confirmation can be toggled per-device in settings\n" +
                "• Unapproved (not paired) phones cannot receive changes or requests\n" +
                "• All traffic is encrypted (TLS) and stays on local network"
        )
    }

    private fun showPairingHelpDialog() {
        showScrollableInfoDialog(
            title = "Pairing Guide",
            body =
                "✓ Pairing works on all Android versions.\n\n" +
                "🔗 TO PAIR ANOTHER PHONE:\n" +
                "1. On this phone: Tap 'Generate PIN' or 'Show QR'\n" +
                "2. On the other phone:\n" +
                "   • Open Wasted → P2P tab\n" +
                "   • Scan this phone's QR code, OR\n" +
                "   • Enter the PIN code\n" +
                "3. Pairing completes automatically — both phones exchange settings\n\n" +
                "📌 DEVICE OWNER (Android 14+ only):\n" +
                "If you want factory reset to work via P2P on Android 14+, set up Device Owner using the 'Setup Device Owner' guide card at the top of the P2P screen.\n\n" +
                "💬 RESET CONFIRMATION:\n" +
                "By default, remote resets execute immediately. To require confirmation, toggle 'Require confirmation before remote reset' in This Device Settings.\n\n" +
                "🔐 FULL CONTROL:\n" +
                "Even with confirmation disabled, all connected phones retain full control. If they toggle confirmation back on on either phone, the reset dialog is canceled."
        )
    }

    private fun showSettingsHelpDialog() {
        showScrollableInfoDialog(
            title = "This Device Settings",
            body =
                "This section edits only this phone.\n\n" +
                "📝 SETTINGS MANAGED HERE:\n" +
                "Wasted enable state, wipe options, trigger toggles, inactivity timeout, tile delay, fake applications, and recast fields.\n\n" +
                "💬 REMOTE RESET CONFIRMATION:\n" +
                "By default OFF. When enabled, remote reset requests show a confirmation dialog before executing. When disabled, resets execute immediately, but all the connected phones retain full control — toggling confirmation back on will cancel the reset.\n\n" +
                "⏱️ TIMEOUT FORMAT:\n" +
                "Use format like: 7d (days), 48h (hours), or 120m (minutes).\n\n" +
                "💾 SAVING:\n" +
                "Saves values on this phone and syncs state with paired peers for their device lists. To change another phone's settings, use that phone's card in the Devices section."
        )
    }

    private fun showLocalActionsHelpDialog() {
        showScrollableInfoDialog(
            title = "This Device Actions",
            body =
                "These actions affect only the phone in your hand.\n\n" +
                "📋 ENABLE DEVICE ADMIN:\n" +
                "Grants Wasted system privilege for locking.\n" +
                "• Android 14+: Locking only (reset requires Device Owner via P2P setup)\n" +
                "• Android 13 and below: Locking AND factory reset\n\n" +
                "🔒 DEVICE OWNER SETUP (Android 14+ ONLY):\n" +
                "Only needed on Android 14 and above. Use the 'Setup Device Owner' guide at the top of the P2P screen. Once set up:\n" +
                "• Enables full factory reset capability\n" +
                "• Works reliably for local and remote resets\n" +
                "• Required for P2P remote control on Android 14+\n\n" +
                "⚙️ LOCK & RESET BUTTONS:\n" +
                "• Lock: Sends to lock screen immediately (no data loss)\n" +
                "• Reset: Factory resets with confirmation (wipes data if enabled)"
        )
    }

    private fun requestDeviceAdmin() {
        deviceAdminLauncher.launch(adminManager.makeRequestIntent())
    }

    private fun showAdminRequiredDialog(actionLabel: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Enable Device Admin")
            .setMessage("$actionLabel needs Device Admin on this phone. Enable it now so Wasted can lock or reset this device when asked.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Enable") { _, _ -> requestDeviceAdmin() }
            .show()
    }

    private fun updateLocalDeviceActionsState() {
        // isActive/getResetSupport/getManagementSummary all do DPM + PackageManager IPC;
        // each call can take 100-500ms on slow devices — MUST NOT run on Main thread.
        viewLifecycleOwner.lifecycleScope.launch {
            val (active, resetSupport, summary) = withContext(Dispatchers.IO) {
                Log.d(TAG, "updateLocalDeviceActionsState: querying DPM/PM on IO")
                Triple(
                    adminManager.isActive(),
                    adminManager.getResetSupport(),
                    adminManager.getManagementSummary(),
                )
            }
            binding.localAdminStatusText.text = summary
            binding.enableAdminButton.isVisible = !active
            binding.localActionsDescription.text = if (active) {
                if (resetSupport.isSupported) {
                    "Use these only for this phone. Reset always asks for confirmation before wiping."
                } else {
                    "Lock works on this phone, but reset is unavailable here. ${resetSupport.userMessage}"
                }
            } else {
                "Lock and reset on this phone need Device Admin first. Use Enable Device Admin below, then come back to these actions."
            }
        }
    }

    private fun renderLocalSettings(snapshot: DeviceSettingsSnapshot) = with(binding) {
        localAppEnabledSwitch.isChecked = snapshot.appEnabled
        localWipeDataSwitch.isChecked = snapshot.wipeDataEnabled
        localWipeEmbeddedSimSwitch.isChecked = snapshot.wipeEmbeddedSimEnabled
        localWipeEmbeddedSimSwitch.isEnabled = snapshot.wipeDataEnabled
        localRemoteResetConfirmationSwitch.isChecked = snapshot.remoteResetConfirmationEnabled
        localTimeoutEditText.setTextIfChanged(formatTimeoutInput((snapshot.inactivityTimeout / 60_000L).toInt().coerceAtLeast(1)))
        localTimeoutInputLayout.error = null
        localPanicKitSwitch.isChecked = hasFlag(snapshot.triggerMask, Trigger.PANIC_KIT.value)
        localTileSwitch.isChecked = hasFlag(snapshot.triggerMask, Trigger.TILE.value)
        localTileDelayValue.text = formatTileDelayLabel(snapshot.tileDelayMs)
        val tileDelaySeconds = (snapshot.tileDelayMs / 1000f).coerceIn(0f, 3f)
        if (localTileDelaySlider.value != tileDelaySeconds) {
            localTileDelaySlider.value = tileDelaySeconds
        }
        localShortcutSwitch.isChecked = hasFlag(snapshot.triggerMask, Trigger.SHORTCUT.value)
        localBroadcastSwitch.isChecked = hasFlag(snapshot.triggerMask, Trigger.BROADCAST.value)
        localNotificationSwitch.isChecked = hasFlag(snapshot.triggerMask, Trigger.NOTIFICATION.value)
        localUsbSwitch.isChecked = snapshot.usbDetectionEnabled
        localLockSwitch.isChecked = snapshot.autoLockEnabled
        localApplicationSwitch.isChecked = hasFlag(snapshot.triggerMask, Trigger.APPLICATION.value)
        localSignalSwitch.isChecked = hasFlag(snapshot.applicationOptionsMask, ApplicationOption.SIGNAL.value)
        localTelegramSwitch.isChecked = hasFlag(snapshot.applicationOptionsMask, ApplicationOption.TELEGRAM.value)
        localThreemaSwitch.isChecked = hasFlag(snapshot.applicationOptionsMask, ApplicationOption.THREEMA.value)
        localSessionSwitch.isChecked = hasFlag(snapshot.applicationOptionsMask, ApplicationOption.SESSION.value)
        updateLocalApplicationOptionsState(localApplicationSwitch.isChecked)
        localRecastSwitch.isChecked = snapshot.recastEnabled
        updateLocalRecastInputsState(snapshot.recastEnabled)
        localRecastActionEditText.setTextIfChanged(snapshot.recastAction)
        localRecastReceiverEditText.setTextIfChanged(snapshot.recastReceiver)
        localRecastExtraKeyEditText.setTextIfChanged(snapshot.recastExtraKey)
        localRecastExtraValueEditText.setTextIfChanged(snapshot.recastExtraValue)
    }

    private fun updateLocalApplicationOptionsState(enabled: Boolean) = with(binding) {
        localSignalSwitch.isEnabled = enabled
        localTelegramSwitch.isEnabled = enabled
        localThreemaSwitch.isEnabled = enabled
        localSessionSwitch.isEnabled = enabled
    }

    private fun updateLocalRecastInputsState(enabled: Boolean) = with(binding) {
        localRecastActionEditText.isEnabled = enabled
        localRecastReceiverEditText.isEnabled = enabled
        localRecastExtraKeyEditText.isEnabled = enabled
        localRecastExtraValueEditText.isEnabled = enabled
    }

    private fun validateLocalTimeoutInput(): Boolean {
        val input = binding.localTimeoutEditText.text?.toString().orEmpty()
        val isValid = isValidTimeoutInput(input)
        binding.localTimeoutInputLayout.error = if (isValid || input.isBlank()) null else getString(R.string.trigger_lock_time_error)
        return isValid
    }

    // ─── P2P Setup Card ──────────────────────────────────────────────────────

    /** Show setup card only on Android 14+; on older versions Device Admin is sufficient. */
    fun refreshP2pSetupCard() {
        // Device Owner is only required on Android 14+ for factory reset capability
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            binding.p2pSetupCard.visibility = View.GONE
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val (isDeviceOwner, isOrgOwned) = withContext(Dispatchers.IO) {
                Pair(adminManager.isDeviceOwner(), adminManager.isOrgOwnedProfileOwner())
            }
            if (isDeviceOwner || isOrgOwned) {
                binding.p2pSetupCard.visibility = View.VISIBLE
                binding.p2pSetupTitle.text = "✓ Device Owner Active"
                binding.p2pSetupBody.text = "Wasted is now set up as Device Owner.\nFull factory reset is armed for this phone and all paired peers.\n\nShizuku is no longer needed — Wasted will work reliably with factory reset capability enabled."
                binding.p2pSetupAction.text = "Setup Complete"
                binding.p2pSetupAction.isEnabled = false
            } else {
                binding.p2pSetupCard.visibility = View.VISIBLE
                binding.p2pSetupTitle.text = "⚠️ Setup Required"
                binding.p2pSetupBody.text = "Tap 'Setup Device Owner' to complete setup steps.\n\nThis enables full factory reset capability."
                binding.p2pSetupAction.apply {
                    text = "Setup Device Owner"
                    isEnabled = true
                }
            }
        }
    }

    /** Show Device Owner setup in a bottom sheet dialog. */
    private fun jumpToMainTab() {
        val ctx = requireContext()
        val admin = DeviceAdminManager(ctx)
        val shizuku = try { WastedApp.shizuku } catch (_: Exception) { null }

        // Create bottom sheet
        val bottomSheet = BottomSheetDialog(ctx)
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(24.dp, 24.dp, 24.dp, 12.dp)
        }

        val title = TextView(ctx).apply {
            text = "🔒 Device Owner Setup"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp }
        }
        container.addView(title)

        val body = TextView(ctx).apply {
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16.dp }
        }
        container.addView(body)

        val button = MaterialButton(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(button)

        // State machine
        when {
            admin.isDeviceOwner() || admin.isOrgOwnedProfileOwner() -> {
                title.text = "✓ Device Owner Active"
                body.text = "Wasted is now set up as Device Owner.\nFull factory reset is armed for this phone.\n\nShizuku is no longer needed."
                button.visibility = View.GONE
                bottomSheet.setOnDismissListener { 
                    refreshP2pSetupCard()
                    bottomSheet.dismiss()
                }
            }

            shizuku == null || !shizuku.isInstalled() -> {
                title.text = "Step 1 — Install Shizuku"
                body.text = "Shizuku lets Wasted clear all app data before wiping.\n\nDownload Shizuku v13.6.0 and install it."
                button.apply {
                    text = "Download Shizuku"
                    setOnClickListener { 
                        openShizukuGitHub()
                        bottomSheet.dismiss()
                    }
                }
            }

            !shizuku.isRunning() -> {
                title.text = "Step 2 — Start Shizuku"
                body.text = "Enable Wireless Debugging:\n" +
                    "Settings → Developer Options → Wireless Debugging ON\n\n" +
                    "Then open Shizuku and tap 'Start via Wireless Debugging'."
                button.apply {
                    text = "Open Shizuku"
                    setOnClickListener { 
                        val intent = ctx.packageManager.getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE)
                        if (intent != null) startActivity(intent) else openShizukuGitHub()
                        bottomSheet.dismiss()
                    }
                }
            }

            !shizuku.hasPermission() -> {
                title.text = "Step 3 — Grant Permission"
                body.text = "Wasted needs permission to use Shizuku.\n\nTap 'Grant Permission' — allow it in the Shizuku dialog."
                button.apply {
                    text = "Grant Permission"
                    setOnClickListener { 
                        shizuku.requestPermission()
                        bottomSheet.dismiss()
                    }
                }
            }

            !shizuku.isConnected() -> {
                title.text = "⏳ Connecting Shell…"
                body.text = "Shizuku shell is starting. Please wait…"
                button.visibility = View.GONE
                
                // Live update: poll until shell connects, then auto-refresh the dialog
                Thread {
                    var elapsed = 0
                    while (elapsed < 15000 && !shizuku.isConnected()) { // max 15 sec
                        Thread.sleep(500)
                        elapsed += 500
                        
                        val act = activity ?: return@Thread
                        act.runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            body.text = "Shizuku shell is starting.\n${elapsed / 1000}s elapsed…"
                        }
                    }
                    
                    // If connected, auto-proceed to next step
                    if (shizuku.isConnected()) {
                        val act = activity ?: return@Thread
                        act.runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            bottomSheet.dismiss()
                            jumpToMainTab() // Re-show with next step
                        }
                    } else {
                        // Still not connected, show error
                        val act = activity ?: return@Thread
                        act.runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            title.text = "❌ Connection Timeout"
                            body.text = "Shizuku shell did not connect after 15 seconds.\n\nTry:\n1. Close and reopen Shizuku app\n2. Restart this dialog"
                            button.apply {
                                visibility = View.VISIBLE
                                text = "Retry"
                                setOnClickListener { 
                                    bottomSheet.dismiss()
                                    jumpToMainTab()
                                }
                            }
                        }
                    }
                }.start()
            }

            else -> {
                title.text = "🔒 Set Device Owner"
                body.text = "Remove all linked accounts first from your phone, else we cannot set Wasted as Device Owner:\n" +
                    "Settings → Accounts → remove each one (Gmail etc.)\n" +
                    "Then tap 'Set Device Owner'.\n" +
                    "Reboot phone (only if 'Set Device Owner' option doesn't work)\n\n"
                button.apply {
                    text = "Check Accounts First"
                    setOnClickListener { 
                        showCheckAccountsDialog(shizuku)
                        bottomSheet.dismiss()
                    }
                }

                val setDeviceOwnerBtn = MaterialButton(ctx).apply {
                    text = "Set Device Owner"
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = 8.dp }
                    setOnClickListener { 
                        showDeviceOwnerConfirmDialog(shizuku)
                        bottomSheet.dismiss()
                    }
                }
                container.addView(setDeviceOwnerBtn)
            }
        }

        bottomSheet.setContentView(container)
        bottomSheet.show()
    }

    private fun openShizukuGitHub() {
        val url = "https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun showCheckAccountsDialog(shizuku: ShizukuManager) {
        val checkingDialog = AlertDialog.Builder(requireContext())
            .setTitle("Checking Accounts…")
            .setMessage("Running dumpsys account list via Shizuku…")
            .show()

        Thread {
            val result = shizuku.checkHiddenAccounts()
            val act = activity ?: return@Thread
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                checkingDialog.dismiss()  // Dismiss the "Checking..." dialog
                AlertDialog.Builder(requireContext())
                    .setTitle("Account Check Result")
                    .setMessage(result)
                    .setPositiveButton("OK") { _, _ ->
                        if (result.contains("✓ No accounts")) {
                            jumpToMainTab()
                        }
                    }
                    .show()
            }
        }.start()
    }

    private fun showDeviceOwnerConfirmDialog(shizuku: ShizukuManager) {
        AlertDialog.Builder(requireContext())
            .setTitle("Set Device Owner — Full Factory Reset")
            .setMessage(
                "✓ This enables FULL data destruction capability.\n" +
                "✓ This phone will format on trigger.\n\n" +
                "Wasted will execute:\n" +
                "  dpm set-device-owner me.lucky.wasted/.admin.DeviceAdminReceiver\n\n" +
                "To undo later: disable Device Admin in Wasted settings."
            )
            .setPositiveButton("Set Device Owner") { _, _ -> doBecomeDeviceOwner(shizuku) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doBecomeDeviceOwner(shizuku: ShizukuManager) {
        showMessage("Checking device configuration…")
        Thread {
            var checkError: String? = null
            var managedProfiles: List<Pair<Int, String>>? = null
            
            // First check for managed profiles (Work Profile blocks Device Owner)
            try {
                managedProfiles = shizuku.getManagedProfiles()
                if (managedProfiles.isNotEmpty()) {
                    checkError = "MANAGED_PROFILES"
                }
            } catch (e: Exception) {
                Log.w("P2PNetworkFragment", "Could not check managed profiles: ${e.message}")
            }

            if (checkError == null) {
                // No managed profiles, proceed with Device Owner setup
                val (success, message) = try {
                    val out = shizuku.setDeviceOwner()
                    Pair(true, out.ifBlank { "Wasted is now Device Owner.\nFull factory reset is armed." })
                } catch (e: Exception) {
                    Pair(false, e.message ?: "Unknown error.")
                }

                val act = activity ?: return@Thread
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    
                    val hasAccountError = message?.contains("account", ignoreCase = true) == true
                    val builder = AlertDialog.Builder(requireContext())
                        .setTitle(if (success) "✓ Device Owner Set" else "Failed")
                        .setMessage(message)
                        .setPositiveButton("OK") { _, _ ->
                            if (success) {
                                refreshP2pSetupCard()
                            }
                        }
                    
                    // Show "Fix Accounts" button if account error
                    if (hasAccountError) {
                        builder.setNegativeButton("Fix Accounts") { _, _ ->
                            showAccountFixDialog(shizuku)
                        }
                    }
                    
                    builder.show()
                }
            } else {
                // Managed profile detected
                val act = activity ?: return@Thread
                act.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    
                    val profiles = managedProfiles ?: emptyList()
                    val profileList = profiles.joinToString("\n") { (id, name) -> "• $name (User ID: $id)" }
                    
                    AlertDialog.Builder(requireContext())
                        .setTitle("Work Profile Detected")
                        .setMessage(
                            "Wasted cannot be Device Owner because a Work Profile (managed profile) exists.\n\n" +
                            "Profiles found:\n$profileList\n\n" +
                            "You must remove this profile to proceed.\n\n" +
                            "Tap \"Remove Profile\" to delete it, or manually remove it from Settings → Apps → Special App Access"
                        )
                        .setPositiveButton("Remove Profile") { _, _ ->
                            showRemoveProfileConfirmDialog(shizuku, profiles)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }.start()
    }

    private fun showRemoveProfileConfirmDialog(shizuku: ShizukuManager, profiles: List<Pair<Int, String>>) {
        val profileNames = profiles.map { it.second }.joinToString(", ")
        AlertDialog.Builder(requireContext())
            .setTitle("Remove Profile?")
            .setMessage(
                "This will remove the managed profile:\n$profileNames\n\n" +
                "All apps and data in the profile will be deleted.\n\n" +
                "After removal, you can set Wasted as Device Owner."
            )
            .setPositiveButton("Remove") { _, _ ->
                removeProfileAndRetry(shizuku, profiles)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeProfileAndRetry(shizuku: ShizukuManager, profiles: List<Pair<Int, String>>) {
        showMessage("Removing managed profile…")
        Thread {
            var success = false
            var errorMsg: String? = null

            for ((userId, _) in profiles) {
                try {
                    success = shizuku.removeManagedProfile(userId)
                    if (success) {
                        Log.i("P2PNetworkFragment", "Profile $userId removed successfully")
                        break
                    } else {
                        errorMsg = "Failed to remove profile $userId"
                    }
                } catch (e: Exception) {
                    errorMsg = e.message ?: "Unknown error"
                    Log.e("P2PNetworkFragment", "Remove profile failed: ${e.message}")
                }
            }

            val act = activity ?: return@Thread
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread

                if (success) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("✓ Profile Removed")
                        .setMessage("Managed profile has been removed.\n\nNow you can tap 'Become Device Owner' to proceed.")
                        .setPositiveButton("OK") { _, _ ->
                            // Don't auto-retry; let user do it manually
                        }
                        .show()
                } else {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Removal Failed")
                        .setMessage(errorMsg ?: "Could not remove managed profile.")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }.start()
    }

    private fun showAccountFixDialog(shizuku: ShizukuManager) {
        showMessage("Discovering system accounts…")
        Thread {
            val (packages, error) = try {
                val pkgs = shizuku.getAccountProviderPackages()
                Pair(pkgs, null)
            } catch (e: Exception) {
                Pair(emptyList(), e.message)
            }

            val act = activity ?: return@Thread
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread

                if (error != null) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("Discovery Failed")
                        .setMessage("Could not discover account packages: $error\n\nEnter package name manually below.")
                        .setPositiveButton("OK", null)
                        .show()
                    showManualPackageDisableDialog(shizuku)
                } else if (packages.isEmpty()) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("No Accounts Found")
                        .setMessage("No account provider packages detected.\n\nTry running 'Become Device Owner' again.")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    showPackageSelectionDialog(shizuku, packages)
                }
            }
        }.start()
    }

    private fun showPackageSelectionDialog(shizuku: ShizukuManager, packages: List<String>) {
        val ctx = requireContext()
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Disable Account Packages")
            .setMessage("Select packages to disable, then reboot and retry Device Owner setup.\n\nIf none listed, enter manually:")
            .setItems(packages.toTypedArray()) { _, which ->
                showPackageDisableConfirmDialog(shizuku, packages[which])
            }
            .setNegativeButton("Manual Entry") { _, _ ->
                showManualPackageDisableDialog(shizuku)
            }
            .setPositiveButton("Cancel", null)
            .show()
    }

    private fun showPackageDisableConfirmDialog(shizuku: ShizukuManager, packageName: String) {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle("Disable Package?")
            .setMessage("Package: $packageName\n\nThis will disable the package and remove its accounts.\n\nAfter disabling, REBOOT your device, then tap 'Become Device Owner' again.")
            .setPositiveButton("Disable & Reboot Instructions") { _, _ ->
                disablePackageAndShowInstructions(shizuku, packageName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun disablePackageAndShowInstructions(shizuku: ShizukuManager, packageName: String) {
        showMessage("Disabling $packageName…")
        Thread {
            val success = try {
                shizuku.disablePackage(packageName)
            } catch (e: Exception) {
                Log.e("P2PNetworkFragment", "Disable failed: ${e.message}")
                false
            }

            val act = activity ?: return@Thread
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                AlertDialog.Builder(requireContext())
                    .setTitle(if (success) "✓ Package Disabled" else "Failed")
                    .setMessage(
                        if (success)
                            "Package disabled successfully.\n\n" +
                            "IMPORTANT: You must REBOOT your device now to clear the account from the system.\n\n" +
                            "After rebooting:\n" +
                            "1. Open Wasted\n" +
                            "2. Go to p2p screen's setup\n" +
                            "3. Tap 'Become Device Owner' again"
                        else
                            "Failed to disable package. Try manual entry or check Shizuku."
                    )
                    .setPositiveButton("OK", null)
                    .show()
            }
        }.start()
    }

    private fun showManualPackageDisableDialog(shizuku: ShizukuManager) {
        val ctx = requireContext()
        val input = android.widget.EditText(ctx).apply {
            hint = "com.example.package"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setPadding(20.dp, 12.dp, 20.dp, 8.dp)
        }

        AlertDialog.Builder(ctx)
            .setTitle("Enter Package Name")
            .setMessage("Enter the full package name to disable:")
            .setView(input)
            .setPositiveButton("Disable") { _, _ ->
                val pkg = input.text.toString().trim()
                if (pkg.isNotEmpty() && pkg.contains(".")) {
                    showPackageDisableConfirmDialog(shizuku, pkg)
                } else {
                    AlertDialog.Builder(ctx)
                        .setTitle("Invalid Package")
                        .setMessage("Package name must contain at least one dot (e.g., com.example.package)")
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showScrollableInfoDialog(title: String, body: String) {
        val messageView = TextView(requireContext()).apply {
            text = body
            textSize = 14f
            setPadding(20.dp, 12.dp, 20.dp, 8.dp)
        }

        val scrollView = ScrollView(requireContext()).apply {
            addView(messageView)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun launchQrScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan the other phone's pairing QR")
            .setBeepEnabled(true)
            .setOrientationLocked(true)
            .setCaptureActivity(PortraitCaptureActivity::class.java)
        scanQrLauncher.launch(options)
    }

    private fun showMessage(message: String) {
        val currentView = view ?: return
        Snackbar.make(currentView, message, Snackbar.LENGTH_SHORT).show()
    }

    private fun createPeerActionButton(label: String): MaterialButton {
        return MaterialButton(requireContext()).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            insetTop = 0
            insetBottom = 0
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 8.dp
            }
        }
    }

    private fun createWideActionButton(label: String): MaterialButton {
        return MaterialButton(requireContext()).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            insetTop = 0
            insetBottom = 0
            setPadding(12.dp, 10.dp, 12.dp, 10.dp)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    private fun createSectionLabel(label: String): TextView {
        return TextView(requireContext()).apply {
            text = label
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 16.dp, 0, 6.dp)
        }
    }

    private fun normalizeButtonRow(buttonRow: LinearLayout) {
        val lastIndex = buttonRow.childCount - 1
        for (index in 0..lastIndex) {
            val params = buttonRow.getChildAt(index).layoutParams as? LinearLayout.LayoutParams ?: continue
            params.marginEnd = if (index == lastIndex) 0 else 8.dp
            buttonRow.getChildAt(index).layoutParams = params
        }
    }

    private fun buildSettingsSnapshot(
        baseSnapshot: DeviceSettingsSnapshot,
        appEnabled: Boolean,
        wipeDataEnabled: Boolean,
        wipeEmbeddedSimEnabled: Boolean,
        remoteResetConfirmationEnabled: Boolean,
        timeoutInput: String,
        panicKitEnabled: Boolean,
        tileEnabled: Boolean,
        tileDelayMs: Long,
        shortcutEnabled: Boolean,
        broadcastEnabled: Boolean,
        notificationEnabled: Boolean,
        usbEnabled: Boolean,
        inactivityEnabled: Boolean,
        applicationEnabled: Boolean,
        signalEnabled: Boolean,
        telegramEnabled: Boolean,
        threemaEnabled: Boolean,
        sessionEnabled: Boolean,
        recastEnabled: Boolean,
        recastAction: String,
        recastReceiver: String,
        recastExtraKey: String,
        recastExtraValue: String,
    ): DeviceSettingsSnapshot? {
        val timeoutMinutes = parseTimeoutMinutes(timeoutInput) ?: return null

        var triggerMask = 0
        triggerMask = Utils.setFlag(triggerMask, Trigger.PANIC_KIT.value, panicKitEnabled)
        triggerMask = Utils.setFlag(triggerMask, Trigger.TILE.value, tileEnabled)
        triggerMask = Utils.setFlag(triggerMask, Trigger.SHORTCUT.value, shortcutEnabled)
        triggerMask = Utils.setFlag(triggerMask, Trigger.BROADCAST.value, broadcastEnabled)
        triggerMask = Utils.setFlag(triggerMask, Trigger.NOTIFICATION.value, notificationEnabled)
        triggerMask = Utils.setFlag(triggerMask, Trigger.USB.value, usbEnabled)
        triggerMask = Utils.setFlag(triggerMask, Trigger.LOCK.value, inactivityEnabled)
        triggerMask = Utils.setFlag(triggerMask, Trigger.APPLICATION.value, applicationEnabled)

        var applicationOptions = 0
        applicationOptions = Utils.setFlag(applicationOptions, ApplicationOption.SIGNAL.value, signalEnabled && applicationEnabled)
        applicationOptions = Utils.setFlag(applicationOptions, ApplicationOption.TELEGRAM.value, telegramEnabled && applicationEnabled)
        applicationOptions = Utils.setFlag(applicationOptions, ApplicationOption.THREEMA.value, threemaEnabled && applicationEnabled)
        applicationOptions = Utils.setFlag(applicationOptions, ApplicationOption.SESSION.value, sessionEnabled && applicationEnabled)

        return baseSnapshot.copy(
            appEnabled = appEnabled,
            wipeDataEnabled = wipeDataEnabled,
            wipeEmbeddedSimEnabled = wipeDataEnabled && wipeEmbeddedSimEnabled,
            remoteResetConfirmationEnabled = remoteResetConfirmationEnabled,
            triggerMask = triggerMask,
            inactivityTimeout = timeoutMinutes * 60_000L,
            tileDelayMs = tileDelayMs,
            applicationOptionsMask = applicationOptions,
            recastEnabled = recastEnabled,
            recastAction = recastAction,
            recastReceiver = recastReceiver,
            recastExtraKey = recastExtraKey,
            recastExtraValue = recastExtraValue,
            usbDetectionEnabled = usbEnabled,
            autoLockEnabled = inactivityEnabled,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun buildPeerSettingsText(peer: Peer): String {
        val snapshot = peerSettingsSnapshots[peer.deviceId]
        if (snapshot == null) {
            return if (peer.isConnected) {
                "Current settings: waiting for ${peer.deviceName} to report back"
            } else {
                "Current settings: unavailable while ${peer.deviceName} is offline"
            }
        }

        val updatedAt = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(snapshot.updatedAt))
        val adminState = if (snapshot.deviceAdminActive) "Admin on" else "Admin off"
        val resetState = if (snapshot.resetSupported) "Reset ready" else "Reset unavailable"
        val supportLine = if (snapshot.resetSupported) {
            null
        } else {
            snapshot.resetSupportMessage
        }
        val baseText = "Current settings: ${buildSettingsSummary(snapshot)}\n$adminState • $resetState • Last reported: $updatedAt"
        return if (supportLine.isNullOrBlank()) baseText else "$baseText\n$supportLine"
    }

    private fun buildSettingsSummary(snapshot: DeviceSettingsSnapshot): String {
        val triggerNames = buildList {
            if (hasFlag(snapshot.triggerMask, Trigger.PANIC_KIT.value)) add("PanicKit")
            if (hasFlag(snapshot.triggerMask, Trigger.TILE.value)) add("Tile")
            if (hasFlag(snapshot.triggerMask, Trigger.SHORTCUT.value)) add("Shortcut")
            if (hasFlag(snapshot.triggerMask, Trigger.BROADCAST.value)) add("Broadcast")
            if (hasFlag(snapshot.triggerMask, Trigger.NOTIFICATION.value)) add("Notification")
            if (hasFlag(snapshot.triggerMask, Trigger.LOCK.value)) add("Inactivity")
            if (hasFlag(snapshot.triggerMask, Trigger.USB.value)) add("USB")
            if (hasFlag(snapshot.triggerMask, Trigger.APPLICATION.value)) add("Application")
        }.ifEmpty { listOf("None") }

        val timeoutMinutes = (snapshot.inactivityTimeout / 60_000L).toInt().coerceAtLeast(1)
        val wipeLabel = if (snapshot.wipeDataEnabled) {
            if (snapshot.wipeEmbeddedSimEnabled) "Wipe+eSIM" else "Wipe on"
        } else {
            "Wipe off"
        }
        val tileLabel = formatTileDelaySummary(snapshot.tileDelayMs)
        val recastLabel = if (snapshot.recastEnabled) "Recast on" else "Recast off"
        val remoteResetLabel = if (snapshot.remoteResetConfirmationEnabled) "Remote confirm on" else "Remote confirm off"
        val enabledLabel = if (snapshot.appEnabled) "Enabled" else "Disabled"
        val resetLabel = if (snapshot.resetSupported) "Reset ready" else "Reset blocked"
        return "$enabledLabel • ${formatTimeoutSummary(timeoutMinutes)} • $wipeLabel\nTriggers: ${triggerNames.joinToString(", ")}\nTile $tileLabel • $recastLabel • $remoteResetLabel • $resetLabel"
    }

    private fun formatTileDelayLabel(delayMs: Long): String {
        return "Tile safe delay: ${formatTileDelaySummary(delayMs)}"
    }

    private fun formatTileDelaySummary(delayMs: Long): String {
        return "${String.format("%.1f", delayMs / 1000f)}s"
    }

    private fun hasFlag(mask: Int, flag: Int): Boolean = mask.and(flag) != 0

    private fun parseTimeoutMinutes(input: String): Int? {
        val normalized = input.trim().lowercase()
        if (!isValidTimeoutInput(normalized)) {
            return null
        }
        val modifier = normalized.last()
        val value = normalized.dropLast(1).toIntOrNull() ?: return null
        return when (modifier) {
            MODIFIER_DAYS -> value * 24 * 60
            MODIFIER_HOURS -> value * 60
            MODIFIER_MINUTES -> value
            else -> null
        }
    }

    private fun isValidTimeoutInput(input: String): Boolean {
        return lockCountPattern.matcher(input.trim().lowercase()).matches()
    }

    private fun formatTimeoutInput(minutes: Int): String {
        return when {
            minutes % (24 * 60) == 0 -> "${minutes / 24 / 60}$MODIFIER_DAYS"
            minutes % 60 == 0 -> "${minutes / 60}$MODIFIER_HOURS"
            else -> "${minutes}$MODIFIER_MINUTES"
        }
    }

    private fun formatTimeoutSummary(minutes: Int): String {
        val days = minutes / (24 * 60)
        val hours = (minutes % (24 * 60)) / 60
        val mins = minutes % 60
        return buildList {
            if (days > 0) add("${days}d")
            if (hours > 0) add("${hours}h")
            if (mins > 0 || isEmpty()) add("${mins}m")
        }.joinToString(" ")
    }

    private fun formatTimeoutLabel(minutes: Int): String {
        val days = minutes / (24 * 60)
        val hours = (minutes % (24 * 60)) / 60
        val mins = minutes % 60
        val parts = buildList {
            if (days > 0) add("$days day${if (days == 1) "" else "s"}")
            if (hours > 0) add("$hours hour${if (hours == 1) "" else "s"}")
            if (mins > 0 || isEmpty()) add("$mins minute${if (mins == 1) "" else "s"}")
        }
        return "Inactivity timeout: ${parts.joinToString(" ")}"
    }

    private fun EditText.setTextIfChanged(value: String) {
        if (text?.toString() != value) {
            setText(value)
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
