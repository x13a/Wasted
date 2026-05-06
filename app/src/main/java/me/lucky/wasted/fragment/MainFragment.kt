package me.lucky.wasted.fragment

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import java.util.*

import me.lucky.wasted.Application as WastedApp
import me.lucky.wasted.Preferences
import me.lucky.wasted.R
import me.lucky.wasted.Utils
import me.lucky.wasted.admin.DeviceAdminManager
import me.lucky.wasted.databinding.FragmentMainBinding
import me.lucky.wasted.shizuku.ShizukuManager

class MainFragment : Fragment() {
    private lateinit var binding: FragmentMainBinding
    private lateinit var ctx: Context
    private lateinit var prefs: Preferences
    private lateinit var prefsdb: Preferences
    private val admin by lazy { DeviceAdminManager(ctx) }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        prefs.copyTo(prefsdb, key)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentMainBinding.inflate(inflater, container, false)
        init()
        setup()
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        prefs.registerListener(prefsListener)
    }

    override fun onResume() {
        super.onResume()
        // Refresh whenever we return to screen — covers returning from:
        // Shizuku app (after starting/granting permission), Settings (after removing accounts/granting files access)
        refreshProtectionUI()
    }

    override fun onStop() {
        super.onStop()
        prefs.unregisterListener(prefsListener)
    }

    private fun init() {
        ctx = requireContext()
        prefs = Preferences(ctx)
        prefsdb = Preferences(ctx, encrypted = false)
        if (prefs.secret.isEmpty()) prefs.secret = makeSecret()
        binding.apply {
            secret.text = prefs.secret
            secret.setBackgroundColor(ctx.getColor(
                if (prefs.triggers != 0) R.color.secret_1 else R.color.secret_0
            ))
            wipeData.isChecked = prefs.isWipeData
            wipeEmbeddedSim.isChecked = prefs.isWipeEmbeddedSim
            wipeEmbeddedSim.isEnabled = wipeData.isChecked
            toggle.isChecked = prefs.isEnabled
        }
    }

    private fun setup() = binding.apply {
        wipeData.setOnCheckedChangeListener { _, isChecked ->
            prefs.isWipeData = isChecked
            wipeEmbeddedSim.isEnabled = isChecked
            refreshProtectionUI()
        }
        wipeEmbeddedSim.setOnCheckedChangeListener { _, isChecked ->
            prefs.isWipeEmbeddedSim = isChecked
        }
        toggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) requestAdmin() else setOff()
        }
    }

    private fun setOn() {
        prefs.isEnabled = true
        Utils(ctx).setEnabled(true)
        binding.toggle.isChecked = true
        // Prompt for All Files Access right after enabling — needed for Tier 3 (file deletion)
        if (!admin.hasManageExternalStoragePermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestFilesAccess()
        } else {
            refreshProtectionUI()
        }
    }

    private fun setOff() {
        prefs.isEnabled = false
        Utils(ctx).setEnabled(false)
        try { admin.remove() } catch (exc: SecurityException) {}
        binding.toggle.isChecked = false
        refreshProtectionUI()
    }

    private val registerForDeviceAdmin =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == Activity.RESULT_OK) setOn() else setOff()
        }

    private fun requestAdmin() = registerForDeviceAdmin.launch(admin.makeRequestIntent())

    // ─── All Files Access (MANAGE_EXTERNAL_STORAGE for Tier 3 wipe) ──────────

    private val filesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshProtectionUI() // re-check permission state after user returns from Settings
        }

    private fun requestFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${ctx.packageName}")
            )
            filesAccessLauncher.launch(intent)
        }
        // API < 30: WRITE_EXTERNAL_STORAGE is declared in manifest and granted at install
    }

    // ─── Protection level + Setup cards ──────────────────────────────────────

    /**
     * Refresh both cards. Cards only show when wipe is enabled and Device Admin is active.
     */
    private fun refreshProtectionUI() {
        val showCards = prefs.isWipeData && admin.isActive()
        if (!showCards) {
            binding.protectionCard.visibility = View.GONE
            binding.setupCard.visibility = View.GONE
            return
        }
        binding.protectionCard.visibility = View.VISIBLE
        binding.protectionStatus.text = when (admin.getProtectionTier()) {
            1 -> getString(R.string.protection_tier_1)
            2 -> getString(R.string.protection_tier_2)
            3 -> getString(R.string.protection_tier_3)
            else -> getString(R.string.protection_tier_4)
        }
        updateSetupCard()
    }

    /**
     * Show the correct setup step based on Shizuku state and Device Owner state.
     *
     * States (in order):
     *   DO active                → success banner, nothing more to do
     *   Shizuku not installed    → Step 1: install from Play Store
     *   Shizuku installed, off   → Step 2: start via Wireless Debugging
     *   Running, no permission   → Step 3: grant permission
     *   Running, shell pending   → "connecting…" placeholder
     *   Shell ready              → optional Device Owner button
     */
    private fun updateSetupCard() {
        val shizuku = shizuku() ?: run {
            binding.setupCard.visibility = View.GONE
            return
        }
        binding.setupCard.visibility = View.VISIBLE

        when {
            admin.isDeviceOwner() || admin.isOrgOwnedProfileOwner() -> {
                binding.setupTitle.text = "✓ Device Owner Active"
                binding.setupBody.text =
                    "Wasted is enrolled as Device Owner.\n" +
                    "A full factory reset will fire on trigger."
                binding.setupAction.visibility = View.GONE
                binding.filesAccessDivider.visibility = View.GONE
                binding.filesAccessRow.visibility = View.GONE
            }

            !shizuku.isInstalled() -> {
                binding.setupTitle.text = "Step 1 — Install Shizuku"
                binding.setupBody.text =
                    "Shizuku lets Wasted clear all app data and TRIM flash storage " +
                    "before wiping — making deleted data forensically unrecoverable.\n\n" +
                    "Download Shizuku v13.6.0 from GitHub and install it.\n" +
                    "After installing, open it and follow its setup instructions."
                binding.setupAction.apply {
                    text = "Download Shizuku"
                    visibility = View.VISIBLE
                    setOnClickListener { openShizukuGitHub() }
                }
                showFilesAccessRowIfNeeded()
            }

            !shizuku.isRunning() -> {
                binding.setupTitle.text = "Step 2 — Start Shizuku"
                binding.setupBody.text =
                    "Shizuku is installed but not running.\n\n" +
                    "1. Enable Developer Options (if not already):\n" +
                    "   Settings → About Phone → tap Build Number 7 times\n\n" +
                    "2. Enable Wireless Debugging:\n" +
                    "   Settings → Developer Options → Wireless Debugging → On\n\n" +
                    "3. Open Shizuku → tap \"Start via Wireless Debugging\"\n" +
                    "   → follow the on-screen pairing steps\n\n" +
                    "No PC needed — a second Android phone running Termux works too."
                binding.setupAction.apply {
                    text = "Open Shizuku"
                    visibility = View.VISIBLE
                    setOnClickListener { launchShizuku() }
                }
                showFilesAccessRowIfNeeded()
            }

            !shizuku.hasPermission() -> {
                binding.setupTitle.text = "Step 3 — Grant Shizuku Permission"
                binding.setupBody.text =
                    "Shizuku is running! Wasted needs permission to use it.\n\n" +
                    "Tap Grant Permission — a dialog from Shizuku will appear.\n" +
                    "Tap Allow."
                binding.setupAction.apply {
                    text = "Grant Permission"
                    visibility = View.VISIBLE
                    setOnClickListener { shizuku.requestPermission() }
                }
                showFilesAccessRowIfNeeded()
            }

            !shizuku.isConnected() -> {
                binding.setupTitle.text = "Shizuku — Connecting…"
                binding.setupBody.text =
                    "Permission granted. The shell is connecting — usually takes 1–2 seconds.\n\n" +
                    "If this persists: open Shizuku, force-stop it, and restart it."
                binding.setupAction.visibility = View.GONE
                showFilesAccessRowIfNeeded()
            }

            else -> {
                binding.setupTitle.text = "🔒 Upgrade to Device Owner"
                binding.setupBody.text =
                    "Shizuku is active — TRIM + app data clear is armed.\n\n" +
                    "For FULL factory-reset capability, make Wasted the Device Owner:\n\n" +
                    "✓ Full data destruction guaranteed\n" +
                    "✓ Most reliable wipe on Android 14+\n\n" +
                    "Prerequisites — BEFORE tapping the button:\n" +
                    "1. Settings → Accounts → remove every account\n" +
                    "2. Settings → Apps → open Gmail, Samsung Account, Google → remove accounts\n" +
                    "3. Reboot the phone\n" +
                    "4. Come back and tap Set Device Owner"
                binding.setupAction.apply {
                    text = "Set Device Owner"
                    visibility = View.VISIBLE
                    setOnClickListener { showDeviceOwnerConfirmDialog() }
                }
                showFilesAccessRowIfNeeded()
            }
        }
    }

    private fun showFilesAccessRowIfNeeded() {
        val granted = admin.hasManageExternalStoragePermission()
        binding.filesAccessDivider.visibility = if (!granted) View.VISIBLE else View.GONE
        binding.filesAccessRow.visibility = if (!granted) View.VISIBLE else View.GONE
        if (!granted) {
            binding.filesAccessAction.setOnClickListener { requestFilesAccess() }
        }
    }

    // ─── Device Owner command flow ────────────────────────────────────────────

    private fun showDeviceOwnerConfirmDialog() {
        AlertDialog.Builder(ctx)
            .setTitle("Set Device Owner — Full Factory Reset")
            .setMessage(
                "✓ This enables FULL data destruction capability.\n" +
                "✓ Wasted will format the device on trigger.\n\n" +
                "Prerequisites (MUST be done first):\n\n" +
                "• Settings → Accounts → remove every account\n" +
                "• Settings → Apps → open Gmail, Samsung Account, Google\n" +
                "  → Account & sync → remove all accounts\n" +
                "• Reboot the phone\n\n" +
                "Not sure if accounts are hidden? Use 'Check Accounts' to verify.\n\n" +
                "Then Wasted will execute:\n" +
                "  dpm set-device-owner me.lucky.wasted/.admin.DeviceAdminReceiver\n\n" +
                "To undo later: disable Device Admin in Wasted settings."
            )
            .setNeutralButton("Check Accounts") { _, _ -> showCheckAccountsDialog() }
            .setPositiveButton("Set Device Owner") { _, _ -> doBecomeDeviceOwner() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCheckAccountsDialog() {
        val s = shizuku() ?: run {
            AlertDialog.Builder(ctx)
                .setTitle("Shizuku Not Ready")
                .setMessage("Shizuku shell is not connected yet. Wait a moment and try again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        binding.setupBody.text = "Checking for hidden accounts…"
        Thread {
            val result = s.checkHiddenAccounts()
            val act = activity ?: return@Thread
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                AlertDialog.Builder(ctx)
                    .setTitle("Account Check Result")
                    .setMessage(result)
                    .setPositiveButton("OK") { _, _ ->
                        if (result.contains("✓ No accounts")) {
                            // If no accounts, show Device Owner dialog again
                            showDeviceOwnerConfirmDialog()
                        }
                    }
                    .show()
                refreshProtectionUI()
            }
        }.start()
    }

    private fun doBecomeDeviceOwner() {
        binding.setupAction.isEnabled = false
        binding.setupBody.text = "Running command via Shizuku shell…"

        Thread {
            val (success, message) = try {
                val out = shizuku()!!.setDeviceOwner()
                Pair(true, out.ifBlank { "Wasted is now Device Owner.\nFull factory reset is armed." })
            } catch (e: Exception) {
                Pair(false, e.message ?: "Unknown error.")
            }

            val act = activity ?: return@Thread
            act.runOnUiThread {
                if (!isAdded) return@runOnUiThread
                binding.setupAction.isEnabled = true
                AlertDialog.Builder(ctx)
                    .setTitle(if (success) "✓ Device Owner Set" else "Failed")
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> if (success) refreshProtectionUI() }
                    .show()
                if (!success) updateSetupCard() // restore card text on failure
            }
        }.start()
    }

    // ─── Shizuku launch helpers ───────────────────────────────────────────────

    private fun openShizukuGitHub() {
        val url = "https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0"
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun launchShizuku() {
        val intent = ctx.packageManager.getLaunchIntentForPackage(ShizukuManager.SHIZUKU_PACKAGE)
        if (intent != null) startActivity(intent) else openShizukuGitHub()
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /** Safely retrieve the app-wide ShizukuManager. Null if Application not initialized. */
    private fun shizuku(): ShizukuManager? = try {
        WastedApp.shizuku
    } catch (_: UninitializedPropertyAccessException) { null }

    private fun makeSecret() = UUID.randomUUID().toString()
}