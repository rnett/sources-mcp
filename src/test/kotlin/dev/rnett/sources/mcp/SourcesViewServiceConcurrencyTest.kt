package dev.rnett.sources.mcp

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.ossreviewtoolkit.model.AnalyzerResult
import org.ossreviewtoolkit.model.DependencyGraph
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.VcsInfo
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SourcesViewServiceConcurrencyTest {

    @TempDir
    lateinit var tempDir: Path

    private fun makePkgId(name: String) =
        Identifier("Maven", "com.example", name, "1.0.0")

    private fun makePkg(id: Identifier) = Package(
        id = id,
        declaredLicenses = emptySet(),
        description = "Test package",
        homepageUrl = "https://example.com",
        binaryArtifact = RemoteArtifact.EMPTY,
        sourceArtifact = RemoteArtifact.EMPTY,
        vcs = VcsInfo.EMPTY,
    )

    private fun makeMinimalAnalyzerResult(vararg pkgs: Package): AnalyzerResult {
        val pkgIds = pkgs.map { it.id }
        return AnalyzerResult(
            projects = emptySet(),
            packages = pkgs.toSet(),
            dependencyGraphs = mapOf(
                "Maven" to DependencyGraph(
                    packages = pkgIds,
                    scopes = mapOf(DependencyGraph.qualifyScope(pkgIds[0], "compile") to emptyList()),
                    nodes = pkgIds.mapIndexed { i, _ ->
                        org.ossreviewtoolkit.model.DependencyGraphNode(pkg = i, fragment = 0)
                    },
                    edges = emptySet()
                )
            ),
        )
    }

    @Test
    fun testConcurrentSyncSameKeyCreatesOneView() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val pkg = makePkg(makePkgId("concurrent-lib"))
        val result = makeMinimalAnalyzerResult(pkg)
        val mockAnalyzer = mockk<DependencyAnalyzer> {
            every { analyzeDependencies(any(), any(), any()) } returns result
            every { computeDepHash(any()) } returns "hash-concurrent"
        }
        val mockDownloader = mockk<DependencySourcesDownloader>(relaxed = true) {
            every { downloadSources(any(), any()) } returns tempDir.resolve("cas").also { it.createDirectories(); it.resolve("File.kt").writeText("") }
        }

        val env = SourcesMcpEnvironment(tempDir)
        val service = SourcesViewService(
            dependencyAnalyzer = mockAnalyzer,
            downloader = mockDownloader,
            env = env,
            normalizationService = NoOpNormalizationService,
        )

        val projectDir = tempDir.resolve("project").also { it.createDirectories() }

        // Launch 5 concurrent syncs for same key
        val jobs = (1..5).map {
            async(testDispatcher) {
                service.sync(projectDir)
            }
        }

        // Advance virtual time until all coroutines complete
        testScheduler.advanceUntilIdle()

        val results = jobs.awaitAll()
        val first = results.first()
        results.forEach { assertTrue(it === first, "All results should be the same object") }

        // Analysis must have been called exactly once for this key
        verify(exactly = 1) { mockAnalyzer.analyzeDependencies(any(), any(), any()) }
    }

    @Test
    fun testConcurrentSyncDifferentKeysProceedIndependently() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val pkg = makePkg(makePkgId("diff-key-lib"))
        val result = makeMinimalAnalyzerResult(pkg)
        val mockAnalyzer = mockk<DependencyAnalyzer> {
            every { analyzeDependencies(any(), any(), any()) } returns result
            every { computeDepHash(any()) } returns "hash-diff-key"
        }
        val mockDownloader = mockk<DependencySourcesDownloader>(relaxed = true) {
            every { downloadSources(any(), any()) } returns tempDir.resolve("cas").also { it.createDirectories(); it.resolve("File.kt").writeText("") }
        }

        val env = SourcesMcpEnvironment(tempDir)
        val service = SourcesViewService(
            dependencyAnalyzer = mockAnalyzer,
            downloader = mockDownloader,
            env = env,
            normalizationService = NoOpNormalizationService,
        )

        val projectDir1 = tempDir.resolve("project1").also { it.createDirectories() }
        val projectDir2 = tempDir.resolve("project2").also { it.createDirectories() }

        val job1 = async(testDispatcher) { service.sync(projectDir1) }
        val job2 = async(testDispatcher) { service.sync(projectDir2) }

        testScheduler.advanceUntilIdle()

        val result1 = job1.await()
        val result2 = job2.await()

        assertNotEquals(result1.sessionId, result2.sessionId)

        // Two different keys → two analyses
        verify(exactly = 2) { mockAnalyzer.analyzeDependencies(any(), any(), any()) }
    }

    @Test
    fun testParallelismLimitIsEnforced() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val numPackages = 10
        val configuredParallelism = 2

        val pkgs = (1..numPackages).map { makePkg(makePkgId("parallel-$it")) }
        val result = makeMinimalAnalyzerResult(*pkgs.toTypedArray())
        val mockAnalyzer = mockk<DependencyAnalyzer> {
            every { analyzeDependencies(any(), any(), any()) } returns result
            every { computeDepHash(any()) } returns "hash-parallel"
        }

        val inFlight = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)

        val env = SourcesMcpEnvironment(tempDir)

        val mockDownloader = mockk<DependencySourcesDownloader>(relaxed = true) {
            coEvery { downloadSources(any(), any()) } coAnswers {
                val current = inFlight.incrementAndGet()
                maxConcurrent.updateAndGet { kotlin.math.max(it, current) }
                // Simulate some work
                testScheduler.advanceTimeBy(10)
                inFlight.decrementAndGet()
                tempDir.resolve("cas").also { it.createDirectories(); it.resolve("File.kt").writeText("") }
            }
        }

        val service = SourcesViewService(
            dependencyAnalyzer = mockAnalyzer,
            downloader = mockDownloader,
            env = env,
            normalizationService = NoOpNormalizationService,
        )

        val projectDir = tempDir.resolve("parallel-project").also { it.createDirectories() }

        // Override the parallelism field via reflection to ensure controlled test
        val parallelismField = SourcesViewService::class.java.getDeclaredField("parallelism")
        parallelismField.isAccessible = true
        parallelismField.setInt(service, configuredParallelism)

        val syncJob = async(testDispatcher) {
            service.sync(projectDir)
        }

        testScheduler.advanceUntilIdle()
        val view = syncJob.await()

        assertTrue(maxConcurrent.get() <= configuredParallelism,
            "Max concurrent downloads ($maxConcurrent) should not exceed configured parallelism ($configuredParallelism)")
    }
}
