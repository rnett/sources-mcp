package dev.rnett.sources.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.assertTrue

class DependencySourcesDownloaderTest {

    @TempDir
    lateinit var tempDir: Path

    @TempDir
    lateinit var testHomeDir: Path

    private lateinit var testEnv: SourcesMcpEnvironment
    private lateinit var analyzer: DependencyAnalyzer
    private lateinit var downloader: DependencySourcesDownloader

    @BeforeEach
    fun setup() {
        testEnv = SourcesMcpEnvironment(testHomeDir)
        analyzer = DependencyAnalyzer(DependencyCacheService(testEnv))
        downloader = DependencySourcesDownloader(testEnv)
    }

    @Test
    fun testDownloadNpmSources() {
        val projectDir = tempDir.resolve("npm-project")
        testProject(projectDir) {
            npm {
                name("test-npm")
                addDependency("lodash", "4.17.21")
            }
        }

        val ortResult = analyzer.analyzeDependencies(projectDir)
        println("Packages found: ${ortResult.packages.map { it.id.toCoordinates() }}")

        val lodashPkg = ortResult.packages.find { it.id.name == "lodash" }
            ?: throw IllegalStateException("lodash package not found. Found: ${ortResult.packages.map { it.id.toCoordinates() }}. Analyzer result issues: ${ortResult.issues}")

        val casPath = downloader.downloadSources(lodashPkg)

        assertTrue(casPath.exists(), "CAS path should exist: $casPath")
        assertTrue(casPath.resolve("package.json").exists(), "Extracted sources should contain package.json")
    }

    @Test
    fun testDownloadMavenSources() {
        val projectDir = tempDir.resolve("maven-project")
        testProject(projectDir) {
            maven {
                pom(
                    """
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>dev.rnett</groupId>
                        <artifactId>test-maven</artifactId>
                        <version>1.0.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>org.apache.commons</groupId>
                                <artifactId>commons-lang3</artifactId>
                                <version>3.12.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                """.trimIndent()
                )
            }
        }

        val ortResult = analyzer.analyzeDependencies(projectDir)
        println("Packages found (Maven): ${ortResult.packages.map { it.id.toCoordinates() }}")
        val lang3Pkg = ortResult.packages.find { it.id.name == "commons-lang3" }
            ?: throw IllegalStateException("commons-lang3 package not found. Found: ${ortResult.packages.map { it.id.toCoordinates() }}. Analyzer result issues: ${ortResult.issues}")

        val casPath = downloader.downloadSources(lang3Pkg)

        assertTrue(casPath.exists(), "CAS path should exist: $casPath")
        // Maven source artifacts usually contain the source files and the pom.xml
        assertTrue(casPath.resolve("pom.xml").exists() || casPath.resolve("META-INF").exists(), "Extracted sources should contain pom.xml or META-INF")
    }

    @Test
    fun testDownloadGradleSources() {
        val projectDir = tempDir.resolve("gradle-project")
        testProject(projectDir) {
            gradle {
                buildScript(
                    """
                    plugins { id 'java' }
                    repositories { mavenCentral() }
                    dependencies { implementation 'org.slf4j:slf4j-api:1.7.32' }
                """.trimIndent()
                )
            }
        }

        val ortResult = analyzer.analyzeDependencies(projectDir)
        val slf4jPkg = ortResult.packages.find { it.id.name == "slf4j-api" }
            ?: throw IllegalStateException("slf4j-api package not found")

        val casPath = downloader.downloadSources(slf4jPkg)

        assertTrue(casPath.exists(), "CAS path should exist: $casPath")
        assertTrue(casPath.resolve("META-INF").exists(), "Extracted sources should contain META-INF")
    }

    @Test
    fun testDownloadCargoSources() {
        val projectDir = tempDir.resolve("cargo-project")
        testProject(projectDir) {
            cargo {
                name("test-cargo")
                addDependency("serde", "1.0.152")
            }
        }

        val ortResult = analyzer.analyzeDependencies(projectDir)
        val serdePkg = ortResult.packages.find { it.id.name == "serde" }
            ?: throw IllegalStateException("serde package not found")

        val casPath = downloader.downloadSources(serdePkg)

        assertTrue(casPath.exists(), "CAS path should exist: $casPath")
        assertTrue(casPath.resolve("Cargo.toml").exists(), "Extracted sources should contain Cargo.toml")
    }

    @Test
    fun testConcurrentDownloads() = runTest {
        val projectDir = tempDir.resolve("stress-project")
        testProject(projectDir) {
            npm {
                name("stress-npm")
                addDependency("lodash", "4.17.21")
            }
        }

        val ortResult = analyzer.analyzeDependencies(projectDir)
        val lodashPkg = ortResult.packages.find { it.id.name == "lodash" }
            ?: throw IllegalStateException("lodash package not found in stress test. Found: ${ortResult.packages.map { it.id.toCoordinates() }}")

        val concurrency = 10
        val casPaths = (1..concurrency).map {
            async(Dispatchers.IO) {
                downloader.downloadSources(lodashPkg)
            }
        }.awaitAll()

        val firstPath = casPaths.first()
        assertTrue(casPaths.all { it == firstPath }, "All concurrent requests should return the same CAS path")
        assertTrue(firstPath.exists())
    }
}
