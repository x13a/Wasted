package me.lucky.wasted.remote.data

import android.content.Context
import android.content.ContextWrapper

/** Keeps device-protected storage identity even if a library asks for applicationContext. */
object DirectBootStorage {
    fun context(ctx: Context): Context {
        val protectedContext = ctx.createDeviceProtectedStorageContext()
        return object : ContextWrapper(protectedContext) {
            override fun getApplicationContext(): Context = this
            override fun createDeviceProtectedStorageContext(): Context = this
        }
    }
}
