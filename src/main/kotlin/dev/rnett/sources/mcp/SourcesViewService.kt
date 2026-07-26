package dev.rnett.sources.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.ossreviewtoolkit.model.AnalyzerResult
import org.ossreviewtoolkit.model.Identifier
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.Scope
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

enum class SyncMode {
    /** Use cached analysis and cached downloads. */
    CACHED,
    /** Re-run ORT dependency analysis; reuse cached downloads. */
    REFRESH_ANALYSIS,
    /** Use cached analysis; re-download all sources. */
    REDOWNLOAD_SOURCES,
    /** Re-run analysis AND re-download all sources. */
    FULL_REFRESH,
}

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class SourcesViewService(
    private val dependencyAnalyzer: DependencyAnalyzer = DependencyAnalyzer(),
    private val downloader: DependencySourcesDownloader = DependencySourcesDownloader(),
    private val env: SourcesMcpEnvironment = SourcesMcpEnvironment.fromEnv(),
    private val normalizationService: NormalizationService = NoOpNormalizationService,
) {
    private val logger = LoggerFactory.getLogger(SourcesViewService::class.java)
    private val json = Json { prettyPrint = true }

    private val viewCache = ConcurrentHashMap<ViewCacheKey, SessionView>()
    private val syncMutexes = ConcurrentHashMap<ViewCacheKey, Mutex>()

    private val parallelism: Int = System.getenv("SOURCES_VIEW_PARALLELISM")?.toIntOrNull()
        ?: Runtime.getRuntime().availableProcessors()

    companion object {
        private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("win")
        private const val WINDOWS_MAX_PATH = 260
        private const val JUNCTION_PATH_WARN_THRESHOLD = 200
        private const val MAX_MANIFEST_SIZE = 10L * 1024 * 1024 // 10MB
        private const val MAX_DEPENDENCIES = 50_000
    }

    /**
     * Analyzes project dependencies, downloads all source artifacts, and creates a view directory
     * containing symlinks (junctions on Windows) to every dependency's source tree.
     *
     * @param projectRoot absolute path to the project root directory (any ecosystem supported by ORT).
     * @param scopes filter dependencies by ORT scope name (OR logic). Empty set includes all dependencies.
     * @param mode controls caching behavior. Use [SyncMode.CACHED] for full caching,
     *   [SyncMode.REFRESH_ANALYSIS] to force ORT re-analysis (reuses cached downloads),
     *   [SyncMode.REDOWNLOAD_SOURCES] to force re-download, or [SyncMode.FULL_REFRESH] for both.
     * @return [SessionView] containing the `viewPath` directory for source exploration, plus manifest metadata.
     */
    suspend fun sync(
        projectRoot: Path,
        scopes: Set<String> = emptySet(),
        mode: SyncMode = SyncMode.CACHED,
    ): SessionView {
        val cacheKey = ViewCacheKey.create(projectRoot, scopes)

        // Evict from in-memory cache only when analysis is being refreshed.
        // REDOWNLOAD_SOURCES re-downloads but reuses cached analysis — no need to evict.
        if (mode == SyncMode.REFRESH_ANALYSIS || mode == SyncMode.FULL_REFRESH) {
            viewCache.remove(cacheKey)
        }

        getValidCachedView(cacheKey)?.let { return it }

        val mutex = syncMutexes.computeIfAbsent(cacheKey) { Mutex() }
        return try {
            mutex.withLock {
                getValidCachedView(cacheKey)?.let { return@withLock it }

                val result = dependencyAnalyzer.analyzeDependencies(
                    projectRoot,
                    force = mode == SyncMode.REFRESH_ANALYSIS || mode == SyncMode.FULL_REFRESH
                )

                val filteredPackages = filterPackagesByScopes(result, scopes)

                val sessionId = UUID.randomUUID().toString()
                val baseDir = env.viewsDir.resolve(sessionId)
                val sourcesDir = baseDir.resolve("sources")

                val depHash = dependencyAnalyzer.computeDepHash(projectRoot)

                try {
                    baseDir.createDirectories()
                    sourcesDir.createDirectories()

                    // Pre-validate all dependency identifiers before processing
                    validateDependencyIdentifiers(filteredPackages, sourcesDir)

                    val (dependencies, failedIds) = downloadAndLinkDependencies(
                        filteredPackages, sourcesDir,
                        redownload = mode == SyncMode.REDOWNLOAD_SOURCES || mode == SyncMode.FULL_REFRESH
                    )

                    val normalizedSourcesDir = normalizationService.normalize(sourcesDir)

                    val manifest = ViewManifest(
                        sessionId = sessionId,
                        timestamp = Instant.now().toString(),
                        projectRoot = projectRoot.toAbsolutePath().normalize().toString(),
                        scopes = scopes,
                        depHash = depHash,
                        dependencies = dependencies,
                        failedDependencies = failedIds
                    )

                    baseDir.resolve("manifest.json").writeText(json.encodeToString(ViewManifest.serializer(), manifest))

                    val sessionView = SessionView(
                        sessionId = sessionId,
                        baseDir = baseDir,
                        sourcesDir = normalizedSourcesDir,
                        manifest = manifest
                    )

                    viewCache[cacheKey] = sessionView
                    sessionView
                } catch (e: Exception) {
                    if (baseDir.exists()) {
                        baseDir.deleteRecursively()
                    }
                    throw e
                }
            }
        } finally {
            syncMutexes.remove(cacheKey)
        }
    }

    private fun filterPackagesByScopes(
        result: AnalyzerResult,
        scopes: Set<String>,
    ): List<Package> {
        val allDepsWithScopes = extractDependenciesWithScopes(result)
        return if (scopes.isEmpty()) {
            allDepsWithScopes.map { it.first }
        } else {
            allDepsWithScopes.filter { (_, depScopes) ->
                depScopes.any { depScope ->
                    scopes.any { userScope ->
                        depScope == userScope || depScope.endsWith(":$userScope")
                    }
                }
            }.map { it.first }
        }
    }

    private fun validateDependencyIdentifiers(packages: List<Package>, sourcesDir: Path) {
        val allowlist = Regex("^[a-zA-Z0-9._\\[\\]-]*$")

        for (pkg in packages) {
            val components = listOf(
                "ecosystem" to pkg.id.type,
                "namespace" to pkg.id.namespace,
                "name" to pkg.id.name,
                "version" to pkg.id.version,
            )
            for ((fieldName, value) in components) {
                require(!value.contains("/") && !value.contains("\\")) {
                    "Dependency $fieldName contains path separator: '$value'"
                }
                require(allowlist.matches(value)) {
                    "Dependency $fieldName contains unsafe characters: '$value'"
                }
            }

            val testPath = sourcesDir.resolve("${pkg.id.type}/${pkg.id.namespace}/${pkg.id.name}/${pkg.id.version}").normalize()
            require(testPath.startsWith(sourcesDir)) {
                "Dependency path escapes sources directory"
            }
        }
    }

    private suspend fun downloadAndLinkDependencies(
        packages: List<Package>,
        sourcesDir: Path,
        redownload: Boolean
    ): Pair<List<ViewDependency>, List<String>> = coroutineScope {
        val results = packages.chunked(parallelism.coerceAtLeast(1)).flatMap { chunk ->
            chunk.map { pkg ->
                async {
                    try {
                        val casPath = withContext(Dispatchers.IO) {
                            downloader.downloadSources(pkg, forceRedownload = redownload)
                        }
                        val casKey = casPath.fileName.toString()

                        val ecosystem = pkg.id.type
                        val namespace = pkg.id.namespace
                        val name = pkg.id.name
                        val version = pkg.id.version

                        val linkDir = sourcesDir.resolve("$ecosystem/$namespace/$name/$version").normalize()
                        linkDir.parent.createDirectories()

                        if (!linkDir.exists()) {
                            createLink(linkDir, casPath)
                        }

                        ViewDependency(
                            id = pkg.id.toCoordinates(),
                            casKey = casKey,
                            ecosystem = ecosystem,
                            namespace = namespace,
                            name = name,
                            version = version
                        ) to null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Failed to process dependency ${pkg.id.toCoordinates()}: ${e.message}")
                        null to pkg.id.toCoordinates()
                    }
                }
            }.awaitAll()
        }
        val successes = results.mapNotNull { it.first }
        val failures = results.mapNotNull { it.second }
        successes to failures
    }

    /**
     * Performs a lightweight staleness check by comparing the hash of the project's dependency
     * declaration files against the stored hash from the most recently synced view.
     *
     * @param projectRoot absolute path to the project root directory.
     * @param scopes filter dependencies by ORT scope name. Empty set matches any view.
     * @return [StalenessCheck] with `fresh: true` when hashes match (view is current),
     *   `fresh: false` or `viewExists: false` when `sync()` is needed, plus diagnostic fields.
     */
    suspend fun check(projectRoot: Path, scopes: Set<String> = emptySet()): StalenessCheck {
        val currentDepHash = runCatching { dependencyAnalyzer.computeDepHash(projectRoot) }.getOrNull()
            ?: return StalenessCheck(viewExists = false, fresh = false, age = null, depHash = null, currentDepHash = "", dependencyCount = null)

        val normalizedRoot = runCatching { projectRoot.toAbsolutePath().normalize().toRealPath() }
            .getOrDefault(projectRoot.toAbsolutePath().normalize())

        // When scopes are specified, do a direct O(1) cache-key lookup first
        if (scopes.isNotEmpty()) {
            val cacheKey = ViewCacheKey.create(projectRoot, scopes)
            val view = viewCache[cacheKey]
            if (view != null) {
                val manifest = view.manifest
                val age = computeAge(manifest.timestamp)
                return StalenessCheck(
                    viewExists = true,
                    fresh = manifest.depHash == currentDepHash,
                    age = age,
                    depHash = manifest.depHash,
                    currentDepHash = currentDepHash,
                    dependencyCount = manifest.dependencies.size,
                    failedDependencies = manifest.failedDependencies
                )
            }
        }

        val cachedEntry = viewCache.entries.firstOrNull { it.key.normalizedProjectRoot == normalizedRoot }
        if (cachedEntry != null) {
            val manifest = cachedEntry.value.manifest
            val age = computeAge(manifest.timestamp)
            return StalenessCheck(
                viewExists = true,
                fresh = manifest.depHash == currentDepHash,
                age = age,
                depHash = manifest.depHash,
                currentDepHash = currentDepHash,
                dependencyCount = manifest.dependencies.size,
                failedDependencies = manifest.failedDependencies
            )
        }

        val diskResult = findViewOnDisk(projectRoot, currentDepHash, scopes)
        if (diskResult != null) return diskResult

        return StalenessCheck(
            viewExists = false,
            fresh = false,
            age = null,
            depHash = null,
            currentDepHash = currentDepHash,
            dependencyCount = null
        )
    }

    private fun computeAge(timestamp: String): Duration? =
        runCatching { Duration.between(Instant.parse(timestamp), Instant.now()) }.getOrNull()

    private fun findViewOnDisk(projectRoot: Path, currentDepHash: String, scopes: Set<String> = emptySet()): StalenessCheck? {
        val viewsDir = env.viewsDir
        if (!viewsDir.exists() || !viewsDir.isDirectory()) {
            return StalenessCheck(
                viewExists = false,
                fresh = false,
                age = null,
                depHash = null,
                currentDepHash = currentDepHash,
                dependencyCount = null
            )
        }

        val normalizedRoot = runCatching { projectRoot.toAbsolutePath().normalize().toRealPath().toString() }
            .getOrNull() ?: return StalenessCheck(viewExists = false, fresh = false, age = null, depHash = null, currentDepHash = currentDepHash, dependencyCount = null)

        return findViewByScanning(viewsDir, normalizedRoot, currentDepHash, scopes)
    }

    private fun findViewByScanning(
        viewsDir: Path,
        normalizedRoot: String,
        currentDepHash: String,
        scopes: Set<String> = emptySet(),
    ): StalenessCheck? {
        val viewDirs = Files.list(viewsDir).use { stream ->
            stream.filter { it.isDirectory() }.toList()
        }

        val candidates = mutableListOf<Pair<String, ViewManifest>>()

        for (dir in viewDirs) {
            val manifestFile = dir.resolve("manifest.json")
            if (!manifestFile.exists()) continue

            if (manifestFile.toFile().length() > MAX_MANIFEST_SIZE) {
                logger.warn("Skipping oversized manifest: $manifestFile")
                continue
            }

            val manifest = runCatching {
                json.decodeFromString(ViewManifest.serializer(), manifestFile.readText())
            }.getOrNull() ?: continue

            if (manifest.dependencies.size > MAX_DEPENDENCIES) {
                logger.warn("Skipping manifest with too many dependencies: ${manifest.dependencies.size}")
                continue
            }

            // Safety validation: reject UNC paths, overlength strings, non-local paths
            val projectRootStr = manifest.projectRoot
            val safeToResolve = projectRootStr.length <= 4096 &&
                !projectRootStr.startsWith("\\\\") &&
                (isWindows && projectRootStr.matches(Regex("^[A-Za-z]:\\\\.*")) ||
                 !isWindows && projectRootStr.startsWith("/"))

            if (!safeToResolve) continue

            val resolved = runCatching {
                Path.of(projectRootStr).toAbsolutePath().normalize().toRealPath().toString()
            }.getOrNull() ?: continue

            if (resolved != normalizedRoot) continue

            // Scopes filter: match if manifest scopes == requested scopes
            if (scopes.isEmpty() || manifest.scopes == scopes) {
                candidates.add(manifest.timestamp to manifest)
            }
        }

        // Sort by timestamp descending for determinism; pick the newest
        val best = candidates.maxByOrNull { it.first } ?: return null
        val manifest = best.second
        val age = computeAge(manifest.timestamp)
        return StalenessCheck(
            viewExists = true,
            fresh = manifest.depHash == currentDepHash,
            age = age,
            depHash = manifest.depHash,
            currentDepHash = currentDepHash,
            dependencyCount = manifest.dependencies.size,
            failedDependencies = manifest.failedDependencies,
        )
    }

    /**
     * Returns the cached [SessionView] for the given project root and scopes, or `null` if no
     * view has been synced for this combination.
     *
     * @param projectRoot absolute path to the project root directory.
     * @param scopes scope filter used during the sync call.
     * @return the cached [SessionView], or `null` if not found.
     */
    fun getView(projectRoot: Path, scopes: Set<String> = emptySet()): SessionView? {
        val cacheKey = ViewCacheKey.create(projectRoot, scopes)
        return getValidCachedView(cacheKey)
    }

    /**
     * Removes the cached [SessionView] for the given project root and scopes from the in-memory cache only.
     * Does not delete the view directory or manifest from disk.
     *
     * @param projectRoot absolute path to the project root directory.
     * @param scopes scope filter used during the sync call.
     */
    fun evictView(projectRoot: Path, scopes: Set<String> = emptySet()) {
        val cacheKey = ViewCacheKey.create(projectRoot, scopes)
        viewCache.remove(cacheKey)
    }

    private suspend fun createLink(linkPath: Path, targetPath: Path) {
        if (isWindows) {
            createJunction(linkPath, targetPath)
        } else {
            withContext(Dispatchers.IO) {
                Files.createSymbolicLink(linkPath, targetPath)
            }
        }
    }

    private suspend fun createJunction(linkPath: Path, targetPath: Path) {
        if (linkPath.exists()) return

        val linkStr = linkPath.toAbsolutePath().normalize().toString()
        val targetStr = targetPath.toAbsolutePath().normalize().toString()

        if (linkStr.length > JUNCTION_PATH_WARN_THRESHOLD) {
            logger.warn("Junction path may approach Windows MAX_PATH ($WINDOWS_MAX_PATH chars) limit (${linkStr.length} chars): $linkStr")
        }

        withContext(Dispatchers.IO) {
            val process = ProcessBuilder("cmd", "/d", "/c", "mklink", "/J", linkStr, targetStr)
                .redirectErrorStream(true)
                .start()

            val exited = process.waitFor(30, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                throw RuntimeException("mklink /J timed out for $linkStr -> $targetStr")
            }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                val error = process.inputStream.bufferedReader().readText()
                if (!linkPath.exists()) {
                    throw RuntimeException("Failed to create junction $linkStr -> $targetStr: $error")
                } else {
                    logger.warn("mklink /J reported non-zero exit code ($exitCode) but junction exists at $linkStr. May point to wrong target. Error: $error")
                }
            }
        }
    }

    private fun collectScopeDeps(
        scopes: Collection<Scope>,
        depToScopes: MutableMap<Identifier, MutableSet<String>>
    ) {
        for (scope in scopes) {
            for (depId in scope.collectDependencies()) {
                depToScopes.getOrPut(depId) { mutableSetOf() } += scope.name
            }
        }
    }

    private fun extractDependenciesWithScopes(result: AnalyzerResult): List<Pair<Package, Set<String>>> {
        val depToScopes = mutableMapOf<Identifier, MutableSet<String>>()
        val packagesById = result.packages.associateBy { it.id }

        collectScopeDeps(result.dependencyGraphs.values.flatMap { it.createScopes() }, depToScopes)
        collectScopeDeps(result.projects.flatMap { it.scopes }, depToScopes)

        return depToScopes.mapNotNull { (id, scopes) ->
            packagesById[id]?.let { it to scopes }
        }
    }

    private fun SessionView.isValid(): Boolean = baseDir.exists() && sourcesDir.exists()

    private fun getValidCachedView(key: ViewCacheKey): SessionView? {
        val view = viewCache[key] ?: return null
        if (view.isValid()) return view
        viewCache.remove(key)
        logger.warn("Evicted stale cache entry for view ${view.sessionId} - directory no longer exists")
        return null
    }
}