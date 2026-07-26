package dev.rnett.sources.mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.RandomAccessFile
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class ViewCleanupServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun testOldViewsAreDeleted() {
        val env = SourcesMcpEnvironment(tempDir)
        val viewsDir = env.viewsDir
        val service = ViewCleanupService(env, maxAge = Duration.ofSeconds(1))

        val viewDir = viewsDir.resolve("old-view")
        viewDir.createDirectories()
        val oldTimestamp = Instant.now().minusSeconds(7200)
        viewDir.resolve("manifest.json").writeText("""{"sessionId": "test", "timestamp": "$oldTimestamp", "projectRoot": "/tmp", "scopes": [], "depHash": "abc", "dependencies": []}""")

        service.cleanup()

        assertFalse(viewDir.exists())
    }

    @Test
    fun testNewViewsAreNotDeleted() {
        val env = SourcesMcpEnvironment(tempDir)
        val viewsDir = env.viewsDir
        val service = ViewCleanupService(env, maxAge = Duration.ofMinutes(5))

        val viewDir = viewsDir.resolve("new-view")
        viewDir.createDirectories()
        viewDir.resolve("manifest.json").writeText("""{"sessionId": "test", "timestamp": "${Instant.now()}", "projectRoot": "/tmp", "scopes": [], "depHash": "abc", "dependencies": []}""")

        service.cleanup()

        assertTrue(viewDir.exists())
    }

    @Test
    fun testCleanupWithNoViewsDirectory() {
        val env = SourcesMcpEnvironment(tempDir)
        val viewsDir = env.viewsDir
        viewsDir.deleteRecursively()

        val service = ViewCleanupService(env, maxAge = Duration.ofSeconds(1))
        service.cleanup()

        // Verify cleanup does NOT recreate the views directory
        assertFalse(viewsDir.exists(), "cleanup should not recreate deleted views directory")
    }

    @Test
    fun testCleanupWithInvalidManifest() {
        val env = SourcesMcpEnvironment(tempDir)
        val viewsDir = env.viewsDir
        val service = ViewCleanupService(env, maxAge = Duration.ofSeconds(10))

        val viewDir = viewsDir.resolve("invalid-view")
        viewDir.createDirectories()
        viewDir.resolve("manifest.json").writeText("not valid json")

        service.cleanup()

        assertTrue(viewDir.exists())
    }

    @Test
    fun testCleanupWithDeletionFailure() {
        val env = SourcesMcpEnvironment(tempDir)
        val viewsDir = env.viewsDir
        val service = ViewCleanupService(env, maxAge = Duration.ofSeconds(1))

        val viewDir = viewsDir.resolve("locked-view")
        viewDir.createDirectories()
        val oldTimestamp = Instant.now().minusSeconds(7200)
        viewDir.resolve("manifest.json").writeText("""{"sessionId": "test", "timestamp": "$oldTimestamp", "projectRoot": "/tmp", "scopes": [], "depHash": "abc", "dependencies": []}""")
        val lockedFile = viewDir.resolve("locked-file")
        lockedFile.writeText("test")
        val raf = RandomAccessFile(lockedFile.toFile(), "rw")

        try {
            service.cleanup()
            assertTrue(viewDir.exists())
        } finally {
            raf.close()
            viewDir.deleteRecursively()
        }
    }

    // --- start()/stop() lifecycle tests ---

    @Test
    fun testStartPerformsInitialCleanup() {
        val env = SourcesMcpEnvironment(tempDir)
        val viewsDir = env.viewsDir
        val service = ViewCleanupService(env, maxAge = Duration.ofSeconds(1))

        val viewDir = viewsDir.resolve("start-cleanup-view")
        viewDir.createDirectories()
        val oldTimestamp = Instant.now().minusSeconds(7200)
        viewDir.resolve("manifest.json").writeText("""{"sessionId": "test", "timestamp": "$oldTimestamp", "projectRoot": "/tmp", "scopes": [], "depHash": "abc", "dependencies": []}""")

        service.start()
        service.stop()

        assertFalse(viewDir.exists(), "start() should perform initial cleanup synchronously")
    }

    @Test
    fun testStopIsNoOpOnUnstartedService() {
        val env = SourcesMcpEnvironment(tempDir)
        val viewsDir = env.viewsDir

        val service = ViewCleanupService(env, maxAge = Duration.ofHours(24))
        // stop() on a never-started service should not throw or hang
        service.stop()
        // If we reach here without exception, test passes
    }

    // --- readTimestamp fallback tests ---

    @Test
    fun testReadTimestampFallbackToLastModifiedWhenNoTimestampKey() {
        val env = SourcesMcpEnvironment(tempDir)
        val viewsDir = env.viewsDir
        val service = ViewCleanupService(env, maxAge = Duration.ofDays(365))

        val viewDir = viewsDir.resolve("no-timestamp-view")
        viewDir.createDirectories()
        viewDir.resolve("manifest.json").writeText("""{"sessionId": "test", "projectRoot": "/tmp"}""")

        service.cleanup()
        assertTrue(viewDir.exists(), "View with valid JSON but no timestamp key should not crash cleanup")
    }

    @Test
    fun testReadTimestampFallbackToLastModifiedWhenNoManifestFile() {
        val env = SourcesMcpEnvironment(tempDir)
        val viewsDir = env.viewsDir
        val service = ViewCleanupService(env, maxAge = Duration.ofDays(365))

        val viewDir = viewsDir.resolve("no-manifest-view")
        viewDir.createDirectories()

        service.cleanup()
        assertTrue(viewDir.exists(), "View with no manifest should not crash cleanup")
    }
}
