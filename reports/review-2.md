# 🏛️ Faceted Review Report — Review #2

**Change**: View Directory Lifecycle Management System
**Architecture**: A1 (External View Directory)
**Resolution finished**: [x]

---

## Executive Summary

This is a **broad, ambitious change** adding a view directory lifecycle system. The architecture (A1 — external views, tool-call-is-sync-point) is **well-designed and honest**. The two-tool model (`sync_dependencies` + `check_dependencies`) is clean and Git-like. The phased implementation matches the design doc faithfully.

However, this change was not reviewed incrementally, and the accumulated scope — 9 new source files, 4 test files, modifications to 5 existing files, plus OpenSpec docs — has produced **significant integration debt**. The most critical finding is that `SyncCheckTools` and `ViewCleanupService` have **zero non-test callers**: the code compiles and tests pass, but no user or agent can invoke the primary deliverables.

The second tier of concern is around **filesystem safety**: shell invocation via `cmd /c mklink`, `createDirectories()` placed outside `try/catch`, and a concurrency limiter that does not actually limit concurrency. These issues create realistic failure modes (orphaned directories, command injection, file-descriptor exhaustion).

The third tier is about **code health and maintainability**: `SourcesViewService` is a 433-line God Object, the `view-index.json` cross-process index has a scope-collision bug, `StalenessCheck` construction is inconsistent across 7 call sites, and the concurrency tests themselves are broken by violating Invariant 10.

**Review panel**: 8 agents dispatched (logic, security, concurrency, idiom, clean-code, architecture, risk, test). Results received from 5 (3 returned empty: logic, concurrency, idiom — their facets are partially addressed by overlapping coverage from other reviewers).

---

## 🔴 Critical (Must Address Before Deployment)

### 1. **[Architecture]** `[Mistake]`: `SyncCheckTools` and `ViewCleanupService` Are Completely Unwired — Zero Non-Test Callers

- **Code Context**:
  - `src/main/kotlin/dev/rnett/sources/mcp/SyncCheckTools.kt` — full class with `registerAll(server: Server)`, never instantiated.
  - `src/main/kotlin/dev/rnett/sources/mcp/ViewCleanupService.kt` — full class with `start()`/`stop()`/`cleanup()`, never instantiated outside tests.
  - Grep confirms zero non-test references for both classes.

  ```kotlin
  // SyncCheckTools.kt — implemented but nobody calls registerAll()
  class SyncCheckTools(
      private val viewService: SourcesViewService = SourcesViewService()
  ) {
      fun registerAll(server: Server) { ... }
  }
  
  // ViewCleanupService.kt — implemented but nobody calls start()
  class ViewCleanupService(
      private val env: SourcesMcpEnvironment,
      private val maxAge: Duration = Duration.ofHours(24),
      ...
  ) {
      fun start() { ... }
  }
  ```

- **Root Cause & Flaw**: Both classes were implemented (tasks 5.1, 5.2 were completed "in code" but not "in integration"). The design doc's Phase 5 is marked as "deferred" because the server bootstrap isn't established. However, a class that compiles but can never be invoked is dead code. Tools are the primary deliverable of this change — their absence means the feature simply doesn't exist from a user perspective.

- **Pragmatic Rationale**: This renders all Phase 5 effort inert. The `SyncCheckTools` tests verify handler logic but do not verify that the tools can be invoked through an MCP server, which is what users and agents need.

- **Recommendation**: Either: (a) implement a minimal server bootstrap (`SourcesMcpServer.kt`) that wires all three layers, or (b) add an integration test that instantiates a real MCP server, registers the tools, and verifies invocation produces valid responses. Minimum: the `registerAll()` method must be called by at least one non-test file.

- **Verification Strategy**: After fix, a search for `SyncCheckTools(` and `ViewCleanupService(` must show at least one non-test instantiation each. An integration test must exercise `sync_dependencies` through an MCP protocol call.

- **Resolution**: TODO

---

### 2. **[Concurrency + Test]** `[Mistake]`: Concurrency Tests Use `Thread.sleep()` and `Dispatchers.IO` — Direct Violation of Invariant 10; MockK Argument Matchers Are Also Wrong

- **Code Context**: `src/test/kotlin/dev/rnett/sources/mcp/SourcesViewServiceConcurrencyTest.kt`, lines 24–28, 39–42, 55, 71–72

  ```kotlin
  // Lines 24-28 (both tests):
  val mockAnalyzer = mockk<DependencyAnalyzer> {
      every { analyzeDependencies(any()) } answers {
          Thread.sleep(100)          // ❌ Thread.sleep()
          mockk(relaxed = true)      // ❌ relaxed mock as AnalyzerResult
      }
  }
  
  // Line 39-42 (test 1):
  val jobs = (1..5).map {
      async(Dispatchers.IO) {       // ❌ Dispatchers.IO in runTest
          service.sync(projectDir)
      }
  }
  ```

  The actual call site at `SourcesViewService.kt:80` passes 2 arguments: `analyzeDependencies(projectRoot, force = fresh)`. MockK's `any()` matches exactly 1 arg. The mock configuration may not even be active.

- **Root Cause & Flaw**: Three issues compound:
  1. `Thread.sleep(100)` blocks a real OS thread, defeating `runTest`'s virtual time and making tests non-deterministic.
  2. `async(Dispatchers.IO)` launches coroutines outside `runTest`'s control, creating real race conditions.
  3. `mockk(relaxed = true)` cast as `AnalyzerResult` returns empty/null defaults for every property — if the service accesses any field, it gets silent defaults, not real data. The test verifies "nothing crashes," not concurrent correctness.

  **Invariant 10** from `AGENTS.md`: "Concurrency tests MUST use `runTest` with `StandardTestDispatcher` for virtual time control. Use `delay()` instead of `Thread.sleep()`. Avoid mixing `Dispatchers.IO` with virtual-time tests."

- **Pragmatic Rationale**: These are the ONLY tests that verify the `Mutex`-guarded deduplication of concurrent `sync()` calls. If they produce false confidence, a real concurrency regression would not be caught. On slow CI machines, `Thread.sleep(100)` may be insufficient, causing flaky failures.

- **Recommendation**: Rewrite both tests for proper virtual-time concurrency testing:
  1. Inject `TestDispatcher` into the service (or refactor to accept a dispatcher parameter).
  2. Use a `CompletableDeferred<Unit>` latch inside `coAnswers { latch.await() }` instead of `Thread.sleep`.
  3. Use `async { }` with the test dispatcher instead of `async(Dispatchers.IO)`.
  4. Explicitly match all relevant arguments: `analyzeDependencies(any(), any(), any())` or `analyzeDependencies(projectRoot = any(), force = any())`.
  5. Create a minimal-but-valid `AnalyzerResult` instead of `mockk(relaxed = true)`.

- **Verification Strategy**: After rewrite, both tests must: (a) complete in virtual time (near-zero wall clock), (b) verify `analyzeDependencies` called exactly once for same-key test, exactly twice for different-key test, (c) pass deterministically on any CI machine including heavily-loaded ones.

- **Resolution**: TODO

---

## 🟡 Major (Highly Recommended)

### 3. **[Security]** `[Intentional — Flawed]`: Shell Metacharacter Injection via `cmd /c mklink` with Incomplete Character Blocklist; Violates Invariant 4

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 338–369

  ```kotlin
  private fun createJunction(linkPath: Path, targetPath: Path) {
      if (linkPath.exists()) return
      val linkStr = linkPath.toAbsolutePath().normalize().toString()
      val targetStr = targetPath.toAbsolutePath().normalize().toString()

      validateSafeForShell(linkStr, "link path")
      validateSafeForShell(targetStr, "target path")

      if (linkStr.length > 200) {
          logger.warn("Junction path may approach Windows MAX_PATH limit (${linkStr.length} chars): $linkStr")
      }

      val process = ProcessBuilder("cmd", "/c", "mklink", "/J", linkStr, targetStr)  // L351
          .redirectErrorStream(true)
          .start()

      val exitCode = process.waitFor()
      if (exitCode != 0) {
          val error = process.inputStream.bufferedReader().readText()
          if (!linkPath.exists()) {
              throw RuntimeException("Failed to create junction $linkStr -> $targetStr: $error")
          }
      }
  }

  private fun validateSafeForShell(path: String, context: String) {           // L364
      val dangerousChars = Regex("[&|<>^%!]")
      require(!dangerousChars.containsMatchIn(path)) {
          "Unsafe characters in $context: '$path'. This may indicate a malicious dependency identifier."
      }
  }
  ```

- **Root Cause & Flaw**: Two overlapping issues:
  1. **Blocklist is incomplete for `cmd.exe`**: The regex `[&|<>^%!]` catches common metacharacters but misses `"`, `(`, `)`, and newline characters — all dangerous in `cmd.exe`.
  2. **Violates Invariant 4**: The project constitution states: *"Never use `ProcessBuilder` with `cmd.exe`, `sh`, or any interpreter for filesystem operations."* This code fires up `cmd.exe` for every junction creation.

  A dependency identifier containing `"` could break quoting boundaries and enable argument injection. While identifiers are pre-validated at lines 103–116, the defense does not fail closed — a single missed character opens the injection vector.

- **Pragmatic Rationale**: A compromised upstream package registry returning a crafted package name could execute arbitrary commands on the MCP server host with the user's privileges.

- **Recommendation**: Eliminate the shell invocation entirely. Two alternatives:
  1. **Use JNA/Kernel32**: Call `CreateSymbolicLinkW` directly with `SYMBOLIC_LINK_FLAG_DIRECTORY` (0x1). Eliminates the shell boundary entirely. Requires no admin privileges from Windows 10 v1703+ when Developer Mode is enabled.
  2. **If JNA is not feasible**: Switch to `cmd /d /c mklink /J ...` (adds `/d` to disable AutoRun entries) AND use an **allowlist** (`^[a-zA-Z0-9._\- \[\]]+$`) instead of the blocklist. Known-safe characters for dependency identifiers are enumerable; dangerous ones are not.

  The allowlist approach is **strongly preferred** over blocklist.

- **Verification Strategy**:
  1. `SourcesViewServiceTest.testRejectsShellMetacharactersInDependencyName()` must still pass.
  2. New test cases for `"`, `(`, `)`, embedded newline — all must reject with `IllegalArgumentException`.
  3. On Windows, `testUuidKeyedViewDirectoryCreation()` must still create junctions and make linked files accessible.

- **Resolution**: TODO

---

### 4. **[Security]** `[Mistake]`: Untrusted Manifest `projectRoot` Fed Directly to `Path.of()` in `findViewOnDisk()` — UNC Path DoS / Credential Leak

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 282–304

  ```kotlin
  for (dir in viewDirs) {
      val manifestFile = dir.resolve("manifest.json")
      if (!manifestFile.exists()) continue

      val manifest = runCatching {
          json.decodeFromString(ViewManifest.serializer(), manifestFile.readText())
      }.getOrNull() ?: continue

      if (runCatching {
          Path.of(manifest.projectRoot)                                // L290 ← UNTRUSTED INPUT
              .toAbsolutePath().normalize().toRealPath().toString()
      }.getOrNull() == normalizedRoot) {
          // ... trust manifest contents for depHash, dependencies, etc.
      }
  }
  ```

- **Root Cause & Flaw**: `manifest.projectRoot` is deserialized from a JSON file on disk — a file that could be written by any process with write access to `cache/views/` or by a prior buggy server run. The code calls `Path.of(manifest.projectRoot)` without any validation of the string content. Although wrapped in `runCatching`, the `Path.of()` call can trigger:
  - UNC path resolution (`\\malicious-server\share`) — a network call with potential timeouts and NTLM hash leakage (classic Windows credential capture).
  - Very long strings causing excessive memory allocation or stack overflow during path normalization.

  The `check_dependencies` MCP tool is annotated as `readOnlyHint = true` — users expect it to be fast and side-effect-free. A planted manifest with a UNC path could cause `check()` to hang for 30+ seconds while the OS attempts SMB resolution.

- **Pragmatic Rationale**: DoS via UNC timeout renders the `check()` tool unusable. Credential leakage via NTLM hash in UNC requests is a well-known Windows attack vector.

- **Recommendation**: Validate `manifest.projectRoot` before calling `Path.of()`:
  1. Reject UNC paths (starting with `\\`).
  2. Reject paths that don't match local absolute format (drive letter on Windows, `/` root on Unix).
  3. Apply a reasonable length limit (e.g., 4096 characters).
  
  ```kotlin
  val projectRootStr = manifest.projectRoot
  require(projectRootStr.length <= 4096) { "manifest projectRoot too long" }
  require(!projectRootStr.startsWith("\\\\")) { "UNC paths not allowed" }
  require(IS_WINDOWS && projectRootStr.matches(Regex("^[A-Za-z]:\\\\.*")) ||
          !IS_WINDOWS && projectRootStr.startsWith("/")) {
      "projectRoot must be an absolute local path: $projectRootStr"
  }
  ```

- **Verification Strategy**: 
  1. Create a manifest with `projectRoot = "\\\\192.168.1.100\\share\\project"`, call `check()`, verify it returns `viewExists = false` without hanging.
  2. Manifest with legitimate `C:\\path` still works and matches correctly.
  3. Manifest with 100KB `projectRoot` — `check()` returns safely without OOM.

- **Resolution**: TODO

---

### 5. **[Risk]** `[Mistake]`: `createDirectories()` Calls Placed Outside `try-catch` — Orphaned View Directories on Disk

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 95–97 and 148–153

  ```kotlin
  95:│    val sessionId = UUID.randomUUID().toString()
  96:│    val baseDir = env.viewsDir.resolve(sessionId).also { it.createDirectories() }  // ← OUTSIDE try
  97:│    val sourcesDir = baseDir.resolve("sources").also { it.createDirectories() }    // ← OUTSIDE try
       ...
  101:│    try {
  102:│        // Pre-validate all dependency identifiers before processing
       ...
  148:│    } catch (e: Exception) {
  149:│        if (baseDir.exists()) baseDir.deleteRecursively()
  150:│        throw e
  151:│    }
  ```

- **Root Cause & Flaw**: Both `createDirectories()` execute during variable assignment (lines 96–97), which is **outside** the `try` block that starts on line 101. If `sourcesDir.createDirectories()` (line 97) throws — e.g., a `FileSystemException` due to path length on Windows — the exception propagates **without** being caught. The `baseDir` created on line 96 is **leaked**: orphaned empty directory, never cleaned up, never pruned.

  **Violates Invariant 6**: *"Every `createDirectories()` preceding fallible operations MUST have corresponding `deleteRecursively()` cleanup in `catch` or `finally` blocks."*

- **Pragmatic Rationale**: On Windows with MAX_PATH concerns, junction creation failures are realistic. Orphaned directories accumulate unbounded with no cleanup mechanism (the cleanup service relies on `manifest.json`, which was never written for these directories).

- **Recommendation**: Move both `createDirectories()` inside the `try` block:
  ```kotlin
  val sessionId = UUID.randomUUID().toString()
  val baseDir = env.viewsDir.resolve(sessionId)
  val sourcesDir = baseDir.resolve("sources")
  try {
      baseDir.createDirectories()
      sourcesDir.createDirectories()
      // ... rest of logic
  } catch (e: Exception) {
      if (baseDir.exists()) baseDir.deleteRecursively()
      throw e
  }
  ```

- **Verification Strategy**: Write a test that throws from `createDirectories()` (e.g., pre-create `sourcesDir` as a file) and assert no orphaned directories remain. `testSyncCleansUpOnAnalysisFailure` does NOT cover this path.

- **Resolution**: TODO

---

### 6. **[Risk]** `[Mistake]`: `parallelism` Parameter Does Not Limit I/O Concurrency — All Dependencies Downloaded Simultaneously

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 46–47 and 161–196

  ```kotlin
  46:│    private val parallelism: Int = System.getenv("SOURCES_VIEW_PARALLELISM")?.toIntOrNull()
  47:│        ?: Runtime.getRuntime().availableProcessors()
        ...
  161:│) = coroutineScope {
  162:│    packages.chunked(parallelism.coerceAtLeast(1)).flatMap { chunk ->
  163:│        chunk.map { pkg ->
  164:│            async(Dispatchers.IO) {
  165:│                try {
  166:│                    val casPath = downloader.downloadSources(pkg, forceRedownload = redownload)
        ...
  195:│    }.awaitAll().filterNotNull()
  196:│}
  ```

- **Root Cause & Flaw**: `chunked(N)` splits the list into groups of size N. Each chunk's `map { async { ... } }` launches ALL N asyncs per chunk. `flatMap` flattens across all chunks into a single list. `awaitAll()` awaits **every async simultaneously**. The `parallelism` value controls chunk *size* only — it is not a concurrency limiter.

  Example: 500 packages, `parallelism = 64` → `chunked(64)` creates ~8 chunks. Each chunk launches 64 coroutines. `flatMap` flattens to 500 asyncs. **500 concurrent I/O operations**, not 64. The env var `SOURCES_VIEW_PARALLELISM` is a **dead control** — users who set it will not get the expected limit.

- **Pragmatic Rationale**: On a large dependency graph (200+ transitive deps), this launches 200+ simultaneous network connections and file writes. Can exhaust file descriptors, trigger rate-limiting from Maven Central/NPM registries, or cause `OutOfMemoryError`. Users relying on this env var for resource control are silently unprotected.

- **Recommendation**: Replace with a proper concurrency limiter:
  - **Semaphore**: `val semaphore = Semaphore(parallelism)`; each `async` acquires a permit before downloading.
  - **Flow approach**: `packages.asFlow().flatMapMerge(concurrency = parallelism) { flow { emit(download(it)) } }.toList()`
  
  `kotlinx.coroutines` is already on the classpath — no new dependencies needed.

- **Verification Strategy**: Write a test with 20 mock packages and `parallelism = 2`. Use `AtomicInteger` to track concurrent `downloadSources` invocations; assert the count never exceeds 2. Verify the test FAILS with the current implementation.

- **Resolution**: TODO

---

### 7. **[Architecture]** `[Mistake]`: `view-index.json` Has Scope Collision Bug — Maps `projectRoot → sessionId` but Ignores Scopes

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`

  In-memory cache correctly keys by both `projectRoot` and `scopes` (line 43):
  ```kotlin
  private val viewCache = ConcurrentHashMap<ViewCacheKey, SessionView>()
  ```

  But `view-index.json` is a flat `Map<String, String>` (lines 142–145):
  ```kotlin
  val normalizedRoot = projectRoot.toAbsolutePath().normalize().toString()
  val index = readViewIndex()
  index[normalizedRoot] = sessionId       // scopes IGNORED
  writeViewIndex(index)
  ```

  And `findViewOnDisk()` reads only by root (lines 254–257):
  ```kotlin
  val index = readViewIndex()
  val sessionIdFromIndex = index[projectRoot.toAbsolutePath().normalize().toString()]
  ```

  **Scenario**: `sync(root, scopes={"compile"})` → session "abc" → index["/project"] = "abc". Then `sync(root, scopes={"test"})` → session "def" → index["/project"] = "def" (OVERWRITES). Now `check(root)` finds session "def" (test-scope only). The compile-scope view is unreachable via the index.

- **Pragmatic Rationale**: Any agent that uses different scope filters for the same project gets wrong staleness results. Since views have a 24-hour lifespan, stale scope-filtered views could be reported as current or vice versa.

- **Compound Concern**: Additional issues with `view-index.json`:
  - `readViewIndex()` reads without file locking, while `writeViewIndex()` uses `FileLockManager.withLock()`. A crash during `writeText` leaves corrupted JSON, causing **all** index entries to be lost (not just the one being added). Readers see `runCatching { ... }.getOrDefault(mutableMapOf())` — silently returning empty.
  - No integrity protection (no HMAC, no checksum, no signature).

- **Recommendation**: Either:
  1. **Preferred**: Change the index key to include scopes (`root+scopes→sessionId`), update `check()` to accept an optional `scopes` parameter.
  2. **Simplest**: Remove `view-index.json` entirely and rely solely on the disk scan (lines 276–304) which already works correctly. Add the index later if profiling shows it's needed.
  3. **Integrity**: At minimum, use atomic file replacement (`Files.move` with `ATOMIC_MOVE` + `REPLACE_EXISTING`) for index writes to prevent partial-write corruption.

- **Verification Strategy**: Test: `sync(root, scopes={"compile"})`, then `sync(root, scopes={"test"})`, then `check(root)`. Both scope-filtered views must be independently discoverable.

- **Resolution**: TODO

---

### 8. **[Architecture + Clean Code]** `[Intentional — Flawed]`: `SourcesViewService` Is an Emerging God Object — 433 Lines, 5 Distinct Responsibilities, Direct ORT Coupling

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 32–432 (433 lines total)

  A single class contains:
  1. **Pipeline orchestration** (what to call, in what order) — the `sync()` method body (93 lines, lines 62–155)
  2. **Scope filtering** with ORT-qualified suffixes (lines 82–93, dense nested lambdas)
  3. **CAS download orchestration** with parallelism (lines 157–196)
  4. **Shell interaction** — junction creation via `cmd /c mklink` (lines 338–362)
  5. **Shell metacharacter validation** (lines 364–369)
  6. **Manifest serialization** and I/O (lines 130–131)
  7. **view-index management** (lines 371–385)
  8. **Staleness checking** and on-disk scanning (lines 205–304)
  9. **Concurrency management** (lines 43–44, 76–77)

  Additionally, the class imports from 8 different ORT packages (`Analyzer`, `PackageManagerFactory`, `AnalyzerResult`, `Identifier`, `Package`, `Scope`, `AnalyzerConfiguration`, `RepositoryConfiguration`) — directly bypassing the `DependencyAnalyzer` facade that was created to isolate the system from ORT.

- **Root Cause & Flaw**: The design's Decision 2 ("Service Owns Analysis Pipeline") is sound as a goal, but the current implementation pushes everything into a single class rather than a service layer with delegated responsibilities. The shell interaction is particularly concerning — `ProcessBuilder("cmd", "/c", "mklink", "/J", ...)` is called from within the orchestration layer, mixing high-level pipeline logic with platform-specific shell commands. `computeDepHash()` (lines 411–421) duplicates `DependencyAnalyzer`'s ORT setup entirely.

- **Pragmatic Rationale**: As normalization, multi-project views, and caching strategies are added, this class will continue to grow, eventually becoming unmaintainable.

- **Recommendation**:
  1. Extract `filterPackagesByScopes()` and `validateDependencyIdentifiers()` as private helper methods.
  2. Extract a `PlatformLinkService` class — encapsulates Windows junction vs. Unix symlink logic (lines 330–369), removing `cmd.exe` from the orchestration layer.
  3. Move `computeDepHash()` into `DependencyAnalyzer` (it already has the ORT setup for `findManagedFiles`).
  4. Split `findViewOnDisk()` into `findViewFromIndex()` and `findViewByProjectRootScan()` helpers to flatten 4-level nesting.
  5. After refactoring, `SourcesViewService` should be under ~200 lines with 3-4 clear public methods.

- **Verification Strategy**: After refactoring, all existing `SourcesViewServiceTest` tests pass unchanged. `SourcesViewService` imports from at most 3 ORT packages (down from 8). Shell command construction exists only in `PlatformLinkService`.

- **Resolution**: TODO

---

### 9. **[Clean Code + Risk]** `[Mistake]`: `StalenessCheck` Construction Inconsistent Across 7 Call Sites — `""` vs `null` for "No View" Sentinel

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`

  7 construction sites with inconsistent "no data" values. Three representative examples:

  ```kotlin
  // Line 207: computeDepHash failed → empty string + zero
  StalenessCheck(viewExists = false, depHash = "", dependencyCount = 0, ...)
  
  // Line 229: no view found → null sentinels
  StalenessCheck(viewExists = false, depHash = null, dependencyCount = null, ...)
  
  // Line 277: findViewOnDisk fail → empty string + zero again
  StalenessCheck(viewExists = false, depHash = "", dependencyCount = 0, ...)
  ```

  `""` renders in MCP output as `depHash: ` (blank). `null` renders as `depHash: N/A`. An LLM consuming this may interpret the blank as a bug.

- **Pragmatic Rationale**: Any downstream code consuming `StalenessCheck` must check BOTH `viewExists` AND nullability of `depHash`/`dependencyCount`. These are semantically the same state ("no view exists") with different representations.

- **Recommendation**: Normalize all "no view" paths to use `null` for both `depHash` and `dependencyCount`. Consider a private helper `staleCheck(viewExists, currentDepHash, manifest?, age?)` to collapse 7 construction sites into 2-3 calls.

- **Verification Strategy**: A `StalenessCheck` with `viewExists = false` must always have `depHash = null` and `dependencyCount = null`. `ViewModelsTest` should verify this invariant.

- **Resolution**: TODO

---

### 10. **[Test]** `[Mistake]`: Multiple Tests Have Zero Meaningful Assertions

- **Code Context**:

  `src/test/kotlin/dev/rnett/sources/mcp/ViewCleanupServiceTest.kt`, lines 51–58:
  ```kotlin
  @Test
  fun testCleanupWithNoViewsDirectory() {
      val env = SourcesMcpEnvironment(tempDir)
      env.viewsDir.toFile().deleteRecursively()
      val service = ViewCleanupService(env, maxAge = Duration.ofSeconds(1))
      service.cleanup()
      // ❌ No assertions — passes if it doesn't throw
  }
  ```

  `src/test/kotlin/dev/rnett/sources/mcp/ViewCleanupServiceTest.kt`, lines 118–128:
  ```kotlin
  @Test
  fun testStopPreventsFurtherCleanup() {
      val service = ViewCleanupService(env, maxAge = Duration.ofHours(24))
      service.stop()
      assertTrue(true)   // ❌ Always passes — dead assertion
  }
  ```

  `src/test/kotlin/dev/rnett/sources/mcp/ViewModelsTest.kt`, lines 57-147 — three tests (`testStalenessCheckFreshViewExists`, `testStalenessCheckNoViewExists`, `testStalenessCheckStaleViewExists`) only test that Kotlin's `val` and Java's `==` work — they construct data classes and assert properties match.

- **Root Cause & Flaw**: Tests that always pass (or pass for reasons unrelated to application logic) create CI time cost without defect-detection value. Per Test Design Constitution: *"Never Test Trivial Code: Do not test getters, setters, simple POJOs, or trivial delegation."*

- **Recommendation**:
  1. `testCleanupWithNoViewsDirectory`: Add `assertFalse(viewsDir.exists(), "cleanup should not recreate deleted views directory")`.
  2. `testStopPreventsFurtherCleanup`: Either rewrite to actually verify stop behavior (start service, create old view, stop, wait, verify view still exists), or rename to `testStopNoOpOnUnstartedService` and keep as crash-safety test.
  3. Delete the 3 `StalenessCheck` constructor tests — correctness is verified implicitly by `SourcesViewServiceTest` tests that assert `StalenessCheck` values returned by the service.

- **Verification Strategy**: Search for other assertion-less tests in the test suite. No test should pass solely because it "doesn't throw" without explicit verification.

- **Resolution**: TODO

---

### 11. **[Architecture]** `[Intentional — Flawed]`: Boolean Flags (`fresh`/`redownload`) Should Be a Sealed Class or Enum

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 62–67

  ```kotlin
  suspend fun sync(
      projectRoot: Path,
      scopes: Set<String> = emptySet(),
      fresh: Boolean = false,
      redownload: Boolean = false
  ): SessionView
  ```

- **Root Cause & Flaw**: The design defines 4 distinct modes, but the API uses 2 independent booleans with 4 combinations. Issues:
  1. Semantics of `fresh=true, redownload=true` rely on implementation details (line 70: either flag evicts cache).
  2. Adding a third mode would explode into 8 combinations.
  3. `fresh` means "re-analyze" here but "is current" in `StalenessCheck.fresh` — same term, different meanings in the same module.

- **Pragmatic Rationale**: Boolean blindness is a well-known anti-pattern. Callers must pass unnamed `true`/`false` where intent isn't self-documenting.

- **Recommendation**: Replace with a sealed class or enum:
  ```kotlin
  enum class SyncMode {
      CACHED, REFRESH_ANALYSIS, REDOWNLOAD_SOURCES, FULL_REFRESH
  }
  ```
  The MCP tool layer can map the existing parameters to this enum internally.

- **Verification Strategy**: `sync()` must have one mode parameter. All 4 mode combinations must be testable by name. No test should need a comment explaining which boolean combination it tests.

- **Resolution**: TODO

---

### 12. **[Security]** `[Mistake]`: Missing Input Validation on User-Supplied `projectRoot` in MCP Tool Handlers; Raw Exceptions Leak Filesystem Paths

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SyncCheckTools.kt`, lines 101–108, 139–146

  ```kotlin
  val projectRootContent = args["projectRoot"]?.jsonPrimitive?.content
  if (projectRootContent == null) {
      return CallToolResult(isError = true, ...)
  }
  val projectRoot = Path.of(projectRootContent).absolute()      // No length/blank/format validation
  ```

  And lines 114–119: `viewService.sync(...)` is called without `try-catch`. If `sync()` throws (e.g., `IllegalArgumentException` from `require()` in `DependencyAnalyzer`), the exception propagates as a raw error to the MCP framework — not as a structured `CallToolResult(isError = true, ...)` response. Violates **Invariant 12**.

- **Root Cause & Flaw**: 
  1. Empty strings pass the null check but produce `Path.of("")` resolving to the JVM working directory.
  2. Extremely long strings (megabytes) create memory pressure.
  3. Uncaught exceptions include full stack traces with absolute filesystem paths in MCP responses.
  4. No rate limiting — a malicious MCP client could call tools in a loop with arbitrary paths.

- **Recommendation**:
  1. Add validation: reject blank strings, enforce 4096-char length limit, `toAbsolutePath().normalize()`.
  2. Wrap `viewService.sync()` and `viewService.check()` in `try-catch` converting exceptions to `CallToolResult(isError = true, content = [TextContent("...")])` with sanitized messages.
  3. Extract duplicate `projectRoot` validation into a shared private helper (`extractProjectRoot(args)`).

- **Verification Strategy**: Tests invoking handlers with: empty string, whitespace-only, 100KB string — all return `isError = true` without throwing. Non-existent path → `isError = true` with sanitized message.

- **Resolution**: TODO

---

### 13. **[Clean Code]** `[Intentional — Flawed]`: `ViewCleanupService.readTimestamp()` Manually Parses JSON Instead of Using `ViewManifest` Serializer

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/ViewCleanupService.kt`, lines 91–103

  ```kotlin
  private fun readTimestamp(viewDir: Path): Instant? {
      val manifestFile = viewDir.resolve("manifest.json")
      if (manifestFile.exists()) {
          try {
              val element = json.parseToJsonElement(manifestFile.readText())
              val timestampValue = element.jsonObject["timestamp"]?.jsonPrimitive?.content
              if (timestampValue != null) return Instant.parse(timestampValue)
          } catch (e: Exception) { ... }
      }
      // fallback to lastModified...
  ```

- **Root Cause & Flaw**: Constructs its own `Json` instance and navigates the tree manually, while `ViewManifest` is `@Serializable` and `SourcesViewService` writes manifests using `json.encodeToString(ViewManifest.serializer(), manifest)`. Creates a fragile coupling — if field names or structure change, the cleanup service silently breaks and all views revert to `lastModified`-based cleanup.

- **Pragmatic Rationale**: Two copies of JSON schema knowledge to keep in sync. Manual field access is more error-prone than typed deserialization.

- **Recommendation**: Deserialize to `ViewManifest`:
  ```kotlin
  val manifest = json.decodeFromString(ViewManifest.serializer(), manifestFile.readText())
  return Instant.parse(manifest.timestamp)
  ```

- **Verification Strategy**: Test that adding new fields to `ViewManifest` does not break timestamp extraction. Verify timestamp matches when serialized through `ViewManifest`.

- **Resolution**: TODO

---

### 14. **[Test]** `[Mistake]`: Spec Coverage Gaps — 4 Documented Scenarios Are Untested; No Full Pipeline Integration Test

- **Code Context**: Cross-referencing `openspec/changes/view-directory-management/specs/` against tests:

  | Spec Scenario | Location | Tested? |
  |---|---|---|
  | Both `fresh=true` and `redownload=true` | view-directory-creation:58-61 | ❌ |
  | `fresh=true` but analysis cache missing | view-directory-creation:63-67 | ❌ |
  | `check` with view but no analysis cache | view-directory-creation:94-98 | ❌ |
  | `check` is cheap (no downloads/analysis) | sync-check-tools:84-87 | ❌ Not explicitly verified |
  | Full pipeline end-to-end | n/a | ❌ No integration test |

- **Root Cause & Flaw**: Explicitly specified behaviors with no test verification. The `fresh && redownload` case tests an important combination. The "cache missing" cases test graceful degradation. Without tests, these code paths have no guarantee of correctness.

- **Recommendation**: Add targeted tests:
  1. `testBothFreshAndRedownload`: assert `analyzeDependencies` called with `force=true` AND `downloadSources` called with `forceRedownload=true`.
  2. `testFreshWhenCacheMissing`: mock cache returns null, call `sync(fresh=true)`, assert analysis re-runs.
  3. `testCheckWithNoAnalysisCache`: verify `check()` returns meaningful data even when analysis cache is unavailable.
  4. `testCheckDoesNotDownloadOrAnalyze`: verify with mocks that `check()` never calls `analyzeDependencies` or `downloadSources`.
  5. Add a (potentially slow) integration smoke test: real test project → real `sync()` → assert view directory populated, `manifest.json` valid, symlinks/junctions working, `check()` returns fresh.

- **Verification Strategy**: Each new test must match the spec's acceptance criteria exactly.

- **Resolution**: TODO

---

## 🔵 Minor (Polishing / Idioms / Future-Proofing)

### 15. **[Clean Code]** `[Intentional — Flawed]`: `createJunction()` Silently Treats Broken Junctions as Success on Non-Zero Exit Code

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 338–361

  ```kotlin
  val exitCode = process.waitFor()
  if (exitCode != 0) {
      val error = process.inputStream.bufferedReader().readText()
      if (!linkPath.exists()) {
          throw RuntimeException(...)
      }
      // ❌ Else: exit code != 0 but path exists → silently returns. No warning.
  }
  ```

- **Root Cause & Flaw**: If `mklink /J` fails but the path exists (from concurrent creation or a partial creation), the method returns silently. The junction may point to the wrong target.

- **Recommendation**: Log a warning when exit code != 0 but the path exists.

- **Resolution**: TODO

---

### 16. **[Clean Code]** Magic Number 200 for Junction Warning — Use Named Constant Referencing 260

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 347–349

  ```kotlin
  if (linkStr.length > 200) {
      logger.warn("Junction path may approach Windows MAX_PATH limit (${linkStr.length} chars): $linkStr")
  }
  ```

  Windows `MAX_PATH` is 260. The threshold of 200 is unexplained. The design doc mentions 260 explicitly.

- **Recommendation**: Define named constants (`WINDOWS_MAX_PATH = 260`, `JUNCTION_PATH_WARN_THRESHOLD = 200`) in a `companion object`.

- **Resolution**: TODO

---

### 17. **[Clean Code + Architecture]** `[Mistake]`: `toFile()` / `java.io.File` API Usage Violates Invariant 8 — Inconsistent File I/O

Multiple locations flagged:
- `SourcesViewService.kt:416` — `projectRoot.toFile()` in `computeDepHash()` (ORT API constraint; document as explicit exception).
- `DependencySourcesDownloader.kt:109,113,116` — `dir.toFile().listFiles()`, `subDir.delete()` (pre-existing, should migrate to `kotlin.io.path`).
- `SourcesViewServiceTest.kt:147` — `linkPath.toFile().readText()` (replace with `linkPath.readText()`).
- `SourcesViewServiceIntegrationTest.kt:42,117` — `manifestFile.toFile().readText()` (replace with `manifestFile.readText()`).

- **Recommendation**: Replace all test-level `toFile()` I/O with `kotlin.io.path` extensions. For `computeDepHash()`, add a comment documenting the ORT API constraint as an explicit exception to Invariant 8.

- **Resolution**: TODO

---

### 18. **[Clean Code + Architecture]** `[Mistake]`: `computeDepHash()` Duplicates ORT Analyzer Setup from `DependencyAnalyzer`

- **Code Context**: `SourcesViewService.kt` lines 411–421 vs `DependencyAnalyzer.kt` lines 27–33 — structurally identical ORT construction (both create `AnalyzerConfiguration`, build `Analyzer`, call `findManagedFiles` with same "Unmanaged" package manager filter).

- **Recommendation**: After moving `computeDepHash()` into `DependencyAnalyzer` (Finding 5), this resolves naturally. Otherwise, extract the ORT instantiation into a shared factory method.

- **Resolution**: TODO (resolved by Finding 5)

---

### 19. **[Clean Code]** Test Boilerplate Repeated Across 14+ Test Methods

- **Code Context**: `src/test/kotlin/dev/rnett/sources/mcp/SourcesViewServiceTest.kt` — every `runTest` method repeats 5-7 lines of setup (create package, mock analyzer, create CAS dir, mock downloader).

- **Recommendation**: Extract a `setupSyncScenario(pkgName, vararg scopes)` helper.

- **Resolution**: TODO

---

### 20. **[Architecture]** ViewCache Eviction TOCTOU Race Outside Mutex

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 70–77

  ```kotlin
  if (fresh || redownload) viewCache.remove(cacheKey)  // L71 — OUTSIDE lock
  getValidCachedView(cacheKey)?.let { return it }       // L74
  val mutex = syncMutexes.computeIfAbsent(cacheKey) { Mutex() }  // L76
  return mutex.withLock { ... }                         // L77
  ```

  `viewCache.remove()` at L71 is outside the mutex lock. Other threads may call `getValidCachedView()` before the eviction. Works in practice due to double-check inside the lock, but the pattern is non-obvious.

- **Recommendation**: Document with a comment, or move eviction inside the lock.

- **Resolution**: TODO

---

### 21. **[Architecture]** Default Constructors with Side Effects (Environment Reads, Directory Creation)

- **Code Context**: `SourcesViewService` default constructor creates `SourcesMcpEnvironment.fromEnv()` which reads env vars and creates directories. `SyncCheckTools` default constructor creates the entire dependency tree silently.

- **Recommendation (Minor)**: Remove unused `cacheService` parameter from `SourcesViewService` constructor (never referenced in the class body). Consider a lightweight explicit wiring pattern.

- **Resolution**: TODO

---

### 22. **[Test]** `testNewViewsAreNotDeleted` Timing-Fragile — `maxAge` of 10 Seconds

- **Code Context**: `src/test/kotlin/dev/rnett/sources/mcp/ViewCleanupServiceTest.kt`, line 42

  ```kotlin
  val service = ViewCleanupService(env, maxAge = Duration.ofSeconds(10))
  ```

  Manifest timestamp = `Instant.now()`. On a slow CI machine or under GC pause, the 10-second window could be exhausted, causing spurious failure.

- **Recommendation**: Increase `maxAge` to at least `Duration.ofMinutes(5)`.

- **Resolution**: TODO

---

### 23. **[Test]** `testFreshModeEvictsCacheAndReanalyzes` Only Checks Hash Inequality, Not Cause

- **Code Context**: `src/test/kotlin/dev/rnett/sources/mcp/SourcesViewServiceTest.kt`, line 168

  ```kotlin
  assertTrue(view1.manifest.depHash != view2.manifest.depHash, ...)
  ```

  Doesn't verify that the hash changed *because* of the file write. If `computeDepHash` always returns the same value regardless, the test might still pass due to the `verify(exactly=2)` call-count checks.

- **Recommendation**: Compute the pre-change hash explicitly, write the file, compute again, assert inequality by design.

- **Resolution**: TODO

---

### 24. **[Test]** Concurrency Tests Inconsistently Pass `normalizationService` Parameter

- **Code Context**: `SourcesViewServiceConcurrencyTest.kt` omits `normalizationService` (uses default) while `SourcesViewServiceTest.kt` explicitly passes `NoOpNormalizationService()`. Identical result, inconsistent pattern.

- **Recommendation**: Consistently pass `normalizationService = NoOpNormalizationService()` in both test files.

- **Resolution**: TODO

---

## ❓ Open Questions for the Author

None. All 24 findings are self-contained and actionable without user input. The architecture choice (A1) and design decisions are sound — the issues are implementation-level and resolvable from the code alone.

---

## 💡 Proposed Invariants/Rules for `AGENTS.md`

These 5 new invariants should be added to the project constitution to prevent regression in similar future changes:

1. **Server Bootstrap Rule**: Any new MCP tool class (like `SyncCheckTools`) MUST be accompanied by either (a) a server bootstrap that wires it, or (b) an integration test that invokes it through the MCP protocol. No "tool exists but nobody calls `registerAll`."

2. **Concurrency Limiter Verification**: Any method performing batch I/O with a user-visible `parallelism` parameter MUST verify that the limit is actually enforced (Semaphore or `flatMapMerge`). A test must verify the limit is honored — `chunked(N)` alone is insufficient.

3. **`createDirectories()` Try-Scope Rule**: All `createDirectories()` calls that are part of multi-step setup (where earlier steps must be cleaned up if later steps fail) MUST be inside the corresponding `try-catch-finally` block, not in variable initializers or `.also{}`.

4. **Scope-Keyed Indexes Rule**: Any cross-process index that keys by project root MUST also include scope/session parameters when multiple scoped views can coexist. The index schema must match the in-memory cache key schema.

5. **Manifest Deserialization Guard**: Any data deserialized from `manifest.json` or similar on-disk artifacts that controls filesystem operations (`Path.of()`, `Files.list()` etc.) MUST be validated for safety before use. Specifically: reject UNC paths, enforce reasonable length limits, validate that strings conform to expected formats (absolute local paths only).

Additionally, **Invariant 10** (Test Dispatcher Hygiene) should be promoted/enforced via linting or a CI check — the current concurrency test violation shows that documentation alone is insufficient.

---

## ✅ Positive Observations

1. **Architecture choice A1 is well-reasoned** — External View Directory, tool-call-is-sync-point. Honest about what the agent must do. Design doc traces 15 decisions clearly.
2. **Two-tool Git-like model** (`check_dependencies` + `sync_dependencies`) is clean and intuitive.
3. **`ViewCacheKey` path normalization** (absolute, normalize, `toRealPath()`, trim trailing slashes) is thorough.
4. **Concurrency model** (per-key `Mutex` + double-check + `ConcurrentHashMap`) is the correct pattern for this workload.
5. **`NormalizationService` no-op placeholder** is the correct deferral pattern — establishes a seam now, implements later.
6. **Append-only CAS** eliminates CAS-level cleanup and simplifies lifecycle reasoning.
7. **Platform-aware link creation** (Windows/Mac/Linux detection) is correctly gated.
8. **MCP tool annotations** are correctly set (`readOnlyHint`, `destructiveHint`, `idempotentHint`) — Invariant 11 is satisfied.
9. **`StalenessCheck` null-safety** — using nullable fields for "no data" cases is correct Kotlin idiom.
10. **`SourcesViewServiceTest`** — 22 tests with good edge-case coverage, well-structured, descriptive test names.
11. **`ViewModelsTest`** — serialization round-trip tests and `ViewCacheKey` normalization tests are well-factored.
12. **`ViewCleanupServiceTest`** — handles deletion failures (locked files), invalid manifests, missing dir gracefully.
13. **Cleanup-on-failure** — `sync()` correctly deletes `baseDir` on exception (satisfies Invariant 6 intent, barring Finding 5).

---

## 📋 Review Status

- **Pass**: 2 (initial orchestration pass)
- **Total findings**: 24 (2 critical, 12 major, 10 minor)
- **Faceted reviews completed**: 5 of 8 (security, clean-code, architecture, risk, test returned substantive reports; logic, concurrency, idiom returned empty — their facets partially addressed by overlapping coverage)
- **Open questions remaining**: 0
- **Next step**: Author to review findings and issue instructions for remediation

---

*Report generated by `review_orchestrator` — 8-agent panel, 5 substantive reports synthesized*

