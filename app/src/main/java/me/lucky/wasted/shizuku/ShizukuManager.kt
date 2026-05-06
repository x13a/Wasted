package me.lucky.wasted.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import me.lucky.wasted.IRemoteShell
import rikka.shizuku.Shizuku

/**
 * Manages the lifecycle of the Shizuku connection and exposes wipe/setup commands.
 *
 * Architecture:
 * - Shizuku runs with ADB-level privileges (no root required).
 * - Activated via Wireless Debugging on Android 11+.
 * - This manager binds once at startup (if already running) and keeps the shell alive.
 * - At wipe time, commands are dispatched synchronously via the pre-bound IRemoteShell.
 *
 * Setup flow for the user:
 * 1. Install Shizuku from Play Store.
 * 2. Enable Wireless Debugging in Developer Options.
 * 3. Open Shizuku → "Start via Wireless Debugging" → pair.
 * 4. Grant Wasted permission inside Shizuku.
 * 5. Optionally: use setDeviceOwner() to get full factory-reset capability.
 */
class ShizukuManager(private val ctx: Context) {

    @Volatile private var shell: IRemoteShell? = null
    private var boundArgs: Shizuku.UserServiceArgs? = null
    private var boundConn: ServiceConnection? = null

    // ─── Shizuku lifecycle listeners ─────────────────────────────────────────

    private val onBinderReceived = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        if (hasPermission()) bindShell()
    }

    private val onBinderDead = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        shell = null
    }

    private val onPermissionResult = Shizuku.OnRequestPermissionResultListener { _, result ->
        if (result == PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Shizuku permission granted — binding shell")
            bindShell()
        } else {
            Log.w(TAG, "Shizuku permission denied")
        }
    }

    /**
     * Register Shizuku listeners. Call from Application.onCreate().
     * No-op on API < 24 (Shizuku library requires API 24 at minimum even though Shizuku app
     * itself supports API 23 — safe to skip on ancient devices that can't run Wireless Debugging).
     */
    fun init() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
        Shizuku.addBinderDeadListener(onBinderDead)
        Shizuku.addRequestPermissionResultListener(onPermissionResult)
        Log.d(TAG, "ShizukuManager initialized")
    }

    fun destroy() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        Shizuku.removeBinderReceivedListener(onBinderReceived)
        Shizuku.removeBinderDeadListener(onBinderDead)
        Shizuku.removeRequestPermissionResultListener(onPermissionResult)
        unbindShell()
        Log.d(TAG, "ShizukuManager destroyed")
    }

    // ─── Status checks ───────────────────────────────────────────────────────

    /** True if the Shizuku app is installed on the device. */
    fun isInstalled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) { false }
    }

    /** True if the Shizuku service is actively running. */
    fun isRunning(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try { Shizuku.pingBinder() } catch (_: Exception) { false }
    }

    /** True if Wasted has been granted Shizuku permission. */
    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        return try {
            isRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }
    }

    /** True if the IRemoteShell binder is live and ready to accept commands. */
    fun isConnected(): Boolean = shell?.let {
        try { it.asBinder().isBinderAlive } catch (_: Exception) { false }
    } ?: false

    // ─── Permission ──────────────────────────────────────────────────────────

    /** Show the Shizuku permission request dialog to the user. */
    fun requestPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try { Shizuku.requestPermission(RC_PERMISSION) } catch (_: Exception) {}
    }

    // ─── Shell binding ───────────────────────────────────────────────────────

    /**
     * Bind ShizukuShell as a UserService. Shizuku will start it in the ADB shell process.
     * The shell reference becomes available asynchronously via onServiceConnected.
     */
    fun bindShell() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (isConnected()) return
        Log.d(TAG, "Binding Shizuku shell")

        val args = Shizuku.UserServiceArgs(ComponentName(ctx, ShizukuShell::class.java))
            .processNameSuffix("wasted_shell")
            .daemon(false)
            .version(SHELL_VERSION)
            .also { boundArgs = it }

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                shell = IRemoteShell.Stub.asInterface(binder)
                Log.i(TAG, "Shell connected")
            }
            override fun onServiceDisconnected(name: ComponentName) {
                shell = null
                Log.w(TAG, "Shell disconnected")
            }
        }.also { boundConn = it }

        try {
            Shizuku.bindUserService(args, conn)
        } catch (e: Exception) {
            Log.e(TAG, "bindUserService failed: ${e.message}")
        }
    }

    private fun unbindShell() {
        val args = boundArgs ?: return
        val conn = boundConn ?: return
        try { Shizuku.unbindUserService(args, conn, true) } catch (_: Exception) {}
        shell = null
        boundArgs = null
        boundConn = null
    }

    // ─── Wipe commands ───────────────────────────────────────────────────────

    /**
     * Run TRIM + pm clear on all user-installed apps.
     * Call this before deepManualWipe() on Android 14+ when not Device Owner.
     * The shell must already be connected (isConnected() == true).
     * This call is synchronous — run from a background thread or wipe context.
     */
    fun runWipeCommands() {
        val s = shell ?: run {
            Log.w(TAG, "runWipeCommands: shell not connected")
            return
        }
        Log.i(TAG, "Running wipe commands via Shizuku")

        // TRIM: tells NAND flash to zero free blocks → deleted data unrecoverable
        try {
            Log.d(TAG, "Running sm fstrim")
            s.executeNow("sm fstrim &")
        } catch (e: Exception) {
            Log.e(TAG, "TRIM failed: ${e.message}")
        }

        // Clear data of every user-installed app (excluding Wasted itself)
        try {
            val packages = ctx.packageManager.getInstalledPackages(0)
                .filter { pkg ->
                    val flags = pkg.applicationInfo?.flags ?: 0
                    (flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        pkg.packageName != ctx.packageName
                }
            Log.d(TAG, "Clearing ${packages.size} user app(s)")
            packages.forEach { pkg ->
                try {
                    s.executeNow("pm clear ${pkg.packageName}")
                    Log.d(TAG, "Cleared: ${pkg.packageName}")
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "clearAllAppsData failed: ${e.message}")
        }
    }

    // ─── Device Owner setup ──────────────────────────────────────────────────

    /**
     * Run `dpm set-device-owner` via Shizuku shell.
     * Returns a success message, or throws Exception with a user-readable explanation.
     *
     * Prerequisites (enforced by Android OS, not us):
     * - ALL accounts must be removed from the device first (Settings → Accounts).
     * - Apps holding background account tokens (Gmail, Samsung, etc.) must also be cleared.
     * - Reboot after removing accounts before calling this.
     */
    @Throws(Exception::class)
    fun setDeviceOwner(): String {
        if (!isRunning()) throw Exception(
            "Shizuku is not running.\n\n" +
            "Open Shizuku and tap \"Start via Wireless Debugging\"."
        )
        if (!hasPermission()) throw Exception(
            "Shizuku permission not granted.\n\n" +
            "Tap \"Grant Permission\" in the Wasted setup card and allow it in the dialog."
        )
        val s = shell ?: throw Exception(
            "Shell not connected yet.\n\n" +
            "Wait a moment for Shizuku to finish connecting, then try again."
        )

        Log.i(TAG, "Running: dpm set-device-owner $DEVICE_ADMIN_COMPONENT")
        val output = s.executeNow("dpm set-device-owner $DEVICE_ADMIN_COMPONENT")
        Log.d(TAG, "dpm output: $output")

        return when {
            output.contains("Success", ignoreCase = true) -> {
                Log.i(TAG, "Device Owner set successfully")
                output
            }
            output.contains("account", ignoreCase = true) -> throw Exception(
                "The device still has accounts registered.\n\n" +
                "Steps to fix:\n" +
                "1. Settings → Accounts → remove every account\n" +
                "2. Settings → Apps → open Gmail, Samsung Account, Google, etc. → " +
                "Account & sync → remove their accounts\n" +
                "3. Check hidden accounts: on a second phone use Termux → pkg install android-tools → " +
                "adb connect <this phone IP> → adb shell dumpsys account list\n" +
                "4. Reboot this phone\n" +
                "5. Come back and tap Become Device Owner again"
            )
            output.contains("already", ignoreCase = true) && output.contains("owner", ignoreCase = true) -> throw Exception(
                "A device or profile owner is already set on this phone.\n\n" +
                "To clear it: factory reset the phone, then immediately set Wasted as " +
                "Device Owner before re-adding any accounts."
            )
            output.isBlank() -> throw Exception(
                "No output from dpm command. Shizuku may have lost its connection.\n\n" +
                "Try restarting Shizuku and retrying."
            )
            else -> throw Exception(output)
        }
    }

    // ─── Account checking ────────────────────────────────────────────────────

    /**
     * Parse dumpsys account output to extract packages providing accounts.
     * Returns list of unique package names found in account providers.
     * Format: Extracts from lines like "Authenticator{account-type}:" and related package info.
     */
    fun getAccountProviderPackages(): List<String> {
        if (!isRunning()) throw Exception("Shizuku is not running.")
        if (!hasPermission()) throw Exception("Shizuku permission not granted.")
        val s = shell ?: throw Exception("Shell not connected yet.")

        Log.i(TAG, "Running: dumpsys account list")
        val output = s.executeNow("dumpsys account list")
        Log.d(TAG, "dumpsys output length: ${output.length}")

        val packages = mutableSetOf<String>()
        
        // Parse dumpsys account output to extract package names
        // Lines typically contain package names after authenticator types
        val lines = output.split("\n")
        for (line in lines) {
            val trimmed = line.trim()
            // Look for lines with package names (contain dots and are lowercase)
            when {
                trimmed.contains("Account {") && trimmed.contains("}") -> {
                    // Extract package from lines like: Account {name=example@gmail.com type=com.google accounts=...}
                    val regex = """type=([a-zA-Z0-9._]+)""".toRegex()
                    regex.find(trimmed)?.groupValues?.get(1)?.let { packages.add(it) }
                }
                trimmed.startsWith("Authenticator") && trimmed.contains("{") -> {
                    // Extract from lines like: Authenticator{com.motorola.contacts...}
                    val start = trimmed.indexOf("{") + 1
                    val end = trimmed.indexOf("}")
                    if (start > 0 && end > start) {
                        val pkg = trimmed.substring(start, end)
                        if (pkg.contains(".") && !pkg.contains(" ")) {
                            packages.add(pkg)
                        }
                    }
                }
                trimmed.contains("@") && trimmed.contains("type=") -> {
                    // Extract package from account entries
                    val regex = """type=([a-zA-Z0-9._]+)""".toRegex()
                    regex.find(trimmed)?.groupValues?.get(1)?.let { packages.add(it) }
                }
            }
        }

        return packages.filter { it.isNotEmpty() }.sorted()
    }

    /**
     * Disable a specific package via `pm disable-user`.
     * Returns true if successful.
     */
    fun disablePackage(packageName: String): Boolean {
        if (!isRunning()) throw Exception("Shizuku is not running.")
        if (!hasPermission()) throw Exception("Shizuku permission not granted.")
        val s = shell ?: throw Exception("Shell not connected yet.")

        Log.i(TAG, "Running: pm disable-user --user 0 $packageName")
        val output = s.executeNow("pm disable-user --user 0 $packageName")
        Log.d(TAG, "pm disable output: $output")

        return output.isBlank() || output.contains("Success", ignoreCase = true)
    }

    /**
     * Enable a specific package via `pm enable-user`.
     * Returns true if successful.
     */
    fun enablePackage(packageName: String): Boolean {
        if (!isRunning()) throw Exception("Shizuku is not running.")
        if (!hasPermission()) throw Exception("Shizuku permission not granted.")
        val s = shell ?: throw Exception("Shell not connected yet.")

        Log.i(TAG, "Running: pm enable --user 0 $packageName")
        val output = s.executeNow("pm enable --user 0 $packageName")
        Log.d(TAG, "pm enable output: $output")

        return output.isBlank() || output.contains("Success", ignoreCase = true)
    }

    /**
     * Check for managed profiles (Work Profile, etc.) that would block Device Owner enrollment.
     * Returns a list of managed profile user IDs and names, or empty list if none exist.
     * Wasted cannot be Device Owner if ANY managed profile exists.
     */
    fun getManagedProfiles(): List<Pair<Int, String>> {
        if (!isRunning()) throw Exception("Shizuku is not running.")
        if (!hasPermission()) throw Exception("Shizuku permission not granted.")
        val s = shell ?: throw Exception("Shell not connected yet.")

        Log.i(TAG, "Running: dumpsys user")
        val output = s.executeNow("dumpsys user")
        Log.d(TAG, "dumpsys user output length: ${output.length}")

        val profiles = mutableListOf<Pair<Int, String>>()
        
        // Parse output looking for managed profiles
        // Format: UserInfo{id:name:flags}
        val lines = output.split("\n")
        for (line in lines) {
            val trimmed = line.trim()
            // Look for UserInfo lines with MANAGED flag (0x20)
            if (trimmed.contains("UserInfo{") && trimmed.contains("}")) {
                // Example: UserInfo{10:Work Profile:48}
                // Flag 48 = 0x30 = TYPE_PROFILE (0x10) | FLAG_MANAGED (0x20)
                try {
                    val start = trimmed.indexOf("{") + 1
                    val end = trimmed.indexOf("}")
                    if (start > 0 && end > start) {
                        val content = trimmed.substring(start, end)
                        val parts = content.split(":")
                        if (parts.size >= 3) {
                            val userId = parts[0].toIntOrNull() ?: continue
                            val userName = parts[1]
                            val flags = parts[2].toIntOrNull() ?: 0
                            
                            // Flag 0x20 = MANAGED_PROFILE, 0x30 = managed profile type
                            if ((flags and 0x20) != 0 || (flags and 0x30) == 0x30) {
                                Log.w(TAG, "Found managed profile: userId=$userId name=$userName flags=$flags")
                                profiles.add(Pair(userId, userName))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Error parsing UserInfo line: $line")
                }
            }
        }

        return profiles
    }

    /**
     * Remove a managed profile by user ID.
     * After removal, device can become Device Owner.
     */
    fun removeManagedProfile(userId: Int): Boolean {
        if (!isRunning()) throw Exception("Shizuku is not running.")
        if (!hasPermission()) throw Exception("Shizuku permission not granted.")
        val s = shell ?: throw Exception("Shell not connected yet.")

        Log.i(TAG, "Running: pm remove-user $userId")
        val output = s.executeNow("pm remove-user $userId")
        Log.d(TAG, "pm remove-user output: $output")

        return output.isBlank() || output.contains("Success", ignoreCase = true)
    }

    /**
     * Check for hidden or synced accounts on the device via `dumpsys account list`.
     * Returns a user-readable string: either "No accounts detected" or a list of found accounts.
     * Requires Shizuku to be running and shell to be connected.
     */
    fun checkHiddenAccounts(): String = try {
        val s = shell ?: return "Shell not connected. Wait a moment and try again."
        Log.i(TAG, "Running: dumpsys account list")
        val output = s.executeNow("dumpsys account list")
        Log.d(TAG, "dumpsys output: $output")

        when {
            output.isBlank() || output.contains("Accounts: 0", ignoreCase = true) ||
                output.contains("No accounts", ignoreCase = true) -> {
                Log.i(TAG, "No accounts detected")
                "✓ No accounts detected. You can now set Device Owner."
            }
            else -> {
                Log.w(TAG, "Found accounts: $output")
                "⚠️ ACCOUNTS DETECTED:\n\n$output\n\nRemove them before setting Device Owner."
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "checkHiddenAccounts failed: ${e.message}")
        "Error checking accounts: ${e.message}"
    }

    companion object {
        private const val TAG = "ShizukuManager"
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val RC_PERMISSION = 1
        private const val SHELL_VERSION = 1
        private const val DEVICE_ADMIN_COMPONENT = "me.lucky.wasted/.admin.DeviceAdminReceiver"
    }
}
