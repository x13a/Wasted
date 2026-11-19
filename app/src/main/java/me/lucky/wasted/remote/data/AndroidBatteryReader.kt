package me.lucky.wasted.remote.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

import me.lucky.wasted.remote.domain.BatterySnapshot
import me.lucky.wasted.remote.domain.BatteryStatusProvider

/**
 * Reads the current battery from the sticky ACTION_BATTERY_CHANGED broadcast.
 * No runtime permission or receiver registration is required.
 */
class AndroidBatteryReader(private val ctx: Context) : BatteryStatusProvider {
    override fun snapshot(): BatterySnapshot {
        val intent = runCatching {
            ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        return fromBatteryIntent(intent)
    }

    companion object {
        internal fun fromBatteryIntent(intent: Intent?): BatterySnapshot {
            if (intent == null) return BatterySnapshot(null, null)
            return fromBroadcast(
                level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1),
            )
        }

        /** Pure parser used by JVM tests. */
        internal fun fromBroadcast(level: Int, scale: Int, status: Int): BatterySnapshot {
            val percent = if (scale > 0 && level in 0..scale) {
                ((level.toLong() * 100 + scale / 2) / scale).toInt().coerceIn(0, 100)
            } else {
                null
            }
            val charging = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING,
                BatteryManager.BATTERY_STATUS_FULL,
                -> true
                BatteryManager.BATTERY_STATUS_DISCHARGING,
                BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                -> false
                else -> null
            }
            return BatterySnapshot(percent, charging)
        }
    }
}
