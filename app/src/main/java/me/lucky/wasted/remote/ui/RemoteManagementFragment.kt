package me.lucky.wasted.remote.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import me.lucky.wasted.Preferences
import me.lucky.wasted.R
import me.lucky.wasted.Trigger
import me.lucky.wasted.Utils
import me.lucky.wasted.admin.DeviceAdminManager
import me.lucky.wasted.databinding.DialogRemoteConnectionBinding
import me.lucky.wasted.databinding.DialogRemoteFieldBinding
import me.lucky.wasted.databinding.FragmentRemoteManagementBinding
import me.lucky.wasted.remote.RemoteEngine
import me.lucky.wasted.remote.data.RemoteConfigStore
import me.lucky.wasted.remote.data.RemoteCredentialStore
import me.lucky.wasted.remote.data.RemoteDatabase
import me.lucky.wasted.remote.data.RemoteBootPolicy
import me.lucky.wasted.remote.data.RemoteRuntime
import me.lucky.wasted.remote.domain.ClaimEnrollmentParams
import me.lucky.wasted.remote.domain.RemoteConfig
import me.lucky.wasted.remote.domain.RemoteConfigError
import me.lucky.wasted.remote.domain.RemoteConfigField
import me.lucky.wasted.remote.domain.RemoteConfigValidator
import me.lucky.wasted.remote.domain.RemoteError
import me.lucky.wasted.remote.domain.RemoteEvent
import me.lucky.wasted.remote.domain.RemoteEventBus
import me.lucky.wasted.remote.domain.RemoteException
import me.lucky.wasted.remote.domain.RemoteSyncStatus
import me.lucky.wasted.remote.domain.SyncOutcome
import me.lucky.wasted.trigger.shared.ForegroundService
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class RemoteManagementFragment : Fragment() {

    private lateinit var binding: FragmentRemoteManagementBinding
    private lateinit var prefs: Preferences
    private lateinit var configStore: RemoteConfigStore
    private lateinit var credentials: RemoteCredentialStore
    private lateinit var adminManager: DeviceAdminManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var defaultDashboardStatusColor = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentRemoteManagementBinding.inflate(inflater, container, false)
        defaultDashboardStatusColor = binding.dashboardStatus.currentTextColor
        prefs = Preferences.new(requireContext())
        configStore = RemoteConfigStore.new(requireContext())
        credentials = RemoteRuntime.repository(requireContext()).credentials
        adminManager = DeviceAdminManager(requireContext())
        init()
        setup()
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    RemoteEventBus.events.collect { event ->
                        if (event.kind == RemoteEvent.Kind.DEBUG_MODE_CHANGED ||
                            event.kind == RemoteEvent.Kind.SETTINGS_CHANGED ||
                            event.kind == RemoteEvent.Kind.LOCK_APPLIED
                        ) {
                            refreshState()
                        }
                    }
                }
                launch {
                    RemoteSyncStatus.lastSyncAt.collect { refreshState() }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }

    private fun init() = with(binding) {
        deviceName.editText?.setText(Build.MODEL ?: "")
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    private fun setup() = with(binding) {
        setupWizard.setOnClickListener {
            startActivity(Intent(requireContext(), RemoteSetupActivity::class.java))
        }
        editConnection.setOnClickListener { showEditConnectionDialog() }
        editDashboard.setOnClickListener { showEditDashboardDialog() }
        testConnection.setOnClickListener { testConnection() }
        testDashboard.setOnClickListener { testDashboard() }
        openSupabase.setOnClickListener { openUrl("https://supabase.com/dashboard") }
        openWebsite.setOnClickListener { openWebsite() }
        openDeviceOwnerSetup.setOnClickListener {
            (activity as? me.lucky.wasted.MainActivity)?.openHomeScreen()
        }
        enrollmentHelp.setOnClickListener { showEnrollmentHelp() }
        openDashboardFromEnrollment.setOnClickListener { openWebsite() }
        claimEnrollment.setOnClickListener { claimEnrollment() }
        manualSync.setOnClickListener { manualSync() }
        resetEnrollment.setOnClickListener { confirmResetEnrollment() }
        attachListeners()
    }

    private fun attachListeners() = with(binding) {
        enableRemote.setOnCheckedChangeListener { _, checked -> setRemoteEnabled(checked) }
        debugMode.setOnCheckedChangeListener { _, checked -> setDebugMode(checked) }
        notifications.setOnCheckedChangeListener { _, checked ->
            credentials.notificationsEnabled = checked
        }
    }

    private fun detachListeners() = with(binding) {
        enableRemote.setOnCheckedChangeListener(null)
        debugMode.setOnCheckedChangeListener(null)
        notifications.setOnCheckedChangeListener(null)
    }

    private fun refreshState() = with(binding) {
        detachListeners()
        val config = configStore.load()
        val draft = if (config == null) configStore.loadDraft() else null
        enableRemote.isChecked = configStore.remoteEnabled
        debugMode.isChecked = prefs.isDebugMode
        notifications.isChecked = credentials.notificationsEnabled
        enrollmentSection.visibility = if (configStore.remoteEnabled) View.VISIBLE else View.GONE
        val enrolled = credentials.enrolled
        enrollmentTitle.text = if (enrolled) "Device enrolled" else "4. Enroll this device"
        enrollmentHint.text = if (enrolled) {
            "This phone is linked to your owner account. Use Sync now or Reset enrollment below."
        } else {
            "In the dashboard, open Enroll a device → Create enrollment token, then paste it here. " +
                "Each device needs its own token; it expires after 10 minutes."
        }
        deviceName.visibility = if (enrolled) View.GONE else View.VISIBLE
        enrollmentToken.visibility = if (enrolled) View.GONE else View.VISIBLE
        claimEnrollment.visibility = if (enrolled) View.GONE else View.VISIBLE
        enrollmentProgress.visibility = View.GONE
        openWebsite.isEnabled = !config?.websiteUrl.isNullOrBlank()
        openDashboardFromEnrollment.isEnabled = !config?.websiteUrl.isNullOrBlank()
        testDashboard.isEnabled = !config?.websiteUrl.isNullOrBlank()

        // The screen renders saved values only; editing happens in the dialogs.
        projectUrlValue.text = config?.projectUrl ?: draft?.projectUrl ?: "Not set"
        val key = config?.publishableKey ?: draft?.publishableKey
        publishableKeyValue.text = if (key.isNullOrBlank()) {
            "Publishable key: not set"
        } else {
            "Publishable key: ${maskKey(key)}"
        }
        websiteUrlValue.text = config?.websiteUrl?.takeIf { it.isNotBlank() } ?: "Not set"

        val message = buildString {
            if (config == null) {
                if (draft != null) {
                    append("Supabase connection saved. Next: host the dashboard, paste its URL under 2. Dashboard URL, and tap Save dashboard URL.")
                } else {
                    append("Not set up yet. Start with the setup wizard below.")
                }
            } else {
                append("Connection saved.")
                if (!credentials.enrolled) {
                    append("\nNext: enable Remote Management and claim an enrollment token.")
                } else {
                    append("\nThis device is enrolled.")
                }
            }
            val lastSync = credentials.lastSyncAt
            if (lastSync > 0) {
                append("\nLast successful sync: ")
                append(java.text.DateFormat.getDateTimeInstance()
                    .format(java.util.Date(lastSync)))
            }
            if (configStore.remoteEnabled && credentials.enrolled) {
                append("\nRemote sync is active while this device is unlocked.")
            }
        }
        enrollmentStatus.text = message
        refreshDeviceOwnerHint()
        attachListeners()
    }

    private fun refreshDeviceOwnerHint() {
        lifecycleScope.launch {
            val isOwner = withContext(Dispatchers.IO) {
                adminManager.isDeviceOwner() || adminManager.isOrgOwnedProfileOwner()
            }
            val needsDeviceOwner = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            binding.deviceOwnerHint.visibility =
                if (needsDeviceOwner && !isOwner) View.VISIBLE else View.GONE
        }
    }

    /**
     * Editing happens in a dialog so the saved configuration is the single source of truth. The
     * screen itself only ever renders saved values, which is what keeps a stale URL from being
     * silently reused: there is no unsaved field to drift out of sync.
     */
    private fun showEditConnectionDialog() {
        val dialogBinding = DialogRemoteConnectionBinding.inflate(layoutInflater)
        val saved = configStore.load()
        val draft = if (saved == null) configStore.loadDraft() else null
        dialogBinding.projectUrl.editText?.setText(saved?.projectUrl ?: draft?.projectUrl.orEmpty())
        dialogBinding.publishableKey.editText?.setText(
            saved?.publishableKey ?: draft?.publishableKey.orEmpty(),
        )

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Supabase connection")
            .setView(dialogBinding.root)
            .setPositiveButton("Save", null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val projectUrl = dialogBinding.projectUrl.editText?.text?.toString().orEmpty()
                val publishableKey = dialogBinding.publishableKey.editText?.text?.toString().orEmpty()
                dialogBinding.projectUrl.error = null
                dialogBinding.publishableKey.error = null

                val errors = RemoteConfigValidator.validateConnection(projectUrl, publishableKey)
                for (error in errors) {
                    when (error.field) {
                        RemoteConfigField.PROJECT_URL -> dialogBinding.projectUrl.error = error.message
                        RemoteConfigField.PUBLISHABLE_KEY ->
                            dialogBinding.publishableKey.error = error.message
                        RemoteConfigField.WEBSITE_URL -> Unit
                    }
                }
                if (errors.isNotEmpty()) return@setOnClickListener

                val existing = configStore.load()
                val changed = existing == null || existing.projectUrl.trim() != projectUrl.trim()
                val savedOk = if (existing != null) {
                    configStore.save(
                        RemoteConfig(projectUrl, publishableKey, existing.websiteUrl),
                    )
                } else {
                    configStore.saveDraft(projectUrl, publishableKey)
                }
                if (!savedOk) {
                    snackbar("Supabase connection could not be saved.")
                    return@setOnClickListener
                }
                RemoteRuntime.reset()
                dialog.dismiss()
                snackbar("Supabase connection saved.")
                // A different project means the saved dashboard URL almost certainly belongs to
                // the previous one, so offer to clear it instead of quietly reusing it.
                if (changed && existing?.websiteUrl?.isNotBlank() == true) {
                    promptClearDashboardUrl()
                }
                // The stored anonymous session belongs to the previous project. Its user id does
                // not exist in the new database, so any claim would fail on the auth.users foreign
                // key. Drop the identity now and let the next sync create a fresh one.
                if (changed) {
                    credentials.clearSession()
                }
                refreshState()
            }
        }
        dialog.show()
    }

    private fun promptClearDashboardUrl() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Clear the saved dashboard URL?")
            .setMessage(
                "The saved dashboard was built for a different Supabase project. Host the new " +
                    "dashboard, then set its URL. Keeping the old one would open the wrong site.",
            )
            .setPositiveButton("Clear it") { _, _ ->
                configStore.clearWebsiteUrl()
                refreshState()
                snackbar("Dashboard URL cleared.")
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun showEditDashboardDialog() {
        val dialogBinding = DialogRemoteFieldBinding.inflate(layoutInflater)
        val savedUrl = configStore.load()?.websiteUrl.orEmpty()
        dialogBinding.hint.text =
            "Paste the HTTPS URL of the hosted dashboard. If it is not hosted yet, use the setup " +
                "wizard to generate the ZIP, then upload it to your Netlify project."
        dialogBinding.field.hint = "Dashboard URL"
        dialogBinding.field.editText?.setText(savedUrl)
        dialogBinding.clear.setOnClickListener {
            dialogBinding.field.editText?.setText("")
        }
        dialogBinding.clear.visibility = if (savedUrl.isBlank()) View.GONE else View.VISIBLE

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Dashboard URL")
            .setView(dialogBinding.root)
            .setPositiveButton("Save", null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = dialogBinding.field.editText?.text?.toString().orEmpty().trim()
                dialogBinding.field.error = null
                // An empty field is a deliberate clear, not an invalid value.
                if (raw.isEmpty()) {
                    configStore.clearWebsiteUrl()
                    RemoteRuntime.reset()
                    dialog.dismiss()
                    snackbar("Dashboard URL cleared.")
                    refreshState()
                    return@setOnClickListener
                }
                val uri = runCatching { java.net.URI(raw) }.getOrNull()
                if (uri?.scheme != "https" || uri.host.isNullOrBlank()) {
                    dialogBinding.field.error = "Enter a valid HTTPS URL."
                    return@setOnClickListener
                }
                val existing = configStore.load()
                val draft = if (existing == null) configStore.loadDraft() else null
                if (existing == null && draft == null) {
                    dialogBinding.field.error = "Save the Supabase connection first."
                    return@setOnClickListener
                }
                if (!configStore.saveWebsiteUrl(raw)) {
                    dialogBinding.field.error = "Could not save the dashboard URL."
                    return@setOnClickListener
                }
                RemoteRuntime.reset()
                dialog.dismiss()
                snackbar("Dashboard URL saved.")
                refreshState()
            }
        }
        dialog.show()
    }

    private fun testConnection() {
        val saved = configStore.load()
        val draft = if (saved == null) configStore.loadDraft() else null
        val projectUrl = saved?.projectUrl ?: draft?.projectUrl
        val publishableKey = saved?.publishableKey ?: draft?.publishableKey
        if (projectUrl == null || publishableKey == null) {
            snackbar("Save the Supabase connection first.")
            return
        }
        snackbar("Testing Supabase connection…")
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val repository = RemoteRuntime.repository(requireContext())
                try {
                    Result.success(repository.testConnection(projectUrl, publishableKey))
                } catch (e: RemoteException) {
                    Result.failure(e)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            result.fold(
                onSuccess = { anonymousEnabled ->
                    if (anonymousEnabled) {
                        snackbar("Supabase connection OK. Anonymous sign-ins are enabled.")
                    } else {
                        showAnonymousSignInsDialog()
                    }
                },
                onFailure = { error ->
                    val message = when (error) {
                        is RemoteException ->
                            "Supabase connection failed: ${connectionErrorMessage(error.error)}"
                        else ->
                            "Supabase connection failed: ${error.message ?: "unknown error"}"
                    }
                    snackbar(message)
                },
            )
        }
    }

    private fun showAnonymousSignInsDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Anonymous sign-ins are off")
            .setMessage(
                "Remote Management uses anonymous sign-ins to give each device its own private " +
                    "identity. That links a device to your account without storing your email or " +
                    "password on it.\n\nIf you leave this off, your owner account and dashboard " +
                    "still work, but this device cannot enroll or sync remote commands.\n\nEnable “Allow " +
                    "anonymous sign-ins” in Supabase → Authentication → Providers if you want " +
                    "Remote Management.",
            )
            .setPositiveButton("Open Auth settings") { _, _ ->
                projectAuthUrl()?.let(::openUrl)
                    ?: snackbar("Save a valid project URL first.")
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun projectAuthUrl(): String? {
        val config = configStore.load() ?: return null
        val host = Uri.parse(config.projectUrl).host ?: return null
        if (!host.endsWith(".supabase.co")) return null
        val ref = host.removeSuffix(".supabase.co")
        return "https://supabase.com/dashboard/project/$ref/auth/providers"
    }

    private fun testDashboard() {
        val raw = configStore.load()?.websiteUrl?.trim().orEmpty()
        if (raw.isEmpty()) {
            setDashboardTestVisual(false)
            binding.dashboardStatus.text = "Set the dashboard URL first."
            return
        }
        val uri = runCatching { java.net.URI(raw) }.getOrNull()
        if (uri?.scheme != "https" || uri.host.isNullOrBlank()) {
            setDashboardTestVisual(false)
            binding.dashboardStatus.text = "Enter a valid HTTPS URL."
            return
        }
        setDashboardTestVisual(false)
        binding.dashboardStatus.text = "Checking…"
        scope.launch {
            val code = withContext(Dispatchers.IO) {
                runCatching {
                    val client = OkHttpClient.Builder()
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build()
                    client.newCall(Request.Builder().url(raw).get().build())
                        .execute()
                        .use { it.code }
                }.getOrNull()
            }
            when {
                code == null -> {
                    setDashboardTestVisual(false)
                    binding.dashboardStatus.text = "Could not reach the dashboard. Check the URL and your connection."
                }
                code in 200..299 -> {
                    setDashboardTestVisual(true)
                    binding.dashboardStatus.text = "Dashboard is reachable (HTTP $code)."
                }
                code == 401 || code == 403 -> {
                    setDashboardTestVisual(false)
                    binding.dashboardStatus.text =
                        "Dashboard responded with HTTP $code. Make the hosted site public or disable password protection, then test again."
                }
                code == 404 -> {
                    setDashboardTestVisual(false)
                    binding.dashboardStatus.text =
                        "Dashboard responded with HTTP 404. Make sure the uploaded file is named index.html at the site root."
                }
                else -> {
                    setDashboardTestVisual(false)
                    binding.dashboardStatus.text = "Dashboard responded with HTTP $code."
                }
            }
        }
    }

    private fun setDashboardTestVisual(success: Boolean) {
        val green = ContextCompat.getColor(requireContext(), R.color.green_500)
        binding.dashboardStatus.setTextColor(
            if (success) green else defaultDashboardStatusColor,
        )
    }

    /** Keys identify a project rather than acting as a secret, but the full value is just noise. */
    private fun maskKey(key: String): String =
        if (key.length <= 16) key else "${key.take(12)}…${key.takeLast(4)}"

    private fun connectionErrorMessage(error: RemoteError): String = when (error) {
        RemoteError.Network -> "network unavailable. Check your internet connection."
        is RemoteError.RateLimited ->
            "Supabase rate-limited the request. Wait a minute and try again."
        is RemoteError.Server ->
            "Supabase server error (${error.statusCode}). Try again shortly."
        RemoteError.Authentication ->
            "Supabase rejected the key. Check the Project URL and publishable key."
        RemoteError.RlsDenied ->
            "Supabase denied access. Check that setup.sql ran successfully."
        RemoteError.Conflict ->
            "Supabase reported a configuration conflict."
        RemoteError.Validation ->
            "Supabase rejected the request. Check the Project URL and publishable key, and make sure the backend setup is complete."
        RemoteError.BackendUnavailable ->
            "Supabase cannot find the backend function. Reload the schema cache or re-run the backend setup."
        is RemoteError.Unknown ->
            "unexpected Supabase error. Check the Project URL/key and Auth settings."
    }

    private fun showEnrollmentHelp() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Enrollment buttons")
            .setMessage(
                "Claim enrollment — paste the one-time token from the dashboard. It links this phone to your owner account. Each token works once and expires after 10 minutes.\n\n" +
                    "Sync now — asks the app to reconcile with Supabase immediately instead of waiting for the next automatic sync.\n\n" +
                    "Reset enrollment — removes this phone's local device identity and saved remote session. It does not remove the device record from your dashboard; use Revoke there to stop its remote access. The record is kept for audit. You need a new token to enroll this phone again.\n\n" +
                    "Turning Remote Management off stops sync but keeps this device enrolled. Use Reset enrollment when you want to remove the local identity.\n\n" +
                    "Open dashboard — opens your hosted dashboard to create a token or manage this device.",
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun openWebsite() {
        val url = configStore.load()?.websiteUrl
        if (url.isNullOrBlank()) {
            snackbar("Host the generated dashboard first, then save its HTTPS URL here.")
            return
        }
        openUrl(url)
    }

    private fun openUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            snackbar("No browser available.")
        }
    }

    private fun setRemoteEnabled(enabled: Boolean) {
        if (enabled) {
            if (!configStore.isConfigured) {
                detachListeners()
                binding.enableRemote.isChecked = false
                attachListeners()
                snackbar("Save the project URL, publishable key, and dashboard URL first (or use the setup wizard).")
                return
            }
            // First enablement locally forces Debug Mode ON before enrollment.
            prefs.isDebugMode = true
                RemoteBootPolicy.new(requireContext()).debugMode = true
        }
        // Keep the device's encrypted auth session while sync is disabled. Reset enrollment is
        // the explicit action that removes the device identity.
        configStore.remoteEnabled = enabled
        Utils(requireContext()).updateForegroundRequiredEnabled()
        refreshState()
    }

    private fun setDebugMode(enabled: Boolean) {
        if (enabled) {
            prefs.isDebugMode = true
                RemoteBootPolicy.new(requireContext()).debugMode = true
            refreshState()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Turn Debug Mode OFF?")
            .setMessage(
                "While Debug Mode is OFF, dangerous commands (including a remote " +
                    "Force Reset) can wipe this device. Only turn it off when you are " +
                    "ready for real destructive actions.",
            )
            .setPositiveButton("Turn OFF") { _, _ ->
                prefs.isDebugMode = false
                RemoteBootPolicy.new(requireContext()).debugMode = false
                refreshState()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                refreshState()
            }
            .setCancelable(false)
            .show()
    }

    private fun claimEnrollment() {
        if (!configStore.remoteEnabled) {
            snackbar("Enable Remote Management first.")
            return
        }
        if (credentials.enrolled) {
            snackbar("This device is already enrolled.")
            return
        }
        val token = binding.enrollmentToken.editText?.text?.toString()?.trim().orEmpty()
        if (token.length != 64 || token.any { it !in "0123456789abcdef" }) {
            binding.enrollmentToken.error = "Enter the 64-character lowercase hex token"
            return
        }
        binding.enrollmentToken.error = null
        val deviceName = binding.deviceName.editText?.text?.toString()?.trim()
            .orEmpty()
            .ifBlank { Build.MODEL ?: "Android device" }
            .take(80)
        binding.enrollmentProgress.visibility = View.VISIBLE
        binding.claimEnrollment.isEnabled = false
        snackbar("Claiming enrollment…")
        scope.launch {
            val wasEnrolled = credentials.enrolled
            val message = withContext(Dispatchers.IO) {
                val repository = RemoteRuntime.repository(requireContext())
                try {
                    repository.ensureAnonymousSession()
                    val device = repository.claimEnrollment(
                        ClaimEnrollmentParams(
                            token = token,
                            installationId = credentials.installationId,
                            deviceName = deviceName,
                            lockTimerMinutes = prefs.triggerLockCount,
                            usbTriggerEnabled = prefs.triggers.and(Trigger.USB.value) != 0,
                            lockTriggerEnabled = prefs.triggers.and(Trigger.LOCK.value) != 0,
                        ),
                    )
                    credentials.deviceId = device.id
                    credentials.accountId = device.accountId
                    credentials.enrolled = true
                    credentials.appliedGlobalSettingsRevision = 0
                    credentials.appliedDeviceSettingsRevision = 0
                    "Enrolled as ${device.name}."
                } catch (e: Exception) {
                    "Enrollment failed: ${enrollmentFailureReason(e)}"
                }
            }
            binding.enrollmentProgress.visibility = View.GONE
            binding.claimEnrollment.isEnabled = true
            if (!wasEnrolled && credentials.enrolled) {
                binding.enrollmentToken.editText?.setText("")
                ForegroundService.restartRemote(requireContext())
            }
            snackbar(message)
            refreshState()
        }
    }

    /**
     * Turns an enrollment failure into the next action. The sanitized reason alone ("request
     * rejected") does not tell the owner whether to make a new token, reset the device, or retry.
     */
    private fun enrollmentFailureReason(e: Exception): String {
        val error = (e as? RemoteException)?.error
        return when (error) {
            is RemoteError.Conflict ->
                "this token has expired or was already used. Create a new enrollment token in the " +
                    "dashboard and try again."
            RemoteError.Authentication ->
                "this device's session is no longer valid. Tap Reset enrollment, then claim a new " +
                    "token."
            RemoteError.RlsDenied ->
                "the backend rejected the request. Check that setup.sql ran and that you are " +
                    "claiming a token from your own dashboard."
            RemoteError.Validation ->
                "the backend rejected this enrollment. Check the token was copied in full from " +
                    "your dashboard, then try again."
            RemoteError.BackendUnavailable ->
                "the backend function is still loading. Wait a few seconds and try again."
            is RemoteError.RateLimited ->
                "too many attempts. Wait a minute and try again."
            RemoteError.Network ->
                "no network connection. Check your internet and try again."
            is RemoteError.Server ->
                "Supabase returned an error. Wait a moment and try again."
            else -> e.message ?: "unexpected error."
        }
    }

    private fun manualSync() {
        if (!configStore.canStartRemoteWork() || !credentials.enrolled) {
            snackbar("Enable Remote Management and enroll first.")
            return
        }
        snackbar("Syncing…")
        val ctx = requireContext()
        scope.launch {
            val message = withContext(Dispatchers.IO) {
                val engine = RemoteEngine.create(ctx)
                if (engine == null) {
                    "Remote is not available."
                } else {
                    try {
                        when (val outcome = engine.runSync()) {
                            is SyncOutcome.Success -> "Sync complete."
                            is SyncOutcome.Failed -> if (
                                outcome.reason == "access denied" ||
                                outcome.reason == "authentication failed"
                            ) {
                                "Sync failed: this device could not restore its session. Check that it is still active in the dashboard."
                            } else {
                                "Sync failed: ${outcome.reason}"
                            }
                            SyncOutcome.NotConfigured -> "Remote is not configured."
                            SyncOutcome.NotEnrolled -> "Device is not enrolled."
                            SyncOutcome.Coalesced -> "A sync is already running."
                        }
                    } catch (e: Exception) {
                        "Sync failed: ${e.message}"
                    } finally {
                        engine.close()
                    }
                }
            }
            snackbar(message)
            refreshState()
        }
    }

    private fun confirmResetEnrollment() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Reset Remote Enrollment?")
            .setMessage(
                "This stops remote sync, clears the device session and the encrypted " +
                    "remote journal/outbox, and rotates this device's installation ID. " +
                    "The device record remains in your dashboard; revoke it there to stop remote access. " +
                    "A fresh enrollment token from the dashboard is required. Local " +
                    "settings, timers, Device Admin, and P2P are not affected.",
            )
            .setPositiveButton("Reset") { _, _ ->
                credentials.clearSession()
                credentials.rotateInstallationId()
                configStore.remoteEnabled = false
                RemoteRuntime.reset()
                RemoteDatabase.clear(requireContext())
                Utils(requireContext()).updateForegroundRequiredEnabled()
                snackbar("Remote enrollment reset.")
                refreshState()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun snackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}
