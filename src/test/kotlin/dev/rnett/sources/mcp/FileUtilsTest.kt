package dev.rnett.sources.mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileUtilsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun testAtomicMoveIfAbsentSuccessful() {
        val source = tempDir.resolve("source")
        source.writeText("content")
        val target = tempDir.resolve("target")

        FileUtils.atomicMoveIfAbsent(source, target)

        assertTrue(target.exists(), "Target should exist")
        assertFalse(source.exists(), "Source should be moved")
        assertEquals("content", target.readText())
    }

    @Test
    fun testAtomicMoveIfAbsentCollision() {
        val source = tempDir.resolve("source_collision")
        source.writeText("new content")
        val target = tempDir.resolve("target_collision")
        target.writeText("original content")

        FileUtils.atomicMoveIfAbsent(source, target)

        assertTrue(target.exists(), "Target should still exist")
        assertFalse(source.exists(), "Source should be deleted on collision")
        assertEquals("original content", target.readText(), "Target content should not be overwritten")
    }

    @Test
    fun testAtomicMoveIfAbsentDirectory() {
        val source = tempDir.resolve("source_dir")
        source.createDirectory()
        source.resolve("file.txt").writeText("content")
        val target = tempDir.resolve("target_dir")

        FileUtils.atomicMoveIfAbsent(source, target)

        assertTrue(target.exists(), "Target directory should exist")
        assertTrue(target.resolve("file.txt").exists(), "Target subdirectory file should exist")
        assertFalse(source.exists(), "Source directory should be moved")
        assertEquals("content", target.resolve("file.txt").readText())
    }
}
