package dev.rnett.sources.mcp

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SourcesViewServiceIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun testSyncEndToEnd() = runTest {
        val projectDir = tempDir.resolve("npm-project")
        createNpmProject(projectDir, "test-sync", "lodash" to "4.17.21")

        val testEnv = SourcesMcpEnvironment(tempDir.resolve("sources-mcp-home"))
        val cacheService = DependencyCacheService(testEnv)
        val service = SourcesViewService(
            dependencyAnalyzer = DependencyAnalyzer(cacheService),
            downloader = DependencySourcesDownloader(testEnv),
            env = testEnv
        )

        val view = service.sync(projectDir)

        assertNotNull(view.sessionId)
        assertTrue(runCatching { UUID.fromString(view.sessionId) }.isSuccess)

        assertTrue(view.sourcesDir.exists())

        val manifestFile = view.baseDir.resolve("manifest.json")
        assertTrue(manifestFile.exists())
        assertTrue(manifestFile.toFile().readText().isNotBlank())

        assertTrue(view.manifest.dependencies.isNotEmpty())

        val sourceEntries = view.sourcesDir.toFile().listFiles()
        assertNotNull(sourceEntries)
        assertTrue(sourceEntries.isNotEmpty())
    }

    @Test
    fun testSyncGradleEndToEnd() = runTest {
        val projectDir = tempDir.resolve("gradle-project")
        createGradleProject(projectDir, "test-gradle-sync", "org.slf4j:slf4j-api:1.7.32")

        val testEnv = SourcesMcpEnvironment(tempDir.resolve("sources-mcp-home-gradle"))
        val cacheService = DependencyCacheService(testEnv)
        val service = SourcesViewService(
            dependencyAnalyzer = DependencyAnalyzer(cacheService),
            downloader = DependencySourcesDownloader(testEnv),
            env = testEnv
        )

        val view = service.sync(projectDir)

        assertNotNull(view.sessionId)
        assertTrue(runCatching { UUID.fromString(view.sessionId) }.isSuccess)
        assertTrue(view.sourcesDir.exists())

        val manifestFile = view.baseDir.resolve("manifest.json")
        assertTrue(manifestFile.exists())
        assertTrue(manifestFile.toFile().readText().isNotBlank())

        assertTrue(view.manifest.dependencies.isNotEmpty())
    }
}
