package dev.rnett.sources.mcp

import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists

/**
 * File utilities for robust and atomic file operations.
 * Mirrored from `gradle-mcp`.
 */
object FileUtils {
    private val logger = LoggerFactory.getLogger(FileUtils::class.java)

    /**
     * Atomically moves a file/directory from [source] to [target] if it doesn't already exist.
     * If [target] exists, [source] is deleted to maintain idempotency.
     * This ensures concurrent processes don't observe partial states or conflict.
     */
    fun atomicMoveIfAbsent(source: Path, target: Path) {
        if (target.exists()) {
            logger.debug("Target already exists, deleting source: $source")
            source.toFile().deleteRecursively()
            return
        }

        try {
            Files.createDirectories(target.parent)
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: FileAlreadyExistsException) {
            logger.debug("Target already exists (CAS collision), deleting source: $source")
            source.toFile().deleteRecursively()
        } catch (e: UnsupportedOperationException) {
            logger.warn("Atomic move not supported, falling back to standard move: $source -> $target")
            try {
                // Not atomic, but better than nothing as a fallback
                Files.move(source, target)
            } catch (e2: FileAlreadyExistsException) {
                logger.debug("Target already exists after fallback move, deleting source: $source")
                source.toFile().deleteRecursively()
            } catch (e2: Exception) {
                throw IOException("Failed to move $source to $target after fallback", e2)
            }
        } catch (e: Exception) {
            logger.error("Failed to move $source to $target", e)
            throw e
        }
    }
}
