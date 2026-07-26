package dev.rnett.sources.mcp

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.ossreviewtoolkit.model.AnalyzerResult
import org.ossreviewtoolkit.model.DependencyGraph
import org.ossreviewtoolkit.model.DependencyGraphNode
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.PackageLinkage
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.RootDependencyIndex
import org.ossreviewtoolkit.model.VcsInfo
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class SourcesViewServiceTest {

    @TempDir
    lateinit var tempDir: Path

    @TempDir
    lateinit var testHomeDir: Path

    private lateinit var env: SourcesMcpEnvironment
    private lateinit var analyzer: DependencyAnalyzer
    private lateinit var downloader: DependencySourcesDownloader
    private lateinit var service: SourcesViewService

    @BeforeEach
    fun setup() {
        env = SourcesMcpEnvironment(testHomeDir)
        analyzer = mockk()
        downloader = mockk()
        service = SourcesViewService(
            dependencyAnalyzer = analyzer,
            downloader = downloader,
            env = env,
            normalizationService = NoOpNormalizationService,
        )
    }

    private fun makePkgId(name: String, namespace: String = "com.example", version: String = "1.0.0") =
        Identifier("Maven", namespace, name, version)

    private fun makePkg(id: Identifier) = Package(
        id = id,
        declaredLicenses = emptySet(),
        description = "Test package",
        homepageUrl = "https://example.com",
        binaryArtifact = RemoteArtifact.EMPTY,
        sourceArtifact = RemoteArtifact.EMPTY,
        vcs = VcsInfo.EMPTY,
    )

    private fun makeAnalyzerResult(vararg pkgScopePairs: Pair<Package, Set<String>>): AnalyzerResult {
        val pkgs = pkgScopePairs.map { it.first }
        val pkgIds = pkgs.map { it.id }
        val nodes = pkgIds.mapIndexed { i, _ -> DependencyGraphNode(pkg = i, fragment = 0) }
        val dummyProjectId = Identifier("Maven", "com", "root", "1.0")
        val scopeMap = mutableMapOf<String, MutableList<RootDependencyIndex>>()
        pkgScopePairs.forEachIndexed { i, (_, scopes) ->
            scopes.forEach { scope ->
                val qualified = DependencyGraph.qualifyScope(dummyProjectId, scope)
                scopeMap.getOrPut(qualified) { mutableListOf() } += RootDependencyIndex(root = i, fragment = 0)
            }
        }
        val graph = DependencyGraph(packages = pkgIds, scopes = scopeMap, nodes = nodes, edges = emptySet())
        return AnalyzerResult(
            projects = emptySet(),
            packages = pkgs.toSet(),
            dependencyGraphs = mapOf("Maven" to graph),
        )
    }

    // --- Shared test helper (Step 16) ---
    private fun setupSyncScenario(
        pkgName: String,
        vararg scopes: String = arrayOf("compile"),
        namespace: String = "com.example",
        version: String = "1.0.0",
    ): Pair<Package, String> {
        val pkgId = makePkgId(pkgName, namespace, version)
        val pkg = makePkg(pkgId)
        val result = makeAnalyzerResult(pkg to scopes.toSet())
        every { analyzer.analyzeDependencies(tempDir, force = any()) } returns result
        every { analyzer.computeDepHash(tempDir) } returns "hash-${pkgName}"

        val casKey = "hash-${pkgName}"
        val casDir = env.casDir.resolve(casKey).also { it.createDirectories() }
        casDir.resolve("File.kt").writeText(pkgName)
        every { downloader.downloadSources(pkg, forceRedownload = any()) } returns casDir
        return pkg to casKey
    }

    @Test
    fun testUuidKeyedViewDirectoryCreation() = runTest {
        setupSyncScenario("test-lib")
        val view = service.sync(tempDir)

        assertNotNull(view)
        assertTrue(view.sessionId.isNotEmpty())
        assertTrue(view.baseDir.startsWith(env.viewsDir))
        assertTrue(view.baseDir.exists())
        assertTrue(view.sourcesDir.exists())
        assertTrue(view.baseDir.resolve("manifest.json").exists())
        assertEquals(view.sessionId, view.manifest.sessionId)
        assertEquals(1, view.manifest.dependencies.size)
        assertEquals("Maven:com.example:test-lib:1.0.0", view.manifest.dependencies[0].id)
    }

    @Test
    fun testInMemoryViewCaching() = runTest {
        setupSyncScenario("cached-lib")
        val view1 = service.sync(tempDir)
        val view2 = service.sync(tempDir)

        assertEquals(view1.sessionId, view2.sessionId)
        assertEquals(view1.baseDir, view2.baseDir)
        verify(exactly = 1) { analyzer.analyzeDependencies(tempDir, force = any()) }
    }

    @Test
    fun testSymlinkOrJunctionCreation() = runTest {
        setupSyncScenario("linked-lib")
        val view = service.sync(tempDir)

        val linkPath = view.sourcesDir.resolve("Maven/com.example/linked-lib/1.0.0")
        assertTrue(linkPath.exists(), "Link directory should exist at $linkPath")
        assertTrue(linkPath.resolve("File.kt").exists(), "Linked source file should be accessible")
        assertEquals("linked-lib", linkPath.resolve("File.kt").readText())
    }

    @Test
    fun testFreshModeEvictsCacheAndReanalyzes() = runTest {
        setupSyncScenario("fresh-lib")
        val view1 = service.sync(tempDir)

        // Compute pre-change hash
        val hashBefore = view1.manifest.depHash
        tempDir.resolve("build.gradle.kts").writeText("""plugins { id("java") }""")
        every { analyzer.computeDepHash(tempDir) } returns "hash-after-change"
        val hashAfter = service.check(tempDir).currentDepHash

        val view2 = service.sync(tempDir, mode = SyncMode.REFRESH_ANALYSIS)

        verify(exactly = 2) { analyzer.analyzeDependencies(tempDir, force = any()) }
        verify(exactly = 2) { downloader.downloadSources(any(), forceRedownload = any()) }
        assertTrue(view2.manifest.depHash != view1.manifest.depHash, "fresh sync should produce different depHash when dependency files change")
        assertEquals(view2.manifest.depHash, hashAfter, "New view's depHash should match current file state")
    }

    @Test
    fun testRedownloadModeReDownloads() = runTest {
        setupSyncScenario("redownload-lib")
        service.sync(tempDir)
        service.sync(tempDir, mode = SyncMode.FULL_REFRESH)

        verify(exactly = 2) { analyzer.analyzeDependencies(tempDir, force = any()) }
        verify(exactly = 2) { downloader.downloadSources(any(), forceRedownload = any()) }
    }

    @Test
    fun testCheckFreshView() = runTest {
        setupSyncScenario("check-fresh-lib")
        service.sync(tempDir)
        val staleness = service.check(tempDir)

        assertTrue(staleness.viewExists)
        assertTrue(staleness.fresh)
        assertEquals(1, staleness.dependencyCount)
    }

    @Test
    fun testCheckStaleView() = runTest {
        setupSyncScenario("check-stale-lib")
        service.sync(tempDir)

        tempDir.resolve("build.gradle.kts").writeText("""plugins { id("java") }""")
        every { analyzer.computeDepHash(tempDir) } returns "hash-different"

        val staleness = service.check(tempDir)

        assertTrue(staleness.viewExists)
        assertFalse(staleness.fresh)
        assertEquals(1, staleness.dependencyCount)
    }

    @Test
    fun testCheckNoView() = runTest {
        every { analyzer.computeDepHash(tempDir) } returns "hash-no-view"
        val staleness = service.check(tempDir)

        assertFalse(staleness.viewExists)
        assertFalse(staleness.fresh)
    }

    @Test
    fun testCheckViewOnDiskButNotInMemoryCache() = runTest {
        setupSyncScenario("disk-only-lib")
        service.sync(tempDir)
        service.evictView(tempDir)

        val staleness = service.check(tempDir)

        assertTrue(staleness.viewExists, "View should be found on disk despite in-memory eviction")
        assertTrue(staleness.fresh, "View should be fresh since depHash matches")
    }

    @Test
    fun testSyncWithSpecificScopes() = runTest {
        val compilePkg = makePkg(makePkgId("compile-lib"))
        val testPkg = makePkg(makePkgId("test-lib"))
        val result = makeAnalyzerResult(
            compilePkg to setOf("compile"),
            testPkg to setOf("test"),
        )
        every { analyzer.analyzeDependencies(tempDir, force = any()) } returns result
        every { analyzer.computeDepHash(tempDir) } returns "hash-scope"
        val casDir = env.casDir.resolve("scope-hash").also { it.createDirectories() }
        casDir.resolve("File.kt").writeText("scope")
        every { downloader.downloadSources(any()) } returns casDir

        val view = service.sync(tempDir, scopes = setOf("compile"))

        assertEquals(1, view.manifest.dependencies.size)
        assertEquals(compilePkg.id.toCoordinates(), view.manifest.dependencies[0].id)
    }

    @Test
    fun testSyncWithEmptyScopesReturnsAllDependencies() = runTest {
        val compilePkg = makePkg(makePkgId("all-compile-lib"))
        val testPkg = makePkg(makePkgId("all-test-lib"))
        val result = makeAnalyzerResult(
            compilePkg to setOf("compile"),
            testPkg to setOf("test"),
        )
        every { analyzer.analyzeDependencies(tempDir, force = any()) } returns result
        every { analyzer.computeDepHash(tempDir) } returns "hash-all"
        val casDir = env.casDir.resolve("all-scopes-hash").also { it.createDirectories() }
        casDir.resolve("File.kt").writeText("all")
        every { downloader.downloadSources(any()) } returns casDir

        val view = service.sync(tempDir, scopes = emptySet())

        assertEquals(2, view.manifest.dependencies.size)
        val depIds = view.manifest.dependencies.map { it.id }.toSet()
        assertTrue(compilePkg.id.toCoordinates() in depIds)
        assertTrue(testPkg.id.toCoordinates() in depIds)
    }

    @Test
    fun testSyncWithUnknownScopeNamesReturnsEmpty() = runTest {
        val pkg = makePkg(makePkgId("known-lib"))
        val result = makeAnalyzerResult(pkg to setOf("compile"))
        every { analyzer.analyzeDependencies(tempDir, force = any()) } returns result
        every { analyzer.computeDepHash(tempDir) } returns "hash-unknown"
        val casDir = env.casDir.resolve("unknown-scope-hash").also { it.createDirectories() }
        casDir.resolve("File.kt").writeText("unknown")
        every { downloader.downloadSources(any()) } returns casDir

        val view = service.sync(tempDir, scopes = setOf("nonexistent"))

        assertEquals(0, view.manifest.dependencies.size)
    }

    @Test
    fun testSyncWithEmptyAnalyzerResult() = runTest {
        val result = AnalyzerResult.EMPTY
        every { analyzer.analyzeDependencies(tempDir, force = any()) } returns result
        every { analyzer.computeDepHash(tempDir) } returns "hash-empty-result"

        val view = service.sync(tempDir)

        assertEquals(0, view.manifest.dependencies.size)
    }

    @Test
    fun testDependencyInMultipleScopesIncludedOnce() = runTest {
        setupSyncScenario("multi-scope-lib", scopes = *arrayOf("compile", "runtime"))

        val view = service.sync(tempDir, scopes = setOf("compile", "runtime"))

        assertEquals(1, view.manifest.dependencies.size)
    }

    @Test
    fun testMissingCasEntrySkipsDependency() = runTest {
        val okPkg = makePkg(makePkgId("ok-lib"))
        val failPkg = makePkg(makePkgId("fail-lib"))
        val result = makeAnalyzerResult(
            okPkg to setOf("compile"),
            failPkg to setOf("compile"),
        )
        every { analyzer.analyzeDependencies(tempDir, force = any()) } returns result
        every { analyzer.computeDepHash(tempDir) } returns "hash-skip"

        val casDir = env.casDir.resolve("skip-hash").also { it.createDirectories() }
        casDir.resolve("File.kt").writeText("ok")
        every { downloader.downloadSources(okPkg) } returns casDir
        every { downloader.downloadSources(failPkg) } throws RuntimeException("CAS entry missing")

        val view = service.sync(tempDir)

        assertEquals(1, view.manifest.dependencies.size)
        assertEquals(okPkg.id.toCoordinates(), view.manifest.dependencies[0].id)
    }

    @Test
    fun testRejectsUnsafeCharactersInDependencyName() = runTest {
        val pkgId = makePkgId("test&cmd")
        val pkg = makePkg(pkgId)
        val result = makeAnalyzerResult(pkg to setOf("compile"))
        every { analyzer.analyzeDependencies(tempDir, force = any()) } returns result
        every { analyzer.computeDepHash(tempDir) } returns "hash-unsafe"
        val casDir = env.casDir.resolve("unsafe-hash").also { it.createDirectories() }
        casDir.resolve("File.kt").writeText("unsafe")
        every { downloader.downloadSources(pkg, forceRedownload = any()) } returns casDir

        val exception = assertFailsWith<IllegalArgumentException> {
            service.sync(tempDir)
        }
        assertTrue(exception.message?.contains("unsafe characters") == true)
    }

    @Test
    fun testRejectsPathTraversalInDependencyName() = runTest {
        val pkgId = Identifier("..", "escape", "test", "1.0")
        val pkg = makePkg(pkgId)
        val result = makeAnalyzerResult(pkg to setOf("compile"))
        every { analyzer.analyzeDependencies(tempDir, force = any()) } returns result
        every { analyzer.computeDepHash(tempDir) } returns "hash-traversal"
        val casDir = env.casDir.resolve("traversal-hash").also { it.createDirectories() }
        casDir.resolve("File.kt").writeText("traversal")
        every { downloader.downloadSources(pkg, forceRedownload = any()) } returns casDir

        val exception = assertFailsWith<IllegalArgumentException> {
            service.sync(tempDir)
        }
        assertTrue(exception.message?.contains("escapes sources directory") == true)
    }

    @Test
    fun testRejectsPathSeparatorInDependencyName() = runTest {
        val pkgId = makePkgId("sub/dir")
        val pkg = makePkg(pkgId)
        val result = makeAnalyzerResult(pkg to setOf("compile"))
        every { analyzer.analyzeDependencies(tempDir, force = any()) } returns result
        every { analyzer.computeDepHash(tempDir) } returns "hash-separator"
        val casDir = env.casDir.resolve("separator-hash").also { it.createDirectories() }
        casDir.resolve("File.kt").writeText("separator")
        every { downloader.downloadSources(pkg, forceRedownload = any()) } returns casDir

        val exception = assertFailsWith<IllegalArgumentException> {
            service.sync(tempDir)
        }
        assertTrue(exception.message?.contains("path separator") == true)
    }

    @Test
    fun testCheckNonExistentProjectRoot() = runTest {
        every { analyzer.computeDepHash(Path.of("/nonexistent/root/path")) } returns "hash-nonexistent"
        val staleness = service.check(Path.of("/nonexistent/root/path"))
        assertFalse(staleness.viewExists, "viewExists should be false")
        assertFalse(staleness.fresh, "fresh should be false")
    }

    @Test
    fun testGetViewReturnsNullAfterDirectoryDeleted() = runTest {
        setupSyncScenario("deleted-dir-lib")
        service.sync(tempDir)

        // Delete the view directory on disk
        val viewDir = service.getView(tempDir)!!.baseDir
        viewDir.deleteRecursively()

        // getView should return null after directory is deleted
        val result2 = service.getView(tempDir)
        assertEquals(null, result2)
    }

    @Test
    fun testSyncCleansUpOnAnalysisFailure() = runTest {
        setupSyncScenario("cleanup-fail-lib")
        service.sync(tempDir)

        // Now force refresh so we get past the early return, then throw
        val exception = RuntimeException("Simulated ORT analysis failure")
        every { analyzer.analyzeDependencies(tempDir, force = true) } throws exception

        assertFailsWith<RuntimeException> {
            service.sync(tempDir, mode = SyncMode.REFRESH_ANALYSIS)
        }

        // Verify no orphaned directories remain beyond the original successful view
        val viewsDir = env.viewsDir
        if (viewsDir.exists() && viewsDir.isDirectory()) {
            val remainingDirs = Files.list(viewsDir).use { stream ->
                stream.filter { it.isDirectory() && it.fileName.toString() != "lost+found" }.toList()
            }
            assertEquals(1, remainingDirs.size, "Only the successful sync's view dir should remain")
        }
    }

    // --- getView() tests ---

    @Test
    fun testGetViewReturnsNullWhenNoViewSynced() {
        val view = service.getView(tempDir)
        assertEquals(null, view)
    }

    @Test
    fun testGetViewReturnsSessionViewAfterSync() = runTest {
        setupSyncScenario("getview-lib")
        val synced = service.sync(tempDir)
        val view = service.getView(tempDir)

        assertNotNull(view)
        assertEquals(synced.sessionId, view.sessionId)
        assertTrue(view.sourcesDir.exists())
    }

    @Test
    fun testGetViewWithDifferentScopesReturnsNull() = runTest {
        setupSyncScenario("scope-mismatch-lib")
        service.sync(tempDir) // empty scopes = all
        val view = service.getView(tempDir, setOf("compile"))

        assertEquals(null, view)
    }

    @Test
    fun testGetViewReturnsNullAfterEvictView() = runTest {
        setupSyncScenario("evictview-lib")
        service.sync(tempDir)
        service.evictView(tempDir)
        val view = service.getView(tempDir)

        assertEquals(null, view)
    }

    // --- Spec coverage tests (Step 12 / Finding 14) ---

    @Test
    fun testModeFullRefresh() = runTest {
        setupSyncScenario("full-refresh-lib")
        service.sync(tempDir, mode = SyncMode.FULL_REFRESH)

        verify(exactly = 1) { analyzer.analyzeDependencies(tempDir, force = true) }
        verify(exactly = 1) { downloader.downloadSources(any(), forceRedownload = true) }
    }

    @Test
    fun testModeRefreshAnalysisWhenCacheMissing() = runTest {
        setupSyncScenario("cache-miss-lib")
        val view = service.sync(tempDir, mode = SyncMode.REFRESH_ANALYSIS)

        assertNotNull(view)
        verify(exactly = 1) { analyzer.analyzeDependencies(tempDir, force = true) }
    }

    @Test
    fun testCheckReturnsNoViewWhenNoPriorSync() = runTest {
        every { analyzer.computeDepHash(tempDir) } returns "hash-no-cache"
        val staleness = service.check(tempDir)

        assertFalse(staleness.viewExists)
        assertTrue(staleness.currentDepHash.isNotEmpty())
    }

    @Test
    fun testCheckReturnsStaleWhenViewExistsButAnalysisCacheMissing() = runTest {
        setupSyncScenario("cache-missing-lib")
        val synced = service.sync(tempDir)
        service.evictView(tempDir)

        // Simulate analysis cache being deleted by returning a different hash
        every { analyzer.computeDepHash(tempDir) } returns "hash-different-from-stored"

        val staleness = service.check(tempDir)

        assertTrue(staleness.viewExists, "View should be found on disk")
        assertFalse(staleness.fresh, "View should be stale since depHash does not match")
        assertEquals(synced.manifest.depHash, staleness.depHash)
        assertEquals("hash-different-from-stored", staleness.currentDepHash)
    }

    @Test
    fun testCheckDoesNotDownloadOrAnalyze() = runTest {
        // Sync first to create a view
        setupSyncScenario("no-download-lib")
        service.sync(tempDir)

        // Now check — should not trigger analysis or download
        val staleness = service.check(tempDir)

        assertTrue(staleness.viewExists)
        // Verify only the initial sync called analyzeDependencies, not check()
        verify(exactly = 1) { analyzer.analyzeDependencies(any()) }
        verify(exactly = 1) { downloader.downloadSources(any()) }
    }

    // --- Manifest deserialization safety tests ---

    @Test
    fun testCheckRejectsUncPathInManifest() = runTest {
        every { analyzer.computeDepHash(tempDir) } returns "hash-unc"

        val viewsDir = env.viewsDir
        viewsDir.createDirectories()
        val viewDir = viewsDir.resolve("unc-test-view")
        viewDir.createDirectories()

        val manifest = ViewManifest(
            sessionId = "unc-session",
            timestamp = Instant.now().toString(),
            projectRoot = "\\\\server\\share\\project",
            scopes = emptySet(),
            depHash = "hash-unc",
            dependencies = emptyList()
        )
        val manifestJson = Json { prettyPrint = true }.encodeToString(ViewManifest.serializer(), manifest)
        viewDir.resolve("manifest.json").writeText(manifestJson)

        val staleness = service.check(tempDir)

        assertFalse(staleness.viewExists)
    }

    @Test
    fun testCheckRejectsOverlongPathInManifest() = runTest {
        every { analyzer.computeDepHash(tempDir) } returns "hash-overlong"

        val viewsDir = env.viewsDir
        viewsDir.createDirectories()
        val viewDir = viewsDir.resolve("overlong-test-view")
        viewDir.createDirectories()

        val longPath = "C:\\" + "a".repeat(5000) + "\\project"
        val manifest = ViewManifest(
            sessionId = "overlong-session",
            timestamp = Instant.now().toString(),
            projectRoot = longPath,
            scopes = emptySet(),
            depHash = "hash-overlong",
            dependencies = emptyList()
        )
        val manifestJson = Json { prettyPrint = true }.encodeToString(ViewManifest.serializer(), manifest)
        viewDir.resolve("manifest.json").writeText(manifestJson)

        val staleness = service.check(tempDir)

        assertFalse(staleness.viewExists)
    }

    @Test
    fun testCheckRejectsRelativePathInManifest() = runTest {
        every { analyzer.computeDepHash(tempDir) } returns "hash-relative"

        val viewsDir = env.viewsDir
        viewsDir.createDirectories()
        val viewDir = viewsDir.resolve("relative-test-view")
        viewDir.createDirectories()

        val manifest = ViewManifest(
            sessionId = "relative-session",
            timestamp = Instant.now().toString(),
            projectRoot = "..\\..\\etc",
            scopes = emptySet(),
            depHash = "hash-relative",
            dependencies = emptyList()
        )
        val manifestJson = Json { prettyPrint = true }.encodeToString(ViewManifest.serializer(), manifest)
        viewDir.resolve("manifest.json").writeText(manifestJson)

        val staleness = service.check(tempDir)

        assertFalse(staleness.viewExists)
    }
}
