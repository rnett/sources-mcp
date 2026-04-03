package dev.rnett.sources.mcp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Manages file-based advisory locks with timeout and retry logic.
 * Mirrored from `gradle-mcp` for robust concurrent access on Windows.
 */
object FileLockManager {
    private val logger = LoggerFactory.getLogger(FileLockManager::class.java)

    /**
     * Executes the given [block] while holding an exclusive lock on [lockFile].
     *
     * @param lockFile The path to the lock file.
     * @param timeout The maximum time to wait for the lock. Defaults to 60s.
     * @param pollDelay The time to wait between lock acquisition attempts. Defaults to 500ms.
     * @throws IOException If the lock cannot be acquired within the timeout or if an I/O error occurs.
     */
    fun <T> withLock(
        lockFile: Path,
        timeout: Duration = 60.seconds,
        pollDelay: Duration = 500.milliseconds,
        block: () -> T
    ): T {
        val start = System.currentTimeMillis()
        val timeoutMillis = timeout.inWholeMilliseconds

        while (true) {
            try {
                Files.createDirectories(lockFile.parent)

                // Use StandardOpenOption.CREATE and StandardOpenOption.WRITE to ensure the file exists and is writable.
                FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    val lock = try {
                        channel.tryLock()
                    } catch (e: OverlappingFileLockException) {
                        // Locked by another thread in the same JVM
                        null
                    } catch (e: IOException) {
                        // Handle transient Windows "access is denied" which can happen if another process 
                        // just closed the handle or is in a middle of an operation.
                        if (e.message?.contains("access is denied", ignoreCase = true) == true) {
                            null
                        } else {
                            throw e
                        }
                    }

                    if (lock != null) {
                        return try {
                            block()
                        } finally {
                            lock.release()
                        }
                    }
                }
            } catch (e: IOException) {
                // If it's a permanent failure (e.g. invalid path), we might want to throw.
                // But generally, we retry until timeout for any transient issues.
                if (System.currentTimeMillis() - start > timeoutMillis) {
                    throw e
                }
            }

            if (System.currentTimeMillis() - start > timeoutMillis) {
                throw IOException("Timeout acquiring lock on $lockFile after $timeout")
            }

            // Using runBlocking for delay to support non-coroutine callers.
            runBlocking {
                delay(pollDelay)
            }
        }
    }
}
