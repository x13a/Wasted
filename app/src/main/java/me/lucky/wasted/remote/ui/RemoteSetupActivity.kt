package me.lucky.wasted.remote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.text.method.LinkMovementMethodCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import me.lucky.wasted.databinding.ActivityRemoteSetupBinding
import me.lucky.wasted.remote.data.RemoteConfigStore
import me.lucky.wasted.remote.data.RemoteRuntime
import me.lucky.wasted.remote.domain.DashboardTemplate
import me.lucky.wasted.remote.domain.RemoteConfig
import me.lucky.wasted.remote.domain.RemoteConfigField
import me.lucky.wasted.remote.domain.RemoteConfigValidator
import java.io.BufferedOutputStream
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Guided, offline setup: choose a path, collect the project URL/key, provide the database SQL
 * and Auth checklist, generate the Netlify-ready dashboard ZIP, and optionally save its hosted URL.
 */
class RemoteSetupActivity : AppCompatActivity() {
    private companion object {
        const val STEP_CHOICE = 0
        const val STEP_PROJECT = 1
        const val STEP_DATABASE = 2
        const val STEP_AUTH = 3
        const val STEP_DASHBOARD = 4
        const val STEP_HOST = 5
        const val STEP_EXISTING = 6
    }

    private lateinit var binding: ActivityRemoteSetupBinding
    private lateinit var configStore: RemoteConfigStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var currentStep = STEP_CHOICE

    private val createPackage =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            if (uri != null) writeDashboardPackage(uri)
        }

    private var pendingSqlAsset: String? = null
    private val saveSqlDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            val asset = pendingSqlAsset
            pendingSqlAsset = null
            if (uri != null && asset != null) writeSqlAsset(asset, uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityRemoteSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configStore = RemoteConfigStore.new(this)

        binding.toolbar.setNavigationContentDescription("Back")
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        onBackPressedDispatcher.addCallback(this) { goBack() }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        ViewCompat.requestApplyInsets(binding.root)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        configStore.loadDraft()?.let { draft ->
            binding.projectUrl.editText?.setText(draft.projectUrl)
            binding.publishableKey.editText?.setText(draft.publishableKey)
        }
        configStore.load()?.let { config ->
            binding.dashboardUrl.editText?.setText(config.websiteUrl)
        }

        binding.newSetupCard.setOnClickListener { showStep(STEP_PROJECT) }
        binding.existingSetupCard.setOnClickListener { showStep(STEP_EXISTING) }
        binding.openProject.setOnClickListener { openProjectPage("") }
        binding.createProject.setOnClickListener { openUrl("https://supabase.com/dashboard/projects") }
        renderLinks(
            binding.createProjectIntro,
            "Sign in at <a href=\"https://supabase.com/dashboard\">supabase.com/dashboard</a> " +
                "and press <b>New project</b>. Fill in:",
        )
        renderLinks(
            binding.copyKeysHint,
            "Wait until the new project finishes provisioning. Then open " +
                "<a href=\"https://supabase.com/dashboard/projects\">your project</a> → " +
                "<b>Connect</b> (or <b>Settings → API Keys</b>) and copy the Project URL and " +
                "publishable key. Never copy the secret/service-role key.",
        )
        binding.copySetupSql.setOnClickListener { copySetupSql() }
        binding.saveSetupSql.setOnClickListener { saveSqlAsset("setup.sql", "wasted-setup.sql") }
        binding.saveUpdateSql.setOnClickListener { saveUpdateSql() }
        binding.openSqlEditor.setOnClickListener { openProjectPage("sql/new") }
        binding.openAuthSettings.setOnClickListener { openProjectPage("auth/providers") }
        binding.openAuthHooks.setOnClickListener { openProjectPage("auth/hooks") }
        binding.generateDashboardPackage.setOnClickListener {
            if (saveDetails()) createPackage.launch("wasted-dashboard.zip")
        }
        binding.openNetlify.setOnClickListener { openUrl("https://app.netlify.com/") }
        binding.skipToDashboard.setOnClickListener {
            if (saveDetails()) showStep(STEP_DASHBOARD)
        }
        binding.backToRemote.setOnClickListener { finish() }
        binding.wizardNext.setOnClickListener { advance() }

        showStep(STEP_CHOICE)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun goBack() {
        when (currentStep) {
            STEP_CHOICE -> finish()
            STEP_PROJECT, STEP_EXISTING -> showStep(STEP_CHOICE)
            else -> showStep(currentStep - 1)
        }
    }

    private fun showStep(step: Int) {
        currentStep = step
        binding.stepChoice.visibility = if (step == STEP_CHOICE) View.VISIBLE else View.GONE
        binding.stepProject.visibility = if (step == STEP_PROJECT) View.VISIBLE else View.GONE
        binding.stepDatabase.visibility = if (step == STEP_DATABASE) View.VISIBLE else View.GONE
        binding.stepAuth.visibility = if (step == STEP_AUTH) View.VISIBLE else View.GONE
        binding.stepDashboard.visibility = if (step == STEP_DASHBOARD) View.VISIBLE else View.GONE
        binding.stepHost.visibility = if (step == STEP_HOST) View.VISIBLE else View.GONE
        binding.stepExisting.visibility = if (step == STEP_EXISTING) View.VISIBLE else View.GONE

        val setupStep = step in STEP_PROJECT..STEP_HOST
        binding.stepChrome.visibility = if (setupStep) View.VISIBLE else View.GONE
        binding.wizardFooter.visibility = if (setupStep) View.VISIBLE else View.GONE
        if (setupStep) {
            binding.stepLabel.text = "Step $step of 5"
            binding.stepProgress.progress = step
            binding.wizardNext.text = when (step) {
                STEP_PROJECT -> "Continue"
                STEP_DATABASE -> "I ran the SQL — Continue"
                STEP_AUTH -> "Auth settings done — Continue"
                STEP_DASHBOARD -> "Continue"
                else -> "Finish setup"
            }
            binding.wizardNext.isEnabled = true
            when (step) {
                STEP_PROJECT -> binding.stepProject.scrollTo(0, 0)
                STEP_DATABASE -> binding.stepDatabase.scrollTo(0, 0)
                STEP_AUTH -> binding.stepAuth.scrollTo(0, 0)
                STEP_DASHBOARD -> binding.stepDashboard.scrollTo(0, 0)
                STEP_HOST -> binding.stepHost.scrollTo(0, 0)
            }
        }
    }

    private fun advance() {
        when (currentStep) {
            STEP_PROJECT -> if (saveDetails()) showStep(STEP_DATABASE)
            STEP_DATABASE -> showStep(STEP_AUTH)
            STEP_AUTH -> showStep(STEP_DASHBOARD)
            STEP_DASHBOARD -> showStep(STEP_HOST)
            STEP_HOST -> finishSetup()
        }
    }

    private fun saveDetails(): Boolean {
        binding.projectUrl.error = null
        binding.publishableKey.error = null
        val url = binding.projectUrl.editText?.text?.toString().orEmpty()
        val key = binding.publishableKey.editText?.text?.toString().orEmpty()

        val errors = RemoteConfigValidator.validateConnection(url, key)
        for (error in errors) {
            when (error.field) {
                RemoteConfigField.PROJECT_URL -> binding.projectUrl.error = error.message
                RemoteConfigField.PUBLISHABLE_KEY -> binding.publishableKey.error = error.message
                else -> Unit
            }
        }
        if (errors.isNotEmpty()) {
            snackbar("Fix the highlighted fields.")
            return false
        }

        if (!configStore.saveDraft(url, key)) {
            snackbar("Could not save the project details.")
            return false
        }
        return true
    }

    private fun finishSetup() {
        val url = binding.dashboardUrl.editText?.text?.toString()?.trim().orEmpty()
        if (url.isNotEmpty()) {
            val draft = configStore.loadDraft() ?: run {
                snackbar("Save the project details first.")
                return
            }
            binding.dashboardUrl.error = null
            val errors = RemoteConfigValidator.validate(
                draft.projectUrl,
                draft.publishableKey,
                url,
            )
            errors.firstOrNull { it.field == RemoteConfigField.WEBSITE_URL }?.let {
                binding.dashboardUrl.error = it.message
            }
            if (errors.isNotEmpty()) {
                snackbar("Fix the highlighted dashboard URL.")
                return
            }
            if (!configStore.save(RemoteConfig(draft.projectUrl, draft.publishableKey, url))) {
                snackbar("Could not save the dashboard URL.")
                return
            }
            RemoteRuntime.reset()
        }
        finish()
    }

    private fun copySetupSql() {
        val sql = runCatching {
            assets.open("setup.sql").bufferedReader().use { it.readText() }
        }.getOrNull()
        if (sql.isNullOrBlank()) {
            snackbar("Setup SQL is unavailable in this build.")
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Wasted setup SQL", sql))
        snackbar("Setup SQL copied. Paste it into the Supabase SQL Editor and press Run.")
    }

    private fun saveSqlAsset(assetPath: String, fileName: String) {
        pendingSqlAsset = assetPath
        saveSqlDocument.launch(fileName)
    }

    private fun saveUpdateSql() {
        val files = assets.list("updates")
            ?.filter { it.endsWith(".sql") }
            .orEmpty()
            .sorted()
        when {
            files.isEmpty() -> snackbar("No database update is needed for this release.")
            files.size == 1 -> saveSqlAsset("updates/${files.first()}", files.first())
            else -> MaterialAlertDialogBuilder(this)
                .setTitle("Choose update file")
                .setItems(files.toTypedArray()) { _, index ->
                    saveSqlAsset("updates/${files[index]}", files[index])
                }
                .show()
        }
    }

    private fun writeSqlAsset(assetPath: String, uri: Uri) {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val sql = assets.open(assetPath).bufferedReader().use { it.readText() }
                    contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(sql.toByteArray(Charsets.UTF_8))
                    } ?: error("no output stream")
                }.isSuccess
            }
            snackbar(
                if (saved) {
                    "SQL saved. Run it once in the Supabase SQL Editor (a computer is easier)."
                } else {
                    "Could not save the SQL file."
                },
            )
        }
    }

    private fun openProjectPage(suffix: String) {
        val ref = projectRef()
        if (ref == null) {
            snackbar("Enter a valid Supabase project URL first.")
            return
        }
        val path = if (suffix.isEmpty()) "" else "/$suffix"
        openUrl("https://supabase.com/dashboard/project/$ref$path")
    }

    private fun projectRef(): String? {
        val raw = binding.projectUrl.editText?.text?.toString()?.trim().orEmpty()
        val host = runCatching { URI(raw).host }.getOrNull() ?: return null
        if (!host.endsWith(".supabase.co")) return null
        val ref = host.removeSuffix(".supabase.co")
        return ref.takeIf { it.matches(Regex("[a-z0-9-]+")) }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { snackbar("No browser available.") }
    }

    /** Renders inline HTML so anchors in the setup copy are tappable links, not plain text. */
    private fun renderLinks(view: android.widget.TextView, markup: String) {
        view.text = HtmlCompat.fromHtml(markup, HtmlCompat.FROM_HTML_MODE_LEGACY)
        view.movementMethod = LinkMovementMethodCompat.getInstance()
    }

    private fun writeDashboardPackage(uri: Uri) {
        val draft = configStore.loadDraft() ?: run {
            snackbar("Save valid project details first.")
            return
        }
        val version = appVersion()

        binding.dashboardGeneratedStatus.text = "Saving dashboard ZIP…"
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val html = renderDashboardHtml(draft.projectUrl, draft.publishableKey, version)
                    val headers = assets.open("dashboard_headers.txt")
                        .bufferedReader()
                        .use { it.readText() }
                    val manifest = assets.open("pwa/manifest.webmanifest")
                        .bufferedReader()
                        .use { it.readText() }
                    val serviceWorker = assets.open("pwa/sw.js")
                        .bufferedReader()
                        .use { it.readText() }
                        .replace(
                            "__WASTED_VERSION__",
                            "$version-${Integer.toUnsignedString(html.hashCode(), 16)}",
                        )
                    contentResolver.openOutputStream(uri)?.use { stream ->
                        ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                            putText(zip, "index.html", html)
                            putText(zip, "_headers", headers)
                            putText(zip, "manifest.webmanifest", manifest)
                            putText(zip, "sw.js", serviceWorker)
                            listOf(
                                "icon-192.png",
                                "icon-512.png",
                                "icon-maskable-512.png",
                                "apple-touch-icon-180.png",
                                "apple-touch-icon-167.png",
                                "apple-touch-icon-152.png",
                                "apple-touch-icon-120.png",
                                "favicon-32.png",
                                "favicon-48.png",
                            ).forEach { name -> putAsset(zip, "pwa/icons/$name", "icons/$name") }
                        }
                    } ?: error("no output stream")
                }.isSuccess
            }
            binding.dashboardGeneratedStatus.text = if (saved) {
                "Dashboard ZIP saved. Upload it to your Netlify project."
            } else {
                "Could not save the dashboard ZIP. Try again."
            }
            snackbar(
                if (saved) {
                    "ZIP saved with the dashboard, manifest, service worker, icons, and headers."
                } else {
                    "Could not save the dashboard ZIP."
                },
            )
        }
    }

    private fun putText(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun putAsset(zip: ZipOutputStream, assetName: String, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        assets.open(assetName).use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun appVersion(): String = runCatching {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName
    }.getOrNull() ?: "3.0.0"

    private fun renderDashboardHtml(projectUrl: String, key: String, version: String): String {
        val template = assets.open("dashboard-template.html")
            .bufferedReader()
            .use { it.readText() }
        return DashboardTemplate.render(
            template,
            projectUrl,
            key,
            version,
            configStore.load()?.websiteUrl.orEmpty(),
        )
    }

    private fun snackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}
