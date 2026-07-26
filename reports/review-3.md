# 🏛️ Faceted Review Report — View Directory Lifecycle Management

**Review Number**: 3 (Strategic)
**Resolution finished**: [x]

---

## Executive Summary

This review covers a **HIGH complexity** change introducing ~1,200 lines of new Kotlin production code and ~800 lines of tests implementing a View Directory Lifecycle Management system for the `sources-mcp` MCP server. The architecture (A1 model: external view directories, two-tool `check`/`sync` protocol, time-based cleanup) is **sound and well-reasoned**.

However, the change has **blocking defects** that prevent it from shipping in its current state:

1. **Integration tests do not compile** — a constructor parameter (`cacheService`) was removed from `SourcesViewService` without updating 4 integration test call sites.
2. **`forceRedownload` is silently ignored** — the `REDOWNLOAD_SOURCES` and `FULL_REFRESH` sync modes are non-functional because the downloader's double-check logic unconditionally returns existing CAS entries.
3. **Spec-implementation drift** — multiple spec documents document behaviors the implementation does not provide (or vice versa), including `casKey` format, method signatures, and cleanup configurability.

The reviewers also identified 15 major and 25 minor findings spanning concurrency (unbounded coroutine spawning, subprocess blocking), performance (redundant file scanning, unbounded caches), security (subprocess timeout, cancellation swallowing, path exposure), and test quality (missing coverage, weak assertions).

**Overall**: The system is architecturally correct but needs critical defect fixes and substantial spec/documentation updates before merge.

---

## 🔴 Critical (Must Address)

### 1. **[Build + All Facets]** `[Mistake]`: Integration Tests Fail to Compile — `SourcesViewService` Constructor No Longer Has `cacheService` Parameter

- **Code Context**: Two integration test files pass a `cacheService` named parameter that `SourcesViewService` no longer accepts:
  - `src/integrationTest/kotlin/dev/rnett/sources/mcp/CheckDependenciesIntegrationTest.kt:28-30` and `:52-57`
  - `src/integrationTest/kotlin/dev/rnett/sources/mcp/SourcesViewServiceIntegrationTest.kt:26-31` and `:102-107`

  ```kotlin
  // Both integration test files use this pattern (4 call sites total):
  val cacheService = DependencyCacheService(testEnv)
  val service = SourcesViewService(
      dependencyAnalyzer = DependencyAnalyzer(cacheService),
      downloader = DependencySourcesDownloader(testEnv),
      cacheService = cacheService,  // ← ERROR: No parameter with name 'cacheService' found
      env = testEnv
  )
  ```

  The actual `SourcesViewService` constructor (SourcesViewService.kt:41-46) is:
  ```kotlin
  class SourcesViewService(
      private val dependencyAnalyzer: DependencyAnalyzer = DependencyAnalyzer(),
      private val downloader: DependencySourcesDownloader = DependencySourcesDownloader(),
      private val env: SourcesMcpEnvironment = SourcesMcpEnvironment.fromEnv(),
      private val normalizationService: NormalizationService = NoOpNormalizationService(),
  )
  ```

- **Root Cause & Flaw**: The `cacheService` parameter was removed from `SourcesViewService` (per Resolution Plan for Review #2, Finding #21 — it was unused by the class body), but the integration tests were not updated to match. Because integration tests are compiled by a separate `compileIntegrationTestKotlin` task that is not part of `test`, a passing `test` invocation does not catch this. The integration test suite is the only test coverage for end-to-end sync/check flows with real ORT analysis and downloads.

- **Pragmatic Rationale**: The `integrationTest` suite is wired into `tasks.check`, meaning CI will fail. This is a hard blocker — the change cannot be merged in its current state.

- **Recommendation**: Remove `cacheService = cacheService,` from all 4 call sites in both integration test files. The `DependencyCacheService` instance is still needed to construct `DependencyAnalyzer(cacheService)`, so keep the `val cacheService = DependencyCacheService(testEnv)` declaration but stop passing it to `SourcesViewService`.

- **Verification Strategy**: Run `./gradlew compileIntegrationTestKotlin` — must succeed with zero compilation errors. Then `./gradlew integrationTest` must pass.

- **Resolution**: ✅ Fixed

---

### 2. **[Logic + Risk]** `[Mistake]`: `forceRedownload = true` Is Silently Ignored — `REDOWNLOAD_SOURCES` and `FULL_REFRESH` Sync Modes Are Non-Functional

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/DependencySourcesDownloader.kt`, lines 38-57:
  ```kotlin
  fun downloadSources(pkg: Package, forceRedownload: Boolean = false): Path {
      val casKey = getCasKey(pkg)
      val targetDir = env.casDir.resolve(casKey)
  
      // 1. Fast path: check if already in CAS
      if (!forceRedownload && targetDir.exists()) {
          return targetDir
      }
  
      // 2. Acquisition of the advisory lock
      val lockFile = env.locksDir.resolve("$casKey.lock")
      return FileLockManager.withLock(lockFile) {
          // 3. Double-check after acquiring the lock
          if (targetDir.exists()) {              // ← ALWAYS returns existing dir
              return@withLock targetDir
          }
          downloadAndProcess(pkg, targetDir, casKey)
      }
  }
  ```
  When `forceRedownload = true`, the fast path is skipped, but the double-check inside the lock (`if (targetDir.exists())`) unconditionally returns the existing directory. The existing CAS entry is never deleted before re-downloading.

- **Root Cause & Flaw**: The double-check pattern is designed for the normal caching case (avoid race between fast-path check and lock acquisition). But it doesn't account for `forceRedownload`. The flag is consumed at the fast path but ignored at the double-check.

- **Pragmatic Rationale**: `SyncMode.REDOWNLOAD_SOURCES` and `SyncMode.FULL_REFRESH` (which pass `redownload = true` at SourcesViewService.kt:114) do not actually re-download any sources. A user requesting a full refresh will silently get stale cached sources. The test `testRedownloadModeReDownloads` (SourcesViewServiceTest.kt:170-177) doesn't catch this because `downloader` is mocked — the mock returns the same path regardless of `forceRedownload`.

- **Recommendation**: Inside the lock, check `forceRedownload` and delete+redownload if true:
  ```kotlin
  return FileLockManager.withLock(lockFile) {
      if (targetDir.exists()) {
          if (forceRedownload) {
              targetDir.deleteRecursively()
              // fall through to downloadAndProcess
          } else {
              return@withLock targetDir
          }
      }
      downloadAndProcess(pkg, targetDir, casKey)
  }
  ```

- **Verification Strategy**: An integration test that (a) downloads a package normally, (b) modifies or stamps the CAS directory content, (c) re-downloads with `forceRedownload = true`, and (d) verifies the content was replaced (not the previously-stamped content). Alternatively, verify that the CAS directory was deleted and recreated.

- **Resolution**: ✅ Fixed

---

### 3. **[Test + Logic]** `[Mistake]`: `testRedownloadModeReDownloads` Validates Behavior Contradicting the Spec — Test Asserts Re-Analysis When Spec Says Reuse

- **Code Context**: `src/test/kotlin/dev/rnett/sources/mcp/SourcesViewServiceTest.kt`, lines 170-177:
  ```kotlin
  fun testRedownloadModeReDownloads() = runTest {
      setupSyncScenario("redownload-lib")
      service.sync(tempDir)
      service.sync(tempDir, mode = SyncMode.REDOWNLOAD_SOURCES)

      verify(exactly = 2) { analyzer.analyzeDependencies(tempDir, force = any()) }
      verify(exactly = 2) { downloader.downloadSources(any(), forceRedownload = any()) }
  }
  ```

- **Root Cause & Flaw**: The spec (OpenSpec `view-directory-creation`) states for `redownload=true`: **"Re-use cached ORT analysis**, ... re-download+process dependencies." But `sync()` at lines 82-84 evicts the cache for ALL non-CACHED modes, forcing re-analysis. The test asserts `exactly = 2` calls to `analyzeDependencies`, validating the **incorrect** behavior instead of catching this spec-code mismatch.

- **Pragmatic Rationale**: Redownload mode is slower than specified — it re-runs full ORT analysis when it should only re-download sources. The cache eviction logic at SourcesViewService.kt:82-83 prematurely evicts for `REDOWNLOAD_SOURCES` mode.

- **Recommendation**: Only evict the in-memory cache when the mode includes analysis refresh:
  ```kotlin
  if (mode == SyncMode.REFRESH_ANALYSIS || mode == SyncMode.FULL_REFRESH) {
      viewCache.remove(cacheKey)
  }
  ```
  Then update the test to `verify(exactly = 1)` for `analyzeDependencies` and `verify(exactly = 2)` for `downloadSources` (initial + re-download).

- **Verification Strategy**: After the fix, `testRedownloadModeReDownloads` should assert `verify(exactly = 1)` for `analyzeDependencies` and `verify(exactly = 2)` for `downloadSources(any(), forceRedownload = true)` on the second call.

- **Resolution**: ✅ Fixed

---

### 4. **[Spec]** `[Mistake]`: `casKey` Separator Is Colon `:` in Spec, Hyphen `-` in Implementation — Data Format Mismatch

- **Code Context**:
  - Spec (`view-directory-creation/spec.md:131-134`): `"{algorithm}:{hash}"` with example `SHA256:2c26b46b68ffc6dce2ec417...`
  - Implementation (`DependencySourcesDownloader.kt:94`): `"${hash.algorithm}-${hash.value}"` producing e.g., `SHA256-abc123...`
  - Manifest generation (`SourcesViewService.kt:203`): stores `casPath.fileName.toString()` as `casKey` — this is the hyphen-separated directory name from the CAS.

- **Root Cause & Flaw**: The spec documents colon-separated format, the implementation produces hyphen-separated. The manifest is a public artifact that agents may inspect. If any downstream code reads the manifest and parses `casKey` expecting colon-separated format, it will fail.

- **Pragmatic Rationale**: This is a data contract inconsistency. The `casKey` is consumed by agents/LLMs and used for CAS lookups. Format mismatches between spec and implementation are data corruption vectors.

- **Recommendation**: Update the spec to use hyphen format `{algorithm}-{hash}` to match the implementation. Update the spec example to show a realistic hyphen-separated key. Add an assertion in the ViewModels test that `casKey` format matches `^[A-Z0-9]+-[a-f0-9]{64}$`.

- **Verification Strategy**: After the fix, the spec's documented `casKey` format must match the actual directory names inside `cache/cas/`. The serialization round-trip test should use realistic-format `casKey` values.

- **Resolution**: ✅ Fixed

---

## 🟡 Major (Highly Recommended)

### 5. **[Architecture + Logic]** `[Mistake]`: `check()` Keys by `projectRoot` Only; `sync()` Keys by `projectRoot` + `scopes` — Creates Cross-Key Leak and Non-Deterministic View Selection

- **Code Context**:
  - `sync()` (SourcesViewService.kt:73-78): keyed by `ViewCacheKey(projectRoot, scopes)`
  - `check()` (SourcesViewService.kt:246-249): finds in-memory entries by iterating `viewCache.entries` matching only on `normalizedProjectRoot`, ignoring scopes entirely:
  
  ```kotlin
  // SourcesViewService.kt:246-249
  val cachedEntry = viewCache.entries.firstOrNull {
      val normalized = runCatching { projectRoot.toAbsolutePath().normalize().toRealPath() }.getOrNull()
      normalized != null && it.key.normalizedProjectRoot == normalized
  }
  ```
  The disk-scan path (`findViewOnDisk` → `findViewByScanning`, lines 279-337) has the same problem — reads `manifest.projectRoot` but ignores `manifest.scopes`.

- **Root Cause & Flaw**: `check_dependencies` MCP tool (SyncCheckTools.kt:168-203) only accepts `projectRoot` as input. The design doc deliberately kept `check` lightweight. However, the consequence is that `check()` can report `fresh: true` for a scope set the agent doesn't care about. The `ConcurrentHashMap` iteration order is unspecified, making selection non-deterministic. The disk scan's `Files.list()` also does not guarantee order.

- **Pragmatic Rationale**: An agent calling `check_dependencies` after previously syncing with `scopes=["compileClasspath"]` might receive results from a different view synced with `scopes=["runtimeClasspath"]`, leading to confusing staleness results.

- **Recommendation**: Accept `scopes` as an optional parameter to `check()` and `check_dependencies`, matching `sync()`'s signature. Construct a `ViewCacheKey` directly for O(1) lookup. Alternatively, sort by timestamp descending and return the most recent view. Document the selection rule.

- **Verification Strategy**: A test where two `sync()` calls with different scope sets produce different `ViewCacheKey` entries, and `check(projectRoot, scopes=compileClasspath)` returns the correct entry.

- **Resolution**: ✅ Fixed

---

### 6. **[Performance + Risk]** `[Intentional — Flawed]`: `findProjectManagedFiles()` Called Twice on Every `sync()` Hot Path — O(n) File Scan Executed Redundantly

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, Lines 92-95 and 103:
  ```kotlin
  // line 92-95 — inside mutex.withLock:
  val result = dependencyAnalyzer.analyzeDependencies(projectRoot, force = ...)
  // ...
  val depHash = dependencyAnalyzer.computeDepHash(projectRoot)  // line 103
  ```
  In `DependencyAnalyzer.kt`, both `analyzeDependencies()` and `computeDepHash()` each independently call `findProjectManagedFiles()`, which traverses the project directory tree and reads all build files. The `calculateCacheKey()` method iterates all managed files calling `file.readBytes()` on each to compute SHA-256.

- **Root Cause & Flaw**: `analyzeDependencies()` internally calls `findProjectManagedFiles()` for cache key computation and ORT analysis. Separately, `computeDepHash()` calls `findProjectManagedFiles()` from scratch. The hash computation reads each file's bytes twice across the two call paths.

- **Pragmatic Rationale**: For a Gradle multi-module project with 50+ `build.gradle.kts` files, this is 50 `File.listFiles()` + 50 `readBytes()` calls executed twice. On spinning disks or network mounts, the impact is measurable.

- **Recommendation**: Have `analyzeDependencies()` return the `ManagedFileInfo` alongside the `AnalyzerResult`, or have it compute and return the cache key/hash since it already generates it internally. At minimum, add a `findProjectManagedFiles()` result to the in-memory cache so `computeDepHash()` can reuse it.

- **Verification Strategy**: Profile `sync()` with a multi-module Gradle project. Verify `findProjectManagedFiles()` is called exactly once per `sync()` invocation. A unit test can mock `Analyzer` and verify `findManagedFiles` is called at most once.

- **Resolution**: ✅ Fixed

---

### 7. **[Concurrency + Clean Code]** `[Mistake]`: `syncMutexes` `ConcurrentHashMap` Never Evicts Entries — Two Uncoordinated In-Memory Maps With Asymmetric Eviction

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 50-51 and 82-88:
  ```kotlin
  private val viewCache = ConcurrentHashMap<ViewCacheKey, SessionView>()
  private val syncMutexes = ConcurrentHashMap<ViewCacheKey, Mutex>()
  ```
  
  | Operation | `viewCache` evicted? | `syncMutexes` evicted? |
  |---|---|---|
  | `sync(mode=FULL_REFRESH)` | ✅ (line 84) | ❌ |
  | `getValidCachedView` detects stale dir | ✅ (line 425) | ❌ |
  | `evictView()` called | ✅ (line 361) | ❌ |
  | `ViewCleanupService` deletes view dir | ❌ (no coordination) | ❌ |

  The `syncMutexes` map is **append-only** — `computeIfAbsent` (line 88) adds, nothing removes.

- **Root Cause & Flaw**: The service maintains two `ConcurrentHashMap` instances that should be logically paired, but they have **different eviction strategies**. Both maps grow unbounded in a long-running server. The two-map pattern is fragile — a future developer adding a third eviction path must remember to update both maps.

- **Pragmatic Rationale**: After processing 50 different project directories in a long-running server session, the maps hold entries that will never be accessed again. In production/CI contexts, this is a memory leak.

- **Recommendation**: Either: (a) remove the `Mutex` from `syncMutexes` after the critical section completes in `sync()`, or (b) encapsulate both maps in a single `ViewCache` class with a unified eviction API. Also add bounded-size or TTL-based eviction.

- **Verification Strategy**: A test that calls `sync()` with multiple distinct keys, measures `syncMutexes.size` after all calls complete, and asserts cleanup. A test verifying that `syncMutexes.size` does not monotonically increase across repeated sync/evict cycles.

- **Resolution**: ✅ Fixed

---

### 8. **[Performance + Risk]** `[Intentional — Flawed]`: `computeDepHash` Creates a Full Throwaway `Analyzer` Instance for What's Supposed to Be a Lightweight Staleness Check

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/DependencyAnalyzer.kt`, lines 61-63:
  ```kotlin
  fun computeDepHash(projectRoot: Path): String {
      val managedFiles = findProjectManagedFiles(projectRoot)
      return DependencyCacheService.calculateCacheKey(managedFiles)
  }
  ```
  `findProjectManagedFiles` creates `Analyzer(AnalyzerConfiguration())` which scans the project directory, instantiates package managers, and discovers all managed files. In ORT, this is heavyweight — it loads plugin classes, scans directory trees, and potentially spawns subprocesses.

- **Root Cause & Flaw**: The `check()` API is described as "a lightweight staleness check ... no download or ORT analysis." But `computeDepHash` internally invokes ORT's `findManagedFiles`, which IS a form of ORT analysis. The `check_dependencies` MCP tool advertises itself as lightweight but internally does heavyweight ORT work.

- **Pragmatic Rationale**: An LLM agent calling `check_dependencies` to decide whether to sync expects near-instantaneous response. A 2-5 second delay for large projects degrades the agent experience and contradicts documented guarantees.

- **Recommendation**: Cache the `managedFiles` discovery result keyed by `projectRoot` with a short TTL, so repeated `check()` calls on the same project are fast. Update the tool description to accurately reflect the performance characteristics. Alternatively, accept that `findManagedFiles` is necessary and remove the "lightweight" claim.

- **Verification Strategy**: Add a performance test that calls `check()` twice on the same project root and verifies the second call is significantly faster (< 50ms).

- **Resolution**: ✅ Fixed

---

### 9. **[Concurrency]** `[Mistake]`: `runBlocking { delay(...) }` in `FileLockManager.withLock` Blocks Coroutine Thread — Thread Starvation on Lock Contention

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/FileLockManager.kt`, lines 81-84:
  ```kotlin
  // Using runBlocking for delay to support non-coroutine callers.
  runBlocking {
      delay(pollDelay)
  }
  ```
  Calling path when lock is contended:
  ```
  SourcesViewService.sync()                                    [suspend, coroutine context]
    → mutex.withLock { ... }                                   [coroutine context]
      → downloadAndLinkDependencies(...)                        [coroutineScope]
        → async { semaphore.withPermit {                        [coroutine context]
            downloader.downloadSources(pkg, ...)                [REGULAR function]
              → FileLockManager.withLock(lockFile) { ... }     [REGULAR function]
                → runBlocking { delay(500.milliseconds) }      ← THREAD BLOCKS
  ```

- **Root Cause & Flaw**: `FileLockManager.withLock` uses `runBlocking` for its backoff delay. When invoked from a coroutine context, `runBlocking` seizes the underlying thread for the full `pollDelay`. The lock-acquisition loop can retry up to 120 times (60s timeout), keeping the thread pinned. With all coroutine worker threads potentially blocked on contended file locks, the entire view sync pipeline stalls.

- **Pragmatic Rationale**: Under any file lock contention (concurrent syncs, redownload races), threads are wasted. For a multi-client MCP server, this causes cascading delays.

- **Recommendation**: Either (a) wrap the `downloadSources()` call site with `withContext(Dispatchers.IO)` to offload blocking to the IO dispatcher's elastic thread pool, or (b) make `FileLockManager.withLock` a `suspend` function using `delay()` directly without `runBlocking`.

- **Verification Strategy**: A test that launches two coroutines contending for the same file lock, with a custom dispatcher limited to 1 thread. Without the fix, the second coroutine blocks the dispatcher. With the fix, both coroutines interleave cooperatively.

- **Resolution**: ✅ Fixed

---

### 10. **[Concurrency + Performance]** `[Mistake]`: `packages.map { async { ... } }.awaitAll()` Launches Unlimited Coroutine Objects — Memory Bloat for Large Dependency Graphs

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 195-231:
  ```kotlin
  private suspend fun downloadAndLinkDependencies(
      packages: List<Package>, sourcesDir: Path, redownload: Boolean
  ): List<ViewDependency> = coroutineScope {
      val semaphore = Semaphore(parallelism.coerceAtLeast(1))

      packages.map { pkg ->
          async {
              semaphore.withPermit {
                  // ... download + link work ...
              }
          }
      }.awaitAll().filterNotNull()
  }
  ```
  N coroutines are launched (where N = `packages.size`), but only `parallelism` are active inside `withPermit`. The remaining N − parallelism coroutines are suspended, each consuming ~2-4 KB.

- **Root Cause & Flaw**: For a project with 10,000 transitive dependencies, 10,000 coroutine objects are allocated (~20-40 MB heap). Cancellation performance degrades linearly. The `Semaphore` correctly caps active concurrency but does not prevent unbounded coroutine object creation.

- **Pragmatic Rationale**: For typical projects (50-200 deps), overhead is negligible. For monorepos with thousands of transitive dependencies, this is a real memory concern.

- **Recommendation**: Replace with **chunked execution** that limits the number of in-flight coroutines to `parallelism`:
  ```kotlin
  packages.chunked(parallelism).flatMap { chunk ->
      chunk.map { pkg ->
          async {
              try {
                  // work (no semaphore needed since batch size == parallelism)
              } catch (e: Exception) { logger.warn(...); null }
          }
      }.awaitAll()
  }.filterNotNull()
  ```
  Alternative: use `kotlinx.coroutines.flow.flatMapMerge` with `concurrency = parallelism`.

- **Verification Strategy**: Create a test with 1000+ mock packages. Measure coroutine count — must never exceed `parallelism`. Verify chunked version produces identical results to current version.

- **Resolution**: ✅ Fixed

---

### 11. **[Concurrency + Security]** `[Mistake]`: `process.waitFor()` in `createJunction()` Blocks Coroutine Thread — Pipeline-Wide Deadlock Risk

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 372-395 (called from line 214 inside `async { semaphore.withPermit { ... } }`):
  ```kotlin
  private fun createJunction(linkPath: Path, targetPath: Path) {
      // ...
      val process = ProcessBuilder("cmd", "/d", "/c", "mklink", "/J", linkStr, targetStr)
          .redirectErrorStream(true).start()

      val exitCode = process.waitFor()        // ← BLOCKS CALLING THREAD, NO TIMEOUT
      // ...
  }
  ```

- **Root Cause & Flaw**: `ProcessBuilder.start()` launches a native OS subprocess. `process.waitFor()` blocks the calling Java thread with **no timeout**. Combined with the `Semaphore(parallelism)`, if all permit-holding coroutines are simultaneously executing `createJunction()`, ALL threads are blocked. If `mklink` hangs (network drive stall, antivirus hook), this is a practical deadlock scenario. There is no `waitFor(timeout)` variant used.

- **Pragmatic Rationale**: Under normal operation, `mklink` is fast. But a single problematic CAS entry degrades to full denial-of-service: all semaphore permits consumed by hung calls, no further progress possible.

- **Recommendation**: Add timeout (e.g., 30 seconds) with `waitFor(30, TimeUnit.SECONDS)` and `destroyForcibly()` on timeout. Also offload to `Dispatchers.IO` via `withContext`:
  ```kotlin
  private suspend fun createJunction(linkPath: Path, targetPath: Path) {
      withContext(Dispatchers.IO) {
          val process = ProcessBuilder("cmd", "/d", "/c", "mklink", "/J", linkStr, targetStr)
              .redirectErrorStream(true).start()
          val exited = process.waitFor(30, TimeUnit.SECONDS)
          if (!exited) {
              process.destroyForcibly()
              throw RuntimeException("mklink /J timed out for $linkStr -> $targetStr")
          }
          // ...
      }
  }
  ```

- **Verification Strategy**: Test with a mock subprocess that never exits — verify `sync()` completes (or throws) within the timeout. Verify semaphore permits are released after timeout.

- **Resolution**: ✅ Fixed

---

### 12. **[Security + Concurrency]** `[Mistake]`: `mklink /J` Error Path Silently Accepts Possibly Wrong Junction Target Without Verification

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 387-394:
  ```kotlin
  if (exitCode != 0) {
      val error = process.inputStream.bufferedReader().readText()
      if (!linkPath.exists()) {
          throw RuntimeException("Failed to create junction $linkStr -> $targetStr: $error")
      } else {
          logger.warn("mklink /J reported non-zero exit code ($exitCode) but junction exists at $linkStr. May point to wrong target. Error: $error")
      }
  }
  ```

- **Root Cause & Flaw**: When `mklink /J` returns non-zero exit but the link directory already exists, the code logs a warning and **proceeds without verifying the target**. The pre-existing junction (from a prior run, or race condition) could point to a different CAS entry. The pre-check `!linkPath.exists()` at line 213 is a TOCTOU gate. Because `downloadAndLinkDependencies` catches all exceptions and returns `null`, the stale junction silently replaces the correct one.

- **Pragmatic Rationale**: An agent searching dependency sources through the stale junction would search entirely wrong source code, potentially synthesizing incorrect recommendations. The integrity of search results is compromised without any error signal.

- **Recommendation**: After non-zero exit code, verify the junction target using Windows API or `Files.readAttributes`. If target doesn't match `targetPath`, either overwrite or throw:
  ```kotlin
  if (exitCode != 0) {
      if (!linkPath.exists()) throw RuntimeException(...)
      val actualTarget = resolveJunctionTarget(linkPath)
      if (actualTarget != targetPath.toRealPath()) {
          throw RuntimeException("Junction at $linkStr points to wrong target: $actualTarget")
      }
  }
  ```

- **Verification Strategy**: Test that pre-creates a junction pointing to wrong CAS entry, then calls `sync()` and verifies either the junction is corrected or sync fails with appropriate error.

- **Resolution**: ✅ Fixed

---

### 13. **[Clean Code + Logic]** `[Intentional — Flawed]`: `downloadAndLinkDependencies` Silently Drops Failed Downloads — Creates Partial Views With Matching `depHash`

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 198-232:
  ```kotlin
  packages.map { pkg ->
      async {
          semaphore.withPermit {
              try {
                  val casPath = downloader.downloadSources(pkg, forceRedownload = redownload)
                  // ... compute ViewDependency ...
              } catch (e: Exception) {
                  logger.warn("Failed to process dependency ${pkg.id.toCoordinates()}: ${e.message}")
                  null                            // ← silently dropped
              }
          }
      }
  }.awaitAll().filterNotNull()                    // ← removed from result set
  ```

- **Root Cause & Flaw**: When download fails, the dependency is excluded from the view via `filterNotNull()`. But the `ViewManifest.depHash` (computed from dependency *declaration* files, not successfully downloaded dependencies) will still match future `check()` calls. Result: `check()` reports `fresh = true` for a view missing dependencies.

- **Pragmatic Rationale**: An agent running `check_dependencies` → `fresh: true` would skip re-syncing, unaware that a dependency silently failed last time. The partial-availability pattern should be an explicit, documented behavior.

- **Recommendation**: Include failed dependency IDs in the manifest as a separate field (e.g., `failedDependencies: List<String>`), and either incorporate them into `depHash` computation or have `check()` report dependency count mismatch. Document the partial-availability contract.

- **Verification Strategy**: Test: mock `downloader.downloadSources()` to throw for 1 of 3 packages. Assert manifest has `failedDependencies` or `depHash` differs from clean hash.

- **Resolution**: ✅ Fixed

---

### 14. **[Risk]** `[Mistake]`: Three Conflicting `SourcesMcpEnvironment` Instances Created at `SourcesMcpServer` Startup — Architectural Duplication

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesMcpServer.kt`, lines 16-18:
  ```kotlin
  private val viewService = SourcesViewService()                              // env #1 (via default param)
  private val syncCheckTools = SyncCheckTools(viewService)
  private val cleanupService = ViewCleanupService(SourcesMcpEnvironment.fromEnv())  // env #2
  ```
  `SourcesViewService()` defaults trigger `SourcesMcpEnvironment.fromEnv()` in:
  1. `SourcesViewService`'s own `env` parameter
  2. `DependencyAnalyzer()` default → `DependencyCacheService()` default → `SourcesMcpEnvironment.fromEnv()`
  3. `ViewCleanupService(SourcesMcpEnvironment.fromEnv())` explicit

  Three separate instances, each calling `createDirectories()` on same subdirectories.

- **Root Cause & Flaw**: While `createDirectories()` is idempotent, this violates single-source-of-truth for environment configuration. If `SourcesMcpEnvironment` ever gains mutable state (metrics, file watchers, connection pools), these instances diverge.

- **Recommendation**: Extract a single `SourcesMcpEnvironment` instance and pass explicitly to all consumers:
  ```kotlin
  private val env = SourcesMcpEnvironment.fromEnv()
  private val cacheService = DependencyCacheService(env)
  private val analyzer = DependencyAnalyzer(cacheService)
  private val downloader = DependencySourcesDownloader(env)
  private val viewService = SourcesViewService(analyzer, downloader, env)
  private val cleanupService = ViewCleanupService(env)
  ```

- **Verification Strategy**: Verify `SourcesMcpEnvironment.fromEnv()` is invoked exactly once during `SourcesMcpServer` initialization. A static counter in the companion object would suffice.

- **Resolution**: ✅ Fixed

---

### 15. **[Security + Concurrency]** `[Mistake]`: `CancellationException` Swallowed by Broad `catch (e: Exception)` in Concurrent Download Block — Broken Structured Concurrency

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 225-229:
  ```kotlin
  async {
      semaphore.withPermit {
          try {
              // ... download and link ...
          } catch (e: Exception) {
              logger.warn("Failed to process dependency ${pkg.id.toCoordinates()}: ${e.message}")
              null
          }
      }
  }
  ```

- **Root Cause & Flaw**: `CancellationException` extends `RuntimeException` extends `Exception`, so it's caught. When scope is cancelled (MCP request timeout, parent failure), the coroutine returns `null` instead of propagating cancellation. The parent `awaitAll()` sees a completed child rather than a cancelled one. The view directory and manifest are committed (lines 106-128) before cancellation propagates to `downloadAndLinkDependencies`, creating a partial view with missing dependencies.

- **Recommendation**: Re-throw `CancellationException`:
  ```kotlin
  } catch (e: CancellationException) {
      throw e
  } catch (e: Exception) {
      logger.warn(...)
      null
  }
  ```

- **Verification Strategy**: Test that cancels the coroutine scope during `downloadAndLinkDependencies` and verifies `CancellationException` propagates. Verify view cleanup logic removes the partial view.

- **Resolution**: ✅ Fixed

---

### 16. **[Architecture + Risk]** `[Mistake]`: `SourcesMcpServer.start()` Blocks Indefinitely, Has No Graceful Shutdown Path for the MCP Server

- **Code Context**: `SourcesMcpServer.kt:43-45`:
  ```kotlin
  runBlocking {
      server.connect(transport)
  }
  ```
  The `server` variable is scoped inside `start()`; there is no field for it. The `stop()` method only calls `cleanupService.stop()`. There is no `close()` or `disconnect()` on the server. If process receives SIGTERM/CTRL-C, in-flight requests may not drain.

- **Recommendation**: Store `server` as a field. In `stop()`, call `server.close()`. Register a JVM shutdown hook. This enables proper integration testing of server start/stop cycles.

- **Verification Strategy**: Integration test that starts the server, sends a request, stops the server, and verifies no resources leak.

- **Resolution**: ✅ Fixed

---

### 17. **[Build]** `[Mistake]`: New `mainClass` `SourcesMcpServerKt` May Not Resolve at Runtime — No `main()` Function Exists

- **Code Context**: `build.gradle.kts` line 22: `mainClass.set("dev.rnett.sources.mcp.SourcesMcpServerKt")`. `SourcesMcpServer.kt` contains no top-level `fun main()`. The `Kt` file-level facade class would exist but have no `main` method, causing `java -jar` to fail with `Main method not found`.

- **Recommendation**: Add a top-level `fun main()` to `SourcesMcpServer.kt` that creates, registers shutdown hook, and starts the server. If intentionally deferred, add `TODO` comments to both the `mainClass` line and `SourcesMcpServer.kt`.

- **Verification Strategy**: Run `./gradlew shadowJar` and execute `java -jar build/libs/sources-mcp-*.jar` to confirm the entry point is reachable.

- **Resolution**: ✅ No action (false positive — main() already exists)

---

### 18. **[Clean Code]** `[Mistake]`: Duplicate `extractProjectRoot` Try/Catch

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SyncCheckTools.kt`, lines 113-121 (syncDependenciesHandler) and 171-179 (checkDependenciesHandler). Both contain identical try/catch blocks parsing `projectRoot` from args and returning `CallToolResult(isError = true)` on failure.

- **Root Cause & Flaw**: Textbook local DRY violation. Any change to error message format or exception type must be made in two places.

- **Recommendation**: Extract to a private helper returning `CallToolResult?` on failure:
  ```kotlin
  private suspend fun parseProjectRoot(args: JsonObject): CallToolResult? { ... }
  ```

- **Verification Strategy**: After extraction, grep for `"Error: \"'projectRoot' is required"` shows exactly one occurrence.

- **Resolution**: ✅ Fixed

---

### 19. **[Test]** `[Mistake]`: `testCheckReturnsNoViewWhenNoAnalysisCache` Tests the Wrong Scenario — Test Name and Behavior Mismatch

- **Code Context**: `src/test/kotlin/dev/rnett/sources/mcp/SourcesViewServiceTest.kt`, lines 480-486:
  ```kotlin
  fun testCheckReturnsNoViewWhenNoAnalysisCache() = runTest {
      every { analyzer.computeDepHash(tempDir) } returns "hash-no-cache"
      val staleness = service.check(tempDir)
      assertTrue(!staleness.viewExists)  // correctly false — but tests no-view scenario, not spec scenario
  }
  ```

- **Root Cause & Flaw**: The spec specifies: "Scenario: check with view but no analysis cache — WHEN check is called and a view exists on disk but analysis cache is missing, THEN returns viewExists: true, fresh: false." This test never creates a view, so it tests "no view" instead. The spec scenario is entirely untested.

- **Recommendation**: Rename current test to `testCheckReturnsNoViewWhenNoPriorSync`. Add new test that: syncs to create view, evicts from in-memory cache, deletes analysis cache files, calls `check()`, asserts `viewExists == true, fresh == false`.

- **Verification Strategy**: New test passes with correct behavior. Test name accurately describes scenario.

- **Resolution**: ✅ Fixed

---

## 🔵 Minor (Polishing/Idioms)

### 20. **[Build + Architecture]** `[Mistake]`: Vendored MCP SDK Files in `commonMain/` Are Not Compiled — Entire Directory Is Orphaned

- **Code Context**: `commonMain/io/modelcontextprotocol/kotlin/sdk/types.kt` and `server/Server.kt` are untracked files outside any Gradle source set. The project already has a proper MCP SDK dependency via `libs.mcp.sdk`. These files appear to be leftover prototyping artifacts.

- **Root Cause & Flaw**: The directory name suggests KMP source set convention but the project is JVM-only. The files create the false impression of functionality, risk license violations (if modified SDK copies), and invite confusion about what actually compiles.

- **Recommendation**: Delete the entire `commonMain/` directory tree. The SDK is properly consumed via Gradle.

- **Verification Strategy**: After deletion, `./gradlew compileKotlin` must still succeed. `git status` no longer shows `commonMain/` as untracked.

- **Resolution**: ✅ Fixed

---

### 21. **[Idiom]** `[Mistake]`: `assertTrue(!x)` Used Instead of `assertFalse(x)` — 5 Occurrences in `SourcesViewServiceTest.kt`

- **Code Context**: `src/test/kotlin/dev/rnett/sources/mcp/SourcesViewServiceTest.kt`, lines 201, 210, 380, 381, 484:
  ```kotlin
  assertTrue(!staleness.fresh)
  assertTrue(!staleness.viewExists)
  // ... (5 total occurrences)
  ```

- **Root Cause & Flaw**: Project uses `kotlin.test.assertFalse` elsewhere. Negated assertions produce worse failure messages (`"Expected <true>, actual <false>."` with no context). Inconsistency within the same test suite.

- **Recommendation**: Add `import kotlin.test.assertFalse` and replace all 5 occurrences with `assertFalse(x)`.

- **Verification Strategy**: Grep for `assertTrue(!` — zero matches. Tests pass.

- **Resolution**: ✅ Fixed

---

### 22. **[Security]** `[Mistake]`: Error Messages and Warnings Expose Internal Absolute Filesystem Paths — Information Disclosure

- **Code Context**: `SourcesViewService.kt:390, 392` (junction creation errors include `$linkStr` and `$targetStr` — full absolute paths). Also line 186 exposes `sourcesDir` path in `require` message.

- **Root Cause & Flaw**: If exceptions propagate to MCP clients, they receive the server's internal cache layout and directory structure. While low risk in single-user local dev, it's information disclosure for shared/remote contexts.

- **Recommendation**: Use session-relative or dependency-level identifiers in exception messages that propagate. Full paths may appear in server-side `logger.error()` only.

- **Verification Strategy**: Integration tests exercising error paths should assert exception messages do NOT contain absolute paths from `env.viewsDir` or `sourcesDir`.

- **Resolution**: ✅ Fixed

---

### 23. **[Test + Risk]** `[Intentional — Flawed]`: `SyncCheckTools` Has No MCP Protocol-Level Integration Test — Violates Invariant #13

- **Code Context**: `SyncCheckTools.kt` registers tools on the MCP server, but no test invokes them through the MCP protocol transport. All tests bypass `SyncCheckTools` and call `SourcesViewService` directly.

- **Root Cause & Flaw**: Typo in tool parameter name or missing required field in schema would not be caught. The `buildJsonObject {}` DSL for schema construction is manually written and error-prone. Invariant #13 requires protocol-level tests or server bootstrap — the bootstrap exists but protocol tests are missing.

- **Recommendation**: Add a protocol-level integration test that starts `SourcesMcpServer`, connects with test transport, calls `sync_dependencies`/`check_dependencies` via MCP `CallToolRequest`, and verifies `CallToolResult`. Cover: valid sync, valid check, missing parameter, invalid path.

- **Verification Strategy**: New test passes with `./gradlew :integrationTest`.

- **Resolution**: ✅ Fixed

---

### 24. **[Test]** `[Intentional — Flawed]`: No Dedicated Test for New `DependencyAnalyzer.computeDepHash()` Method

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/DependencyAnalyzer.kt`, lines 61-64 (new method). `DependencyAnalyzerTest.kt` has no test for `computeDepHash`. Only tested indirectly through `SourcesViewServiceTest` mocks that bypass the actual method.

- **Root Cause & Flaw**: Integration with `findProjectManagedFiles`, hash of managed file contents, and edge cases (no managed files, symlinks) are never exercised.

- **Recommendation**: Add tests to `DependencyAnalyzerTest`: same files → same hash, different files → different hash, representative ecosystem project.

- **Verification Strategy**: At least 3 test cases covering the new method. `./gradlew test --tests "DependencyAnalyzerTest"` passes.

- **Resolution**: ✅ Fixed

---

### 25. **[Test + Concurrency]** `[Mistake]`: No Verification of Parallelism Limiter Enforcement — Violates Invariant #14

- **Code Context**: `downloadAndLinkDependencies` uses `Semaphore(parallelism)` with `withPermit`. Invariant #14 requires: "A test must verify the limit is honored."

- **Root Cause & Flaw**: No test verifies at most `parallelism` download operations are in-flight concurrently. A future refactoring could remove the semaphore and no test would fail.

- **Recommendation**: Add concurrency test with `AtomicInteger` counting in-flight operations, setting low parallelism, and verifying the limit.

- **Verification Strategy**: Test confirms `maxConcurrent` never exceeds configured parallelism. Run with `SOURCES_VIEW_PARALLELISM=2`.

- **Resolution**: ✅ Fixed

---

### 26. **[Test + Security]** `[Mistake]`: `findViewOnDisk` Manifest Deserialization Safety Guard Is Untested — Violates Invariant #17

- **Code Context**: `SourcesViewService.kt` lines 316-320 perform safety validation on deserialized manifest `projectRoot` (length ≤ 4096, no UNC, drive-letter format). Invariant #17 requires this to be tested.

- **Root Cause & Flaw**: No test creates a manifest with UNC path, over-long path, or relative path and verifies the guard rejects it.

- **Recommendation**: Add test with maliciously crafted manifest containing `"projectRoot": "\\\\server\\share"` and verify `check()` returns `viewExists: false`.

- **Verification Strategy**: Test asserts rejection for unsafe `projectRoot` values. `./gradlew test --tests "SourcesViewServiceTest"` passes.

- **Resolution**: ✅ Fixed

---

### 27. **[Idiom]** `[Unclear Intent]`: `NoOpNormalizationService` Is a `class` — Should Be an `object` (Singleton)

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/NormalizationService.kt`, lines 19-21. Completely stateless — no constructor parameters, no instance state, pure identity function. Using `class` creates unnecessary allocation each time the default parameter is evaluated.

- **Recommendation**: Change to `object NoOpNormalizationService : NormalizationService`. Update constructor default from `= NoOpNormalizationService()` to `= NoOpNormalizationService`.

- **Verification Strategy**: Compile; tests pass.

- **Resolution**: ✅ Fixed

---

### 28. **[Idiom]** `[Intentional — Flawed]`: Side-Effecting Constructor Defaults (`SourcesMcpEnvironment.fromEnv()`) Create Directories at Object Construction Time

- **Code Context**: `SourcesViewService.kt:44`, `SourcesMcpServer.kt:16`. The `SourcesMcpEnvironment.init` block calls `createDirectories()` on 4 paths. Every default parameter evaluation triggers filesystem creation in the user's actual home directory — even during test setup where the `env` will be replaced.

- **Recommendation**: Either move `createDirectories()` out of `init` into a lazy or explicit `ensureDirectories()` method, or add KDoc stating "Intentional: constructor creates directories for bootstrapping simplicity."

- **Verification Strategy**: If extracted: confirm no tests break from missing auto-created directories.

- **Resolution**: ✅ Fixed

---

### 29. **[Idiom]** `[Unclear Intent]`: `SyncMode` Enum Defined as Top-Level in Service File — Should Be in `ViewModels.kt`

- **Code Context**: `SourcesViewService.kt:29-38`. `SyncMode` is a domain model type that controls `sync()` behavior. `ViewModels.kt` contains all other model types. Inconsistent file organization.

- **Recommendation**: Move `SyncMode` enum from `SourcesViewService.kt:29-38` into `ViewModels.kt`. Add import back in service file.

- **Verification Strategy**: `SourcesViewService.kt:29-38` replaced by import. Compilation and tests pass.

- **Resolution**: ✅ Fixed

---

### 30. **[Idiom]** `[Unclear Intent]`: Runtime `val IS_WINDOWS` Uses `const val`-Style SCREAMING_SNAKE_CASE Naming

- **Code Context**: `SourcesViewService.kt:57-59`. `IS_WINDOWS` is a runtime-computed `val` but uses SCREAMING_SNAKE_CASE alongside true `const val` entries. Creates visual inconsistency.

- **Recommendation**: Rename to `isWindows` (camelCase for runtime `val`, screaming case for `const val`).

- **Verification Strategy**: All references updated. Tests pass.

- **Resolution**: ✅ Fixed

---

### 31. **[Clean Code — Readability]** `[Mistake]`: Inconsistent Sentinel/Null-Display Values Across Output Formatting

- **Code Context**: `SyncCheckTools.kt:150` uses `"(all)"` for empty scopes; lines 189-190, 192 use `"N/A"` for null/absent fields. Two different sentinel conventions for the same purpose.

- **Recommendation**: Standardize on one sentinel. Document the convention in the tool description.

- **Verification Strategy**: Grep for `"(all)"` — if still appears, update. All no-data displays use same sentinel.

- **Resolution**: ✅ Fixed

---

### 32. **[Clean Code]** `[Intentional — Flawed]`: `NormalizationService.normalize()` Is Blocking Inside `mutex.withLock` — Holds Mutex During Potential I/O

- **Code Context**: `SourcesViewService.kt:117` calls `normalizationService.normalize(sourcesDir)` inside `mutex.withLock`. The interface declares `fun normalize(Path): Path` (not `suspend`). Future implementations doing I/O will block the mutex thread.

- **Recommendation**: Change interface to `suspend fun normalize(sourcesDir: Path): Path`. `NoOpNormalizationService` remains trivially `suspend`.

- **Verification Strategy**: After changing to `suspend`, `NoOpNormalizationService` must still compile. All tests pass.

- **Resolution**: ✅ Fixed

---

### 33. **[Clean Code]** `[Intentional — Flawed]`: `SourcesViewService` Is a God Object — 14 Methods Across 8 Distinct Responsibilities in 430 Lines

- **Code Context**: `SourcesViewService.kt` contains: orchestration, staleness checking, cache management, scope filtering, validation, download parallelism, platform-specific junctions, manifest serialization, concurrency control.

- **Root Cause & Flaw**: The class simultaneously knows about ORT analysis, dependency graph traversal, Windows junction creation, JSON manifest serialization, cache eviction, and path traversal safety. This was flagged as Review #2 Finding #8 and deferred for bootstrap phase.

- **Recommendation**: Extract at minimum: `ViewStalenessChecker`, `ScopeFilterService`, `JunctionService`, `ViewCacheManager`. Each extracted class ≤ 200 lines, with own test file.

- **Verification Strategy**: After extraction, `SourcesViewService` contains only `sync()` and `downloadAndLinkDependencies()`. All existing tests pass without modification.

- **Resolution**: 🔷 Deferred per bootstrap phase

---

### 34. **[Clean Code]** `[Mistake]`: Dead Development Comment in `ViewCleanupService.kt`

- **Code Context**: `ViewCleanupService.kt:11`:
  ```kotlin
  // jsonObject, jsonPrimitive imports removed — using ViewManifest serializer now (Step 8)
  ```
  Development note referencing an implementation step, meaningless to future readers.

- **Recommendation**: Delete the comment. Git history captures what was removed.

- **Resolution**: ✅ Fixed

---

### 35. **[Performance]** `[Intentional — Flawed]`: `ViewCleanupService.readTimestamp()` Reads and Deserializes Entire `ViewManifest` Just to Extract `timestamp`

- **Code Context**: `ViewCleanupService.kt:92-99`. For a view with 500 dependencies, manifest is ~15-50KB JSON. 99% thrown away since only `timestamp` is needed. During cleanup cycle with 100+ views, this is 1.5-5MB of wasteful I/O and parsing.

- **Recommendation**: Use `Json.parseToJsonElement()` to extract only the `timestamp` field. Or store timestamp as directory last-modified time and read that directly.

- **Verification Strategy**: Profile cleanup cycle with 100+ views. Heap allocation during cleanup should drop.

- **Resolution**: ✅ Fixed

---

### 36. **[Performance]** `[Intentional — Flawed]`: `findViewByScanning()` Performs Linear O(n) Scan of All View Directories on Every `check()` Call — No Index

- **Code Context**: `SourcesViewService.kt:302-336`. Every `check()` call that misses in-memory cache reads and deserializes `manifest.json` from EVERY view directory, performing path normalization per manifest. With 200 stale views (24h retention), each `check()` reads 200 manifest files.

- **Recommendation**: Maintain a lightweight on-disk index (`viewsDir/index.json` mapping `(normalizedProjectRoot, scopes) → sessionId`). Update on `sync()`, delete entries on cleanup. Fall back to scanning only if index is missing/corrupted.

- **Verification Strategy**: Test with 100 unrelated view directories — `check()` should complete in constant time.

- **Resolution**: ✅ Fixed

---

### 37. **[Security]** `[Intentional — Flawed]`: Deserialized `manifest.json` `dependencies` List Has No Size/Entry Validation — Potential Local DoS

- **Code Context**: `SourcesViewService.kt:311-313`. `json.decodeFromString` deserializes unbounded `ViewDependency` list from untrusted on-disk data. Attacker with filesystem write access could plant multi-gigabyte manifest causing OOM.

- **Recommendation**: Add max file size check before reading (`MAX_MANIFEST_SIZE`) and `maxDependencies` bound on deserialized list.

- **Verification Strategy**: Test with manifest containing extreme number of dependency entries, verify rejection without OOM.

- **Resolution**: ✅ Fixed

---

### 38. **[Test]** `[Intentional — Flawed]`: `check()` Method Reads `viewCache` Without Synchronization — Weakly Consistent Iteration

- **Code Context**: `SourcesViewService.kt:246-249`. `ConcurrentHashMap.entries` iteration is weakly consistent — may miss recently added entries or include removed ones. `check()` could return `viewExists: false` for a view just synced on another thread.

- **Recommendation**: Document this as a known limitation (acceptable for best-effort lookup in local dev), or use `syncMutexes` for `check` operations on relevant key to ensure linearizability.

- **Resolution**: ✅ Documented as known limitation

---

### 39. **[Test]** `[Mistake]`: Duplicate `createNpmProject` Helper in Two Integration Test Files — Code Duplication

- **Code Context**: Both `SourcesViewServiceIntegrationTest.kt` (lines 50-93) and `CheckDependenciesIntegrationTest.kt` (lines 84-127) contain identical `createNpmProject(dir, name, dependency)` private methods.

- **Recommendation**: Extract into shared test utility accessible to integration test source set.

- **Verification Strategy**: Both integration test files reference shared helper. Both tests pass.

- **Resolution**: ✅ Fixed

---

### 40. **[Spec]** `[Mistake]`: `sync` Method Signature Spec Drift — Spec Documents `fresh`/`redownload` Booleans, Implementation Uses `SyncMode` Enum

- **Code Context**: Design.md Decision 3 and creation spec document `sync(projectRoot: Path, scopes: Set<String> = emptySet(), fresh: Boolean = false, redownload: Boolean = false)`. Implementation at SourcesViewService.kt:73-77 uses `mode: SyncMode = SyncMode.CACHED`. The MCP tools translate back to booleans, but the internal API spec is stale.

- **Recommendation**: Update the spec to document the `SyncMode` enum and new signature. The `fresh`/`redownload` booleans should be documented only in the MCP tool spec with a note about mapping.

- **Verification Strategy**: A developer copying the documented method signature would produce compilable code.

- **Resolution**: ✅ Fixed

---

### 41. **[Spec]** `[Mistake]`: Undocumented Scope Suffix-Matching Behavior — Implementation Allows `depScope.endsWith(":$userScope")`

- **Code Context**: Spec states "exact match (case-sensitive)." Implementation at SourcesViewService.kt:158-159 allows suffix matching (`depScope == userScope || depScope.endsWith(":$userScope")`). Gradle project-prefixed scopes (e.g., `main:compileClasspath`) match their suffix.

- **Recommendation**: Document the suffix-matching behavior explicitly. Add a scenario: "WHEN sync with scopes=["compileClasspath"] AND dependency has scope "main:compileClasspath" THEN included."

- **Verification Strategy**: Spec contains suffix-matching scenario matching actual behavior.

- **Resolution**: ✅ Fixed

---

### 42. **[Spec]** `[Mistake]`: `redownload` Mode "New CAS Locations" Claim Is Inaccurate — CAS Keys Are Deterministic

- **Code Context**: Spec claims redownload creates "new CAS locations." Implementation uses deterministic `casKey` from artifact hash — same key per version. `forceRedownload` refreshes in-place atomically, cannot produce new key.

- **Recommendation**: Reword: "re-download sources to the same CAS keys (atomically refreshing the content)." Clarify CAS keys are deterministic from artifact hashes.

- **Verification Strategy**: Integration test calling sync with `redownload=true` should show same `casKey` values as prior sync.

- **Resolution**: ✅ Fixed

---

### 43. **[Spec]** `[Intentional — Flawed]`: Missing Edge-Case Scenarios in View Creation Spec — Error Handling Not Documented

- **Code Context**: Creation spec covers happy path only. Implementation handles: `createDirectories()` failure + cleanup, manifest serialization failure, junction with non-zero exit but exists, `computeDepHash` failure before directory creation, validation rejecting all packages. None documented.

- **Recommendation**: Add scenarios for each failure mode. At minimum: "If any step fails after directory creation, service SHALL delete partial view directory before propagating exception."

- **Verification Strategy**: Count `catch` blocks in `SourcesViewService.sync()` — each should have corresponding spec scenario.

- **Resolution**: ✅ Fixed

---

### 44. **[Spec]** `[Mistake]`: Cleanup Maximum Age and Interval Not Configurable via Environment — Spec Promise Unfulfilled

- **Code Context**: Cleanup spec says "maximum age SHALL be configurable via environment variable." Implementation accepts `maxAge`/`cleanupInterval` constructor parameters but no code reads env vars. `SourcesMcpServer` creates with defaults only.

- **Recommendation**: Either implement env var reading (`SOURCES_VIEW_MAX_AGE_HOURS`, etc.) or update spec to remove "configurable via environment" claim.

- **Verification Strategy**: Set env var and verify cleanup honors it.

- **Resolution**: ✅ Fixed

---

### 45. **[Spec]** `[Intentional — Flawed]`: `ViewServiceConfig` Referenced in Design but Absent from Both Spec and Implementation

- **Code Context**: Design.md Decision 14 references `ViewServiceConfig.parallelism`. This class does not exist. Implementation reads parallelism directly from `SOURCES_VIEW_PARALLELISM` env var.

- **Recommendation**: Update Decision 14 to reflect actual implementation. Remove `ViewServiceConfig` reference or mark as planned.

- **Verification Strategy**: Search for `ViewServiceConfig` — must be found in source code or documented as planned/future.

- **Resolution**: ✅ Fixed

---

### 46. **[Spec]** `[Intentional — Flawed]`: Missing Specification for Stale Cache Entry Validation (`getValidCachedView`)

- **Code Context**: Spec says "subsequent sync calls with same inputs SHALL return cached view." Implementation validates view directory still exists before returning cached entry. If deleted (by cleanup), evicts and recreates. Not documented.

- **Recommendation**: Add requirement: "When returning cached view, service SHALL verify view directory still exists on disk." Add corresponding scenario.

- **Verification Strategy**: Spec contains scenario for view directory deleted from disk between sync calls.

- **Resolution**: ✅ Fixed

---

### 47. **[Spec]** `[Intentional — Flawed]`: Missing Specification for `check` Under Exceptional Conditions (I/O errors, corrupt manifest)

- **Code Context**: `check()` can fail: `computeDepHash` I/O errors, empty managed files, corrupt manifest. Implementation handles via `runCatching` but spec doesn't document. When `check` reports `viewExists: false` due to I/O error, agent unnecessarily re-syncs.

- **Recommendation**: Add scenarios for I/O errors and corrupt manifest. Consider adding `error` field to `StalenessCheck` for distinguishing "no view" from "couldn't determine."

- **Verification Strategy**: Each error-handling code path in `check()` should have corresponding spec scenario.

- **Resolution**: ✅ Fixed

---

### 48. **[Spec]** `[Intentional — Flawed]`: Cross-Document Inconsistency — Lifecycle Diagrams Differ Between `design.md` and `view-lifecycle-management.md`

- **Code Context**: Design.md diagram shows complete Server Background section (ViewCleanupService, in-memory cache). Lifecycle-management.md diagram omits background section and has different workflow paths.

- **Recommendation**: Update lifecycle-management.md to either match design.md or explicitly defer: "For authoritative lifecycle model, see [design.md]."

- **Verification Strategy**: Diagrams should be identical or one should clearly defer.

- **Resolution**: ✅ Fixed

---

## ❓ Open Questions for the Author (HIGH BAR)

- **None.** The reviewers flagged no absolutely blocking questions requiring user input.

---

## 💡 Proposed Invariants/Rules

From across all reviewers, these invariants should be added to `AGENTS.md`:

**18. Subprocess Timeout Rule**: Any `ProcessBuilder` invocation that calls `waitFor()` MUST use the timed variant (`waitFor(timeout, unit)`) with `destroyForcibly()` on timeout.

**19. No `runBlocking` in Shared Utility Code**: Any utility function that may be called from a coroutine context MUST NOT use `runBlocking`. Either make it `suspend` or document that callers must offload to `Dispatchers.IO`.

**20. `async`-Per-Item Pattern Requires Bounded Chunking**: When processing variable-length collections with `async`, the number of concurrently launched coroutines MUST be bounded by a configurable limit.

**21. Subprocess Execution from Coroutines Requires `withContext(Dispatchers.IO)`**: Any `ProcessBuilder` or `Runtime.exec()` call made from a coroutine context MUST be wrapped in `withContext(Dispatchers.IO)`.

**22. ConcurrentHashMap as Keyed-Mutex Registry Requires Cleanup Strategy**: Any `ConcurrentHashMap<K, Mutex>` used to serialize per-key operations MUST have an explicit removal policy.

**23. Dual-Cache Coordination Rule**: When a class maintains two in-memory caches that are conceptually paired, eviction operations MUST update both caches atomically. They should share a single eviction API.

**24. Error Message Sanitization Rule**: Exception messages propagating to MCP clients MUST NOT contain absolute filesystem paths. Use dependency identifiers or session-relative paths.

**25. Cancellation Propagation Rule**: Any `catch (e: Exception)` block inside a coroutine MUST explicitly re-throw `CancellationException` before catching.

**26. Integration Test Compilation Gate**: Any change modifying a service constructor signature MUST update all test call sites (unit + integration) in the same commit.

**27. Spec-Signature Parity Rule**: When a documented method signature in a spec changes during implementation, the spec MUST be updated within the same change.

**28. Configuration Transparency Rule**: When a spec says a value is "configurable via environment variable," the implementation MUST contain code that reads that environment variable.

**29. Single-Scan Rule for View Creation**: All operations within a single `sync()` call requiring ORT's `findManagedFiles()` MUST share a single invocation.

**30. Cache Boundedness Rule**: Any in-memory cache keyed by user-provided inputs MUST have a bounded maximum size or TTL-based eviction.

**31. Vendor-Free Workspace Rule**: No vendored/duplicated source files of external libraries may exist in the repository outside of Gradle-managed dependencies.

**32. Scope Matching Transparency Rule**: Any deviation from "exact match" in scope filtering MUST be explicitly documented in both the design decision and behavioral spec.

---

## ✅ Positive Observations

- The **overall architecture** (A1 model, two-tool protocol, UUID keying, time-based cleanup) is well-designed and well-documented.
- `validateDependencyIdentifiers()` implements a **thorough three-layer allowlist validation** for path traversal defense (path separator rejection, character allowlist, normalized `startsWith` check).
- `ViewManifest` deserialization safety (UNC rejection, drive-letter format, `toRealPath()` verification) follows a correct defense-in-depth posture.
- `createDirectories()` calls are correctly placed inside the `try` block with cleanup-on-failure — Invariant #15 is properly satisfied.
- The `parallelism` Semaphore correctly limits active concurrency — Invariant #14's *mechanism* exists (just needs a verification test).
- `SyncCheckTools` is wired into `SourcesMcpServer.start()` — Invariant #13's bootstrap requirement is met.
- The `SyncMode` enum was a good improvement over boolean combinatorics (just needs spec update).
- `computeDepHash` properly delegates to `DependencyCacheService.calculateCacheKey()` — no duplicated hashing logic.
- `ViewCacheKey.create()` wraps `toRealPath()` in `runCatching` — defensive against non-existent paths.
- The `/d` flag on `cmd /d /c` disables AutoRun entries — partial mitigation for subprocess security.
- The `CoroutineScope(SupervisorJob() + Dispatchers.IO)` pattern in `ViewCleanupService` is correct.
- `FileUtils.atomicMoveIfAbsent` three-tier handling correctly handles Windows filesystem limitations.

---

## 📋 Review Status

- **Pass**: 3
- **Findings**: 48 (3 Critical, 16 Major, 29 Minor)
- **Open questions remaining**: 0
- **Next step**: Address Critical findings (#1-4) — these are compilation blockers and non-functional features. Then address Major findings for correctness and performance. Finally update specs to match implementation.
