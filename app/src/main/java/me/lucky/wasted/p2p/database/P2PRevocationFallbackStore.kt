package me.lucky.wasted.p2p.database

import android.content.Context

/** Plain app-private fail-closed marker set used if encrypted preference writes fail. */
class P2PRevocationFallbackStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun pending(): Set<String> = prefs.getStringSet(KEY_IDS, emptySet())?.toSet() ?: emptySet()

    fun replace(ids: Set<String>): Boolean = update(ids)

    private fun update(ids: Set<String>): Boolean = prefs.edit().putStringSet(KEY_IDS, ids).commit()

    private companion object {
        const val FILE_NAME = "wasted_p2p_revocations"
        const val KEY_IDS = "pending_ids"
    }
}
