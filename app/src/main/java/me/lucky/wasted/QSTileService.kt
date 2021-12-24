package me.lucky.wasted

import android.app.admin.DevicePolicyManager
import android.content.Context
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
    private val dpm by lazy {
        getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }
    private val counter = AtomicInteger(0)
    private var timer: Timer? = null

    override fun onStartListening() {
        super.onStartListening()
        update(if (prefs.isServiceEnabled) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE)
    }

    override fun onClick() {
        super.onClick()
        if (!prefs.isServiceEnabled) return
        if (!prefs.doWipe) {
            dpm.lockNow()
            return
        }
        when (counter.getAndIncrement()) {
            0 -> {
                update(Tile.STATE_ACTIVE)
                timer?.cancel()
                timer = Timer()
                timer?.schedule(timerTask {
                    update(Tile.STATE_INACTIVE)
                    counter.set(0)
                }, 2000)
            }
            1 -> {
                dpm.lockNow()
                dpm.wipeData(0)
            }
        }
    }

    private fun update(tileState: Int) {
        qsTile.apply {
            state = tileState
            updateTile()
        }
    }
}
