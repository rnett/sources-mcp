package dev.rnett.sources.mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class DependencyAnalyzerTest {

    @TempDir
    lateinit var tempDir: Path

    @TempDir
    lateinit var testHomeDir: Path

    private val testEnv by lazy { SourcesMcpEnvironment(testHomeDir) }
    private val testCacheService by lazy { DependencyCacheService(testEnv) }
    private val analyzer by lazy { DependencyAnalyzer(testCacheService) }

    @Test
    fun testAnalyzeEmptyDirectory() {
        assertFailsWith<IllegalArgumentException> {
            analyzer.analyzeDependencies(tempDir)
        }
    }

    @Test
    fun testAnalyzeNonExistentDirectory() {
        assertFailsWith<IllegalArgumentException> {
            analyzer.analyzeDependencies(tempDir.resolve("non-existent"))
        }
    }

    @Test
    fun testAnalyzeGradleProject() {
        tempDir.resolve("build.gradle").writeText(
            """
            plugins {
                id 'java'
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation 'org.apache.commons:commons-lang3:3.12.0'
            }
        """.trimIndent()
        )

        val result = analyzer.analyzeDependencies(tempDir)

        assertNotNull(result.analyzer?.result)
        val projects = result.getProjects()
        assertNotNull(projects.find { it.id.type == "Gradle" })
    }

    @Test
    fun testAnalyzeNpmProject() {
        tempDir.resolve("package.json").writeText(
            """
            {
              "name": "sample-npm-project",
              "version": "1.0.0",
              "dependencies": {
                "lodash": "4.17.21"
              }
            }
        """.trimIndent()
        )
        // dummy lockfile to satisfy ORT default config
        tempDir.resolve("package-lock.json").writeText("{}")

        val result = analyzer.analyzeDependencies(tempDir)

        assertNotNull(result.analyzer?.result)
        val projects = result.getProjects()
        assertNotNull(projects.find { it.id.type == "NPM" })
    }

    @Test
    fun testCentralCaching() {
        tempDir.resolve("pom.xml").writeText(
            """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>dev.rnett</groupId>
                <artifactId>caching-test</artifactId>
                <version>1.0.0</version>
            </project>
        """.trimIndent()
        )

        // First run - should analyze
        val result1 = analyzer.analyzeDependencies(tempDir)
        assertNotNull(result1.analyzer?.result)

        // Second run - should hit cache
        val result2 = analyzer.analyzeDependencies(tempDir)
        assertNotNull(result2.analyzer?.result)

        // Compare relevant parts of the result to ensure it's effectively the same
        assertEquals(result1.getProjects().map { it.id }, result2.getProjects().map { it.id })

        // Modify file - should re-analyze
        tempDir.resolve("pom.xml").writeText(
            """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>dev.rnett</groupId>
                <artifactId>caching-test-modified</artifactId>
                <version>1.0.0</version>
            </project>
        """.trimIndent()
        )

        val result3 = analyzer.analyzeDependencies(tempDir)
        assertNotNull(result3.analyzer?.result)

        val projects1 = result1.getProjects()
        val projects3 = result3.getProjects()

        println("Projects 1: ${projects1.map { it.id }}")
        println("Projects 3: ${projects3.map { it.id }}")

        assert(projects1.map { it.id.name } != projects3.map { it.id.name })
    }
}
