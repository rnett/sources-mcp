package dev.rnett.sources.mcp

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CheckDependenciesIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun testCheckAfterSync() = runTest {
        val projectDir = tempDir.resolve("npm-project")
        createNpmProject(projectDir, "test-check", "lodash" to "4.17.21")

        val testEnv = SourcesMcpEnvironment(tempDir.resolve("sources-mcp-home"))
        val cacheService = DependencyCacheService(testEnv)
        val service = SourcesViewService(
            dependencyAnalyzer = DependencyAnalyzer(cacheService),
            downloader = DependencySourcesDownloader(testEnv),
            env = testEnv
        )

        service.sync(projectDir)

        val check = service.check(projectDir)

        assertTrue(check.viewExists)
        assertTrue(check.fresh)
        assertNotNull(check.age)
        assertNotNull(check.depHash)
        assertTrue(check.depHash == check.currentDepHash)
        assertNotNull(check.dependencyCount)
        assertTrue(check.dependencyCount!! > 0)
    }

    @Test
    fun testCheckAfterManipulation() = runTest {
        val projectDir = tempDir.resolve("npm-project-manipulated")
        createNpmProject(projectDir, "test-manipulate", "lodash" to "4.17.21")

        val testEnv = SourcesMcpEnvironment(tempDir.resolve("sources-mcp-home-manipulated"))
        val cacheService = DependencyCacheService(testEnv)
        val service = SourcesViewService(
            dependencyAnalyzer = DependencyAnalyzer(cacheService),
            downloader = DependencySourcesDownloader(testEnv),
            env = testEnv
        )

        service.sync(projectDir)

        projectDir.resolve("package.json").writeText(
            """
        {
          "name": "test-manipulate",
          "version": "1.0.0",
          "dependencies": {
            "lodash": "4.17.21",
            "chalk": "5.0.0"
          }
        }
            """.trimIndent()
        )

        val check = service.check(projectDir)

        assertTrue(check.viewExists)
        assertTrue(!check.fresh)
        assertNotNull(check.depHash)
        assertNotEquals(check.depHash, check.currentDepHash)
        assertNotNull(check.dependencyCount)
        assertTrue(check.dependencyCount!! > 0)
    }
}
