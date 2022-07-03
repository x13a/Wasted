package me.lucky.wasted

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import java.util.*
import kotlin.concurrent.timerTask

@RequiresApi(Build.VERSION_CODES.N)
class TileService : TileService() {
    companion object {
        private const val SAFE_DELAY = 2000L
    }

    private lateinit var prefs: Preferences
    private lateinit var admin: DeviceAdminManager
    private var counter = 0
    private var timer: Timer? = null

    override fun onCreate() {
        super.onCreate()
        init()
    }

    private fun init() {
        prefs = Preferences.new(this)
        admin = DeviceAdminManager(this)
    }

    override fun onStartListening() {
        super.onStartListening()
        update(
            if (prefs.isEnabled && admin.isActive()) Tile.STATE_INACTIVE
            else Tile.STATE_UNAVAILABLE
        )
    }

    override fun onClick() {
        super.onClick()
        if (!prefs.isEnabled || prefs.triggers.and(Trigger.TILE.value) == 0) return
        if (!prefs.isWipeData) {
            try {
                admin.lockNow()
            } catch (exc: SecurityException) {}
            return
        }
        val v = counter
        counter++
        when (v) {
            0 -> {
                update(Tile.STATE_ACTIVE)
                timer?.cancel()
                timer = Timer()
                timer?.schedule(timerTask {
                    try {
                        admin.lockNow()
                        admin.wipeData()
                    } catch (exc: SecurityException) {}
                }, SAFE_DELAY)
            }
            else -> {
                timer?.cancel()
                update(Tile.STATE_INACTIVE)
                counter = 0
            }
        }
    }

    private fun update(tileState: Int) {
        qsTile.state = tileState
        qsTile.updateTile()
    }
}
