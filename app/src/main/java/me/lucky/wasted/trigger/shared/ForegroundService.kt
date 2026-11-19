package me.lucky.wasted.trigger.shared

import android.app.KeyguardManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.UserManager
import android.util.Log
import androidx.core.app.NotificationCompat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

import me.lucky.wasted.Preferences
import me.lucky.wasted.R
import me.lucky.wasted.Trigger
import me.lucky.wasted.Utils
import me.lucky.wasted.p2p.P2PController
import me.lucky.wasted.remote.RemoteEngine
import me.lucky.wasted.remote.data.RemoteBootPolicy
import me.lucky.wasted.remote.data.RemoteConfigStore
import me.lucky.wasted.remote.data.RemoteRealtimeClient
import me.lucky.wasted.remote.data.RemoteRuntime
import me.lucky.wasted.remote.domain.SyncOutcome
import me.lucky.wasted.remote.work.RemoteRecoveryWorker
import me.lucky.wasted.trigger.lock.TimerCoordinator

class ForegroundService : Service() {
    companion object {
        const val ACTION_RECONFIGURE = "me.lucky.wasted.action.RECONFIGURE"
        const val ACTION_RESTART_REMOTE = "me.lucky.wasted.action.RESTART_REMOTE"
        private const val TAG = "ForegroundService"
        private const val NOTIFICATION_ID = 1000
        private const val NOTIFICATION_ID_REMOTE_SYNC = 1001
        private const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        private const val REMOTE_HEARTBEAT_MS = 60_000L

        @Volatile
        var isRunning = false
            private set
        @Volatile
        var isRemoteRunning = false
            private set

        /** Rebuilds the remote client after enrollment so it loads the newly saved session. */
        fun restartRemote(ctx: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                ctx.applicationContext,
                Intent(ctx.applicationContext, ForegroundService::class.java)
                    .setAction(ACTION_RESTART_REMOTE),
            )
        }
    }

    private var prefs: Preferences? = null
    private val remoteConfigStore by lazy { RemoteConfigStore.new(this) }

    private var lockReceiver: LockReceiver? = null
    private var usbReceiver: UsbReceiver? = null
    private var p2pStarted = false

    private var remoteStarted = false
    private var remoteScope: CoroutineScope? = null
    private var remoteRealtime: RemoteRealtimeClient? = null
    private var remoteEngine: RemoteEngine? = null
    private var remoteNetworkCallback: ConnectivityManager.NetworkCallback? = null

    private val userUnlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != Intent.ACTION_USER_UNLOCKED) return
            Log.i(TAG, "USER_UNLOCKED received; switching remote to credential storage")
            (application as? me.lucky.wasted.Application)?.initializeAfterUnlock()
            RemoteBootPolicy.new(this@ForegroundService)
                .applyTo(Preferences.new(this@ForegroundService))
            RemoteRuntime.reset()
            prefs = null
            stopRemote()
            reconfigure()
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            userUnlockReceiver,
            android.content.IntentFilter(Intent.ACTION_USER_UNLOCKED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        runCatching { unregisterReceiver(userUnlockReceiver) }
        unregisterSubsystemReceivers()
        stopP2p()
        stopRemote()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.i(TAG, "onStartCommand action=${intent?.action}")
        if (intent?.action == ACTION_RESTART_REMOTE) stopRemote()
        reconfigure()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? { return null }

    /**
     * Idempotent. startForeground runs before any subsystem side effect;
     * each subsystem is then diffed independently of the others.
     */
    private fun reconfigure() {
        val unlocked = getSystemService(UserManager::class.java).isUserUnlocked
        val remoteActive = remoteConfigStore.remoteEnabled && remoteConfigStore.isConfigured
        val desired = if (unlocked) {
            val prefs = prefs ?: Preferences.new(this).also { prefs = it }
            SubsystemDesired.from(
                triggers = prefs.triggers,
                p2pEnabled = prefs.p2pEnabled,
                remoteActive = remoteActive,
            )
        } else {
            SubsystemDesired(false, false, false, remoteActive)
        }
        Log.i(TAG, "reconfigure unlocked=$unlocked remote=$remoteActive desired=$desired")
        startForegroundWithTypes(desired)
        syncLockReceiver(desired.lockReceiver)
        syncUsbReceiver(desired.usbReceiver)
        syncP2p(desired.p2p)
        syncRemote(desired.remote)
        if (!desired.hasActiveSubsystem()) stopSelf()
    }

    private fun syncLockReceiver(wanted: Boolean) {
        if (wanted == (lockReceiver != null)) return
        if (wanted) {
            val receiver = LockReceiver(getSystemService(KeyguardManager::class.java).isDeviceLocked)
            registerReceiver(receiver, IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_OFF)
            })
            lockReceiver = receiver
        } else {
            lockReceiver?.let { runCatching { unregisterReceiver(it) } }
            lockReceiver = null
        }
    }

    private fun syncUsbReceiver(wanted: Boolean) {
        if (wanted == (usbReceiver != null)) return
        if (wanted) {
            val receiver = UsbReceiver()
            registerReceiver(receiver, IntentFilter(ACTION_USB_STATE))
            usbReceiver = receiver
        } else {
            usbReceiver?.let { runCatching { unregisterReceiver(it) } }
            usbReceiver = null
        }
    }

    private fun syncP2p(wanted: Boolean) {
        if (wanted == p2pStarted) return
        if (wanted) {
            P2PController.tryGetInstance(this)
                .onSuccess {
                    it.start()
                    p2pStarted = true
                }
                .onFailure {
                    p2pStarted = false
                    Log.e(TAG, "P2P storage unavailable; P2P disabled")
                }
        } else {
            stopP2p()
        }
    }

    private fun stopP2p() {
        if (!p2pStarted) return
        P2PController.instanceOrNull()?.stop()
        p2pStarted = false
    }

    private fun syncRemote(wanted: Boolean) {
        // Direct-boot mode is intentionally allowed: the remote credentials/config and the
        // minimal lock/wipe policy live in device-encrypted storage. On USER_UNLOCKED the
        // receiver restarts this subsystem into the normal credential-backed mode.
        if (wanted == remoteStarted) return
        if (wanted) startRemote() else stopRemote()
    }

    private fun startRemote() {
        try {
            val directBoot = !getSystemService(UserManager::class.java).isUserUnlocked
            val engine = RemoteEngine.create(this, directBoot = directBoot) ?: return
            val realtime = RemoteRealtimeClient { engine.repository.clientForRealtime() }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            remoteStarted = true
            remoteScope = scope
            remoteRealtime = realtime
            remoteEngine = engine

            val runSync: suspend () -> Unit = {
                val outcome = runCatching { engine.runSync() }.getOrNull()
                if (outcome is SyncOutcome.Failed) {
                    if (outcome.retryable && !directBoot) {
                        RemoteRecoveryWorker.scheduleOnce(this)
                    }
                    if (engine.credentials.notificationsEnabled) {
                        NotificationManager(this).notifyRemoteEvent(
                            NOTIFICATION_ID_REMOTE_SYNC,
                            "Remote sync failed",
                            outcome.reason,
                        )
                    }
                }
            }

            // Realtime is an optimization; retry it whenever the app is online or the service
            // starts. The HTTP heartbeat below is the reliable path if Realtime is unavailable.
            // Before first unlock, keep the direct-boot path on HTTP and start Realtime after
            // the credential-backed handoff.
            val ensureRealtime: suspend () -> Unit = {
                if (!directBoot && !realtime.isStarted && engine.credentials.enrolled) {
                    val deviceId = engine.credentials.deviceId
                    if (deviceId != null) {
                        runCatching {
                            realtime.start(scope, deviceId, engine.credentials.accountId) {
                                scope.launch { runSync() }
                            }
                        }.onFailure { Log.w(TAG, "Realtime start failed") }
                    }
                }
            }

            scope.launch { ensureRealtime() }

            // Heartbeat: reconcile every 60s while enabled, enrolled, unlocked, and connected.
            scope.launch {
                while (isActive) {
                    if (hasNetwork()) {
                        ensureRealtime()
                        runSync()
                    }
                    delay(REMOTE_HEARTBEAT_MS)
                }
            }

            val callback = object : ConnectivityManager.NetworkCallback() {
                private fun syncWhenOnline() {
                    scope.launch {
                        ensureRealtime()
                        runSync()
                    }
                }

                override fun onAvailable(network: Network) = syncWhenOnline()

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: android.net.NetworkCapabilities,
                ) {
                    // A network can become validated after onAvailable (captive portal, Wi-Fi
                    // hand-off). Treat validation as the real "internet is back" signal.
                    if (networkCapabilities.hasCapability(
                            android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                        )
                    ) {
                        syncWhenOnline()
                    }
                }
            }
            runCatching {
                getSystemService(ConnectivityManager::class.java)
                    .registerDefaultNetworkCallback(callback)
            }.onFailure { Log.w(TAG, "Network callback registration failed") }
            remoteNetworkCallback = callback

            if (!directBoot) RemoteRecoveryWorker.schedulePeriodic(this)

            isRemoteRunning = true
            Log.i(TAG, "Remote subsystem started")

        } catch (e: Exception) {
            Log.e(TAG, "Remote subsystem failed to start: ${e.javaClass.simpleName}", e)
            isRemoteRunning = false
            remoteStarted = false
            remoteScope?.cancel()
            remoteScope = null
            remoteRealtime = null
            remoteEngine = null
        }
    }

    private fun stopRemote() {
        isRemoteRunning = false
        if (!remoteStarted) return
        remoteStarted = false

        remoteNetworkCallback?.let { callback ->
            runCatching {
                getSystemService(ConnectivityManager::class.java)
                    .unregisterNetworkCallback(callback)
            }
        }
        remoteNetworkCallback = null

        if (getSystemService(UserManager::class.java).isUserUnlocked) {
            RemoteRecoveryWorker.cancelPeriodic(this)
        }

        val scope = remoteScope
        val realtime = remoteRealtime
        val engine = remoteEngine
        remoteScope = null
        remoteRealtime = null
        remoteEngine = null
        if (scope == null) return

        // Unsubscribe Realtime and remove its channel, then cancel the remote scope.
        scope.launch {
            runCatching { realtime?.stop() }
            engine?.close()
            scope.cancel()
        }
        Log.i(TAG, "Remote subsystem stopped")
    }

    private fun hasNetwork(): Boolean = runCatching {
        getSystemService(ConnectivityManager::class.java).activeNetwork != null
    }.getOrDefault(false)

    private fun unregisterSubsystemReceivers() {
        lockReceiver?.let { runCatching { unregisterReceiver(it) } }
        lockReceiver = null
        usbReceiver?.let { runCatching { unregisterReceiver(it) } }
        usbReceiver = null
    }

    private fun startForegroundWithTypes(desired: SubsystemDesired) {
        val notification = NotificationCompat.Builder(this, NotificationManager.CHANNEL_DEFAULT_ID)
                .setContentTitle(getString(R.string.foreground_service_notification_title))
                .setSmallIcon(android.R.drawable.ic_delete)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

        val mask = ForegroundTypes.runtimeMask(Build.VERSION.SDK_INT, desired.p2p, desired.remote)
        Log.i(TAG, "startForeground mask=0x${mask.toString(16)}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mask != 0) {
            startForeground(NOTIFICATION_ID, notification, mask)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private class LockReceiver(private var locked: Boolean) : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (Preferences.new(context ?: return).triggers.and(Trigger.LOCK.value) == 0)
                return
            when (intent?.action) {
                Intent.ACTION_USER_PRESENT -> {
                    locked = false
                    TimerCoordinator.create(context).cancel()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    if (locked) return
                    locked = true
                    Thread(Runner(context, goAsync())).start()
                }
            }
        }

        private class Runner(
            private val ctx: Context,
            private val pendingResult: PendingResult,
        ) : Runnable {
            override fun run() {
                val timer = TimerCoordinator.create(ctx)
                var delay = 1000L
                while (!timer.schedule()) {
                    Thread.sleep(delay)
                    delay = delay.shl(1)
                }
                pendingResult.finish()
            }
        }
    }

    private class UsbReceiver : BroadcastReceiver() {
        companion object {
            private const val KEY_1 = "connected"
            private const val KEY_2 = "host_connected"
        }

        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_STATE) return
            val utils = Utils(context ?: return)
            if (!utils.isDeviceLocked()) return
            val extras = intent.extras ?: return
            if (!extras.getBoolean(KEY_1) && !extras.getBoolean(KEY_2)) return
            utils.fire(Trigger.USB)
        }
    }
}
