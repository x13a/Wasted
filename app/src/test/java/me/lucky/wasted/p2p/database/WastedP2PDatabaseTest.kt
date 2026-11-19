package me.lucky.wasted.p2p.database

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WastedP2PDatabaseTest {

    private fun tempDir(): File = Files.createTempDirectory("wasted-p2p-db").toFile()

    @Test
    fun `absent database family is already clean`() {
        val dir = tempDir()
        assertTrue(WastedP2PDatabase.deleteDatabaseFamily(File(dir, "secure.db")))
        dir.deleteRecursively()
    }

    @Test
    fun `database and all sidecars are removed`() {
        val dir = tempDir()
        val main = File(dir, "secure.db")
        val artifacts = listOf(
            main,
            File(main.path + "-wal"),
            File(main.path + "-shm"),
            File(main.path + "-journal"),
        )
        artifacts.forEach { it.writeText("x") }

        assertTrue(WastedP2PDatabase.deleteDatabaseFamily(main))
        assertFalse(artifacts.any { it.exists() })
        dir.deleteRecursively()
    }

    @Test
    fun `orphan sidecars are removed when the main file is absent`() {
        val dir = tempDir()
        val main = File(dir, "secure.db")
        val orphan = File(main.path + "-wal").apply { writeText("x") }

        assertTrue(WastedP2PDatabase.deleteDatabaseFamily(main))
        assertFalse(orphan.exists())
        dir.deleteRecursively()
    }
}
