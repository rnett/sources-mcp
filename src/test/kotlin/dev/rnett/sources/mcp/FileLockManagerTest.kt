package dev.rnett.sources.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

class FileLockManagerTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun testConcurrentAcquisition() = runTest {
        val lockFile = tempDir.resolve("test.lock")
        val counter = AtomicInteger(0)
        val concurrency = 5

        val jobs = (1..concurrency).map {
            async(Dispatchers.IO) {
                FileLockManager.withLock(lockFile) {
                    val current = counter.incrementAndGet()
                    // Use Thread.sleep here because withLock is not suspendable and we are in Dispatchers.IO
                    Thread.sleep(100)
                    assertEquals(1, current, "Each thread should see counter as 1 after its own increment because it's the only one in the block")
                    assertEquals(1, counter.get(), "Lock should be exclusive")
                    counter.decrementAndGet()
                }
            }
        }

        jobs.awaitAll()
        assertEquals(0, counter.get())
    }

    @Test
    fun testTimeout() = runTest {
        val lockFile = tempDir.resolve("timeout.lock")

        // Acquire lock in another coroutine and hold it
        val job = async(Dispatchers.IO) {
            FileLockManager.withLock(lockFile) {
                Thread.sleep(1000)
            }
        }

        // Wait a bit to ensure the lock is acquired
        delay(200.milliseconds)

        // Try to acquire lock with a shorter timeout than the holder's delay
        assertFailsWith<java.io.IOException> {
            FileLockManager.withLock(lockFile, timeout = 300.milliseconds, pollDelay = 100.milliseconds) {
                // should not reach here
            }
        }

        job.await()
    }
}
