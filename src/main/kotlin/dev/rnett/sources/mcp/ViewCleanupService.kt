package dev.rnett.sources.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ViewCleanupService(
    private val env: SourcesMcpEnvironment,
    private val maxAge: Duration = Duration.ofHours(24),
    private val cleanupInterval: Duration = Duration.ofMinutes(60),
) {
    private val logger = LoggerFactory.getLogger(ViewCleanupService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Performs an initial synchronous cleanup scan, then starts a background
     * scheduled cleanup that runs at [cleanupInterval].
     */
    fun start() {
        cleanup()
        scope.launch {
            while (isActive) {
                delay(cleanupInterval.toMillis())
                if (!isActive) break
                cleanup()
            }
        }
    }

    /**
     * Stops the background scheduled cleanup.
     */
    fun stop() {
        scope.cancel()
    }

    /**
     * Scans the views directory and removes view directories older than [maxAge].
     * Errors during deletion are logged and retried on the next cycle.
     */
    fun cleanup() {
        val cutoff = Instant.now() - maxAge
        val viewsDir = env.viewsDir

        if (!viewsDir.exists()) {
            logger.debug("Views directory does not exist, nothing to clean up: $viewsDir")
            return
        }

        val viewDirs = Files.list(viewsDir).use { stream ->
            stream.filter { it.isDirectory() }.toList()
        }

        for (viewDir in viewDirs) {
            val timestamp = readTimestamp(viewDir)
            if (timestamp != null && timestamp.isAfter(cutoff)) {
                logger.debug("View directory is within retention period, skipping: ${viewDir.name}")
                continue
            }

            logger.info("Removing expired view directory: ${viewDir.name}")
            try {
                viewDir.deleteRecursively()
            } catch (e: IOException) {
                logger.warn("Failed to delete view directory ${viewDir.name}, will retry on next cycle", e)
            }
        }
    }

    private fun readTimestamp(viewDir: Path): Instant? {
        val manifestFile = viewDir.resolve("manifest.json")
        if (manifestFile.exists()) {
            try {
                val element = json.parseToJsonElement(manifestFile.readText())
                val timestamp = element.jsonObject["timestamp"]?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it) }
                if (timestamp != null) return timestamp
            } catch (e: Exception) {
                logger.debug("Failed to read timestamp from manifest for view ${viewDir.name}, falling back to last-modified", e)
            }
        }

        return try {
            viewDir.getLastModifiedTime().toInstant()
        } catch (e: Exception) {
            logger.debug("Failed to read last-modified time for view ${viewDir.name}", e)
            null
        }
    }
}