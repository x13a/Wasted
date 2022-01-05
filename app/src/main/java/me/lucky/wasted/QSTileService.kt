package me.lucky.wasted

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.timerTask

@RequiresApi(Build.VERSION_CODES.N)
class QSTileService : TileService() {
    private val prefs by lazy { Preferences(this) }
    private val admin by lazy { DeviceAdminManager(this) }
    private val counter = AtomicInteger(0)
    private var timer: Timer? = null

    override fun onStartListening() {
        super.onStartListening()
        update(
            if (prefs.isServiceEnabled && admin.isActive()) Tile.STATE_INACTIVE
            else Tile.STATE_UNAVAILABLE
        )
    }

    override fun onClick() {
        super.onClick()
        if (!prefs.isServiceEnabled) return
        if (!prefs.isWipeData) {
            try {
                admin.lockNow()
            } catch (exc: SecurityException) {}
            return
        }
        when (counter.getAndIncrement()) {
            0 -> {
                update(Tile.STATE_ACTIVE)
                timer?.cancel()
                timer = Timer()
                timer?.schedule(timerTask {
                    try {
                        admin.lockNow()
                        admin.wipeData()
                    } catch (exc: SecurityException) {}
                }, 2000)
            }
            else -> {
                timer?.cancel()
                update(Tile.STATE_INACTIVE)
                counter.set(0)
            }
        }
    }

    private fun update(tileState: Int) {
        qsTile.state = tileState
        qsTile.updateTile()
    }
}