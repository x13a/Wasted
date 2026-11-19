package me.lucky.wasted.shizuku

import me.lucky.wasted.IRemoteShell
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * UserService that runs inside the Shizuku (ADB-level) process.
 * Commands executed here have ADB shell privileges — no root required.
 * Shizuku starts this class in its own process via bindUserService().
 */
class ShizukuShell : IRemoteShell.Stub() {

    override fun executeNow(command: String): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(proc.errorStream)).use { it.readText() }
            proc.waitFor()
            when {
                stderr.isNotBlank() -> "ERROR: ${stderr.trim()}\n${stdout.trim()}".trim()
                else -> stdout.trim()
            }
        } catch (e: Exception) {
            "EXCEPTION: ${e.message}"
        }
    }
}
