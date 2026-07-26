task_id: ses_1fa00fcf5ffeV3x8sbOFuWpdKe (for resuming to continue this task if needed)

<task_result>
Now I have all the data I need. Let me compile the complete resolution plan. All 35 findings are confirmed through code inspection, and this is the first review — so no `[RECURRING]` tags apply. Let me write the full plan.

---

# 🏛️ Review Resolution Plan — `reports/review-1.md`

**Target**: `view-directory-management` greenfield feature  
**Date**: 2026-05-07  
**Total findings**: 35 (5 Critical, 12 Major, 18 Minor)  
**Prior reports**: None — this is the first review; no `[RECURRING]` findings  

---

## Section 1: Validation Summary

| # | Severity | Title | Verdict | Notes |
|---|----------|-------|---------|-------|
| 1 | Critical | `fresh=true` Cannot Force ORT Re-Analysis | **Valid** | `DependencyAnalyzer` unconditionally returns disk-cached result; no `force` parameter |
| 2 | Critical | `redownload=true` Cannot Force CAS Re-download | **Valid** | `DependencySourcesDownloader` fast-path `targetDir.exists()` always true for previously downloaded pkgs |
| 3 | Critical | Scope Filtering Is Broken | **Valid** | ORT graph scopes are qualified; user input is unqualified; exact match always fails |
| 4 | Critical | Command Injection via `cmd.exe` | **Valid** | `ProcessBuilder("cmd", "/c", "mklink", ...)` interprets shell metacharacters in paths |
| 5 | Critical | Path Traversal via Unvalidated Identifiers | **Valid** | `Path.resolve()` does not normalize `..`; dependency identifiers are untrusted |
| 6 | Major | `SyncCheckTools`/`ViewCleanupService` Dead Code | **Valid** | Neither `registerAll()` nor `start()` has any call site |
| 7 | Major | `check()`/`ViewCacheKey.create()` Crash on Non-Existent Roots | **Valid** | `toRealPath()` throws `IOException` on non-existent paths |
| 8 | Major | Duplicated SHA-256 Hashing Logic | **Valid** | Identical copy-paste between `SourcesViewService.computeDepHash` and `DependencyCacheService.calculateCacheKey` |
| 9 | Major | Unused `cacheService` Field in `SourcesViewService` | **Valid** | `cacheService` parameter never referenced in any method body |
| 10 | Major | Concurrent Read/Write Race on Analysis Cache | **Valid** | `getCachedResult`/`saveResult` have zero locking; TOCTOU + partial-read risk |
| 11 | Major | `ViewCleanupService` Can Delete In-Use Views | **Valid** | Cleanup is unaware of in-memory `viewCache`; `getView()` has no existence validation |
| 12 | Major | No Path-Length Check for Windows MAX_PATH | **Valid** | Design Decision #15 mandates warnings at 200 chars; never implemented |
| 13 | Major | Orphaned Directories from Failed Sync | **Valid** | `baseDir`/`sourcesDir` created before fallible operations; no cleanup on failure |
| 14 | Major | Missing `toolAnnotations` on Both MCP Tools | **Valid** | Both set `toolAnnotations = null`; LLMs can't distinguish read-only from mutating ops |
| 15 | Major | `age` Field Returns Custom Format, Not ISO-8601 | **Valid** | Spec requires parseable ISO-8601; code produces "2d 3h 45m 12.345s" |
| 16 | Major | Error Handling Throws Raw Exceptions | **Valid** | `error("projectRoot is required")` produces `IllegalStateException`; no MCP `isError` response |
| 17 | Major | `check()` Disk Fallback O(n) | **Valid** | Scans + deserializes ALL manifests when in-memory cache misses |
| 18 | Minor | Redundant `equals()`/`hashCode()` on Data Class | **Valid** | Manual overrides produce identical semantics to auto-generated |
| 19 | Minor | Inconsistent `kotlin.io.path` vs `java.io.File` | **Valid** | Mixed APIs: `toFile().listFiles()`, `toFile().readText()`, etc. |
| 20 | Minor | Duplicated Scope-Collection Logic | **Valid** | Graph and project blocks in `extractDependenciesWithScopes` are structurally identical |
| 21 | Minor | `formatDuration` Manual Arithmetic | **Valid** | Can use `Duration.toComponents { ... }` |
| 22 | Minor | `isWindows` Per-Instance Instead of Once | **Valid** | Value never changes; belongs in `companion object` |
| 23 | Minor | `@JvmName` on Private Method | **Valid** | `@JvmName("syncDependenciesHandler")` on private suspend method is superfluous |
| 24 | Minor | Inconsistent Fully-Qualified `Path.of` | **Valid** | `java.nio.file.Path.of(...)` vs imported `Path.of(...)` |
| 25 | Minor | `sync()` Method Too Long | **Valid** | 96 lines, 6 responsibilities |
| 26 | Minor | `check()` Method Too Long | **Valid** | Three lookup strategies in one method |
| 27 | Minor | `NormalizationService` Zero Documentation | **Valid** | No KDoc on interface or implementation |
| 28 | Minor | `NormalizationService` Variable on Hot Path | **Valid** (micro-opt) | No-op call on every sync; interface design is correct per design doc |
| 29 | Minor | `Thread.sleep` in Coroutine Tests | **Valid** | Bypasses virtual time |
| 30 | Minor | `getView()` Untested | **Valid** | Zero test invocations |
| 31 | Minor | `ViewCacheKey` Create/Equals/HashCode Untested | **Valid** | Only ViewManifest and StalenessCheck are tested |
| 32 | Minor | `ViewCleanupService.start()`/`stop()` Untested | **Valid** | Only `cleanup()` tested directly |
| 33 | Minor | StalenessCheck Missing "Stale View" State Test | **Valid** | No test for `viewExists=true, fresh=false` |
| 34 | Minor | No Gradle Integration Test | **Valid** | No end-to-end Gradle pipeline test |
| 35 | Minor | `readTimestamp` Fallback Paths Uncovered | **Valid** | No tests for missing timestamp key or missing manifest file |

**Verdict**: All 35 findings are VALID and confirmed by code inspection. Zero invalid findings. Zero findings require user architectural decisions (all have clear technical fixes).

---

## Section 2: Invalid Findings

**None.** Every finding in the report is confirmed by inspecting the referenced source code. The reviewer's analysis, line numbers, code snippets, and root causes are accurate in all 35 cases.

---

## Section 3: Architectural Decisions Needed

**None.** All findings have straightforward technical fixes that don't require user-level design decisions. The one potential gray area — Finding #6 (server wiring) — is simply a matter of whether to wire now or later; the fix is unambiguous: either wire it or update the task list. I'll present the wire-it-now approach as the primary plan with explicit fallback instructions.

---

## Section 4: Spec/Doc Changes Required

These must be done **before or alongside** code changes:

| # | Finding | Spec/Doc Affected | Change |
|---|---------|-------------------|--------|
| 15 | `age` returns custom format | `openspec/changes/view-directory-management/specs/sync-check-tools/spec.md` line 62 | Update `age: String?` description — if switching to ISO-8601 per recommendation, the spec already says ISO-8601 so no change needed. **Decision**: Switch code to ISO-8601; spec is already correct. |
| 6 | Task list wiring | `openspec/changes/view-directory-management/tasks.md` items 5.1, 5.2 | If wiring is deferred: uncheck `[x]` → `[ ]`. If wired now: items remain `[x]`. |
| 12 | MAX_PATH check | `openspec/changes/view-directory-management/design.md` Decision #15 | Implementation gap — code doesn't do what the design says. No spec change needed; the design doc is already correct; code must catch up. |

---

## Section 5: Phase-Ordered Execution Plan

### Phase 1: Critical Security Fixes (Must ship before anything else)

#### Finding #4 — Command Injection via `cmd.exe` (SECURITY BLOCKER)

- **Target Finding**: #4 — Command Injection via `cmd.exe` in Junction Creation
- **Status**: N/A (first review)
- **Rationale & Goal**: `cmd.exe /c` concatenates arguments through the full command interpreter. Shell metacharacters (`&`, `|`, `>`, `<`, `^`, `%`) in dependency identifier components (from external, untrusted package manifests) are interpreted as command separators. A malicious dependency with a crafted name could execute arbitrary commands with the MCP server's privileges. **Goal**: Eliminate all shell invocation for filesystem operations.
- **General Context & Gotchas**: 
  - `Files.createSymbolicLink()` on Windows creates **symbolic links** (not junctions) and requires elevated privileges or Developer Mode. Junctions via `mklink /J` do NOT require privileges. This is why the original code used `mklink`.
  - However, `Files.createSymbolicLink()` on Windows since Java 13 has been improved. It still creates symbolic links, NOT junctions. Junctions and symlinks have different semantics on Windows.
  - JNA/Kernel32 approach requires adding a JNA dependency, which is a new transitive dependency.
  - **Critical gotcha**: Whatever replacement is chosen MUST produce the equivalent of `mklink /J` behavior (directory junction), NOT a regular symbolic link, to maintain the no-elevation-required property.
  - Actually, re-reading: on Windows, `Files.createSymbolicLink(path, target)` for a directory target creates a **directory symbolic link** (requires privileges). This is different from a junction. This is a significant semantic difference. However, for the use case here (just making CAS paths accessible under a view directory), either would functionally work IF the user has Developer Mode enabled or is running as admin. But the design doc specifically chose junctions to avoid the privilege requirement.
  - **Recommendation**: The safest approach is to use JNA's `Kernel32` to call `CreateJunction` — but that adds a dependency. Alternative: implement the junction creation using Java's `com.sun.jna` package or use `java.lang.ProcessBuilder` but with the `cmd /c` approach replaced by direct invocation of `mklink` with arguments passed through a properly escaped array, not string concatenation. Actually, `mklink` is a cmd.exe **internal command** (not a separate executable), so you CANNOT run it without `cmd /c`. This means junctions on Windows fundamentally require `cmd.exe`.
  - **Revised recommendation**: Instead of trying to parse/escape shell metacharacters (which is error-prone), validate that dependency identifiers contain no shell metacharacters (`&`, `|`, `>`, `<`, `^`, `%`, `!`) and reject them with a clear error if they do. This is a whitelist-based approach. OR use `java.nio.file.Files.createSymbolicLink()` with a fallback: on Windows, attempt Files.createSymbolicLink() first (works if Dev Mode is on), and only fall back to mklink with validated paths.
  - **Simplest safe fix**: Add a validation function `requireSafeForShell(path: Path)` that checks all path components for the shell metacharacter set `[&|<|>|^|%|!]` and throws a descriptive error before reaching the ProcessBuilder call. This prevents injection while preserving junction semantics.
- **Execution Steps**:
  1. Add a private function `validateSafePathComponent(path: Path, context: String)` in `SourcesViewService` that checks path components against `Regex("[&|<>^%!]")` and throws with a descriptive error message including the offending component and context.
  2. Call `validateSafePathComponent(linkStr, "link path")` and `validateSafePathComponent(targetStr, "target path")` in `createJunction()` BEFORE the `ProcessBuilder` call.
  3. Optionally, also validate `linkStr` and `targetStr` are absolute after normalization (defense in depth).
  4. Add a test with a dependency whose name/namespace/version contains shell metacharacters and verify it throws a descriptive error rather than executing commands.
- **Verification Strategy** (from review report + expanded):
  - Test that a dependency with cmd.exe metacharacters in its `name` (e.g., `test&del%20/F`) does NOT execute arbitrary commands — the service throws a descriptive `IllegalArgumentException` or similar.
  - Verify no `ProcessBuilder("cmd", "/c", ...)` calls execute with unsanitized user-controlled strings reaching shell interpretation.
  - Run: search for `ProcessBuilder("cmd"` to confirm only one call site exists and it is guarded by the validation.
- **Dependencies**: None (can be done independently)

#### Finding #5 — Path Traversal via Unvalidated Dependency Identifiers (SECURITY BLOCKER)

- **Target Finding**: #5 — Path Traversal via Unvalidated Dependency Identifiers
- **Status**: N/A (first review)
- **Rationale & Goal**: Java's `Path.resolve()` does NOT normalize `../` components. Dependency identifiers (`name`, `namespace`, `version`) are free-form strings from untrusted package manifests. A `namespace` of `../../evil-dir` would escape `sourcesDir`. Combined with Finding #4, a supply-chain attacker could write junctions anywhere on the filesystem. **Goal**: Prevent any path constructed from dependency identifiers from escaping `sourcesDir`.
- **General Context & Gotchas**:
  - `Path.resolve()` simply concatenates; `Path.normalize()` collapses `..` components.
  - `Path.startsWith()` checks the directory prefix — this is sufficient to validate no escape occurred.
  - Normalize BEFORE checking `startsWith()`.
  - The check should happen AFTER `linkDir.parent.createDirectories()` to catch both the directory creation AND the future link creation.
  - Also need to check that `ecosystem`, `namespace`, `name`, and `version` don't individually contain path separators (`/` or `\`) that could cause unexpected subdirectory structures. Actually, `resolve()` with embedded separators would still work but might create deeper directory structures than expected. Let's validate all four components don't contain path separators.
- **Execution Steps**:
  1. In `SourcesViewService.sync()`, after computing `linkDir` at line 107, add:
     ```kotlin
     val linkDir = sourcesDir.resolve("$ecosystem/$namespace/$name/$version").normalize()
     require(linkDir.startsWith(sourcesDir)) {
         "Dependency path escapes sources directory: ${linkDir.toAbsolutePath()}"
     }
     ```
  2. Also add component-level validation before building the path:
     ```kotlin
     for ((fieldName, value) in listOf("ecosystem" to ecosystem, "namespace" to namespace, "name" to name, "version" to version)) {
         require(!value.contains("/") && !value.contains("\\")) {
             "Dependency $fieldName contains path separator: '$value'"
         }
     }
     ```
  3. Write tests with mocked `Package` objects where:
     - `name = "../../escape-test"` → verify `sync()` throws.
     - `namespace = "..\\..\\escape"` → verify `sync()` throws.
     - `name = "sub/dir"` → verify `sync()` throws (component-level validation).
- **Verification Strategy** (from review report + expanded):
  - Test with mocked `Package(id = Identifier("Maven", "../../escape-test", "lib", "1.0"))` — verify sync throws.
  - Test similar patterns in `namespace` and `version`.
  - Test that a valid dependency (no traversal chars) still creates the correct linkDir.
  - Test that empty strings for any component still work (e.g., `namespace = ""` for packages without namespace).
- **Dependencies**: None (can be done independently; ideally combined with Finding #4 in one pass since both touch the dependency processing loop)

---

### Phase 2: Core Logic Fixes (fresh/redownload/scope filtering)

These three findings fix the primary correctness guarantees of the entire subsystem.

#### Finding #1 — `fresh=true` Cannot Force ORT Re-Analysis

- **Target Finding**: #1 — `fresh=true` Cannot Force ORT Re-Analysis
- **Status**: N/A (first review)
- **Rationale & Goal**: The `fresh=true` flag only evicts from the in-memory `viewCache`. `DependencyAnalyzer.analyzeDependencies()` has no mechanism to skip its own disk cache (`DependencyCacheService.getCachedResult()`). Users passing `fresh=true` expecting fresh analysis silently receive stale results. **Goal**: Thread the `fresh` flag through to `DependencyAnalyzer` so it can bypass the disk cache when requested.
- **General Context & Gotchas**:
  - `DependencyAnalyzer.analyzeDependencies()` at lines 44-47 unconditionally calls `cacheService.getCachedResult(managedFiles)` and returns it if non-null.
  - The `DependencyCacheService` has no `evictResult()` or `invalidateResult()` method.
  - The simplest approach: add a `force: Boolean = false` parameter to `DependencyAnalyzer.analyzeDependencies()` that skips the `getCachedResult` check when true. In `SourcesViewService.sync()`, pass `force = fresh` to the analyzer call at line 77.
  - Alternative (reviewer's suggestion): expose `evictResult(managedFiles)` on `DependencyCacheService` and call it from `sync()`. This is more explicit but requires an extra method. The `force` parameter approach is simpler and equally correct.
  - When `fresh` is true, a new analysis result IS computed and saved, so the disk cache is updated. When `fresh` is false, the disk cache is still used, which is correct behavior.
  - **Important**: The existing test `testFreshModeEvictsCacheAndReanalyzes` (SourcesViewServiceTest.kt:153) currently verifies `verify(exactly = 2) { analyzer.analyzeDependencies(tempDir) }` — it counts invocations but does NOT verify the analysis results actually differ. After the fix, this test should ALSO verify that the second call produces a different result when dependency files have changed (per reviewer's verification strategy).
- **Execution Steps**:
  1. Add `force: Boolean = false` parameter to `DependencyAnalyzer.analyzeDependencies()`:
     ```kotlin
     fun analyzeDependencies(
         projectRoot: Path,
         packageManagerOptions: Map<String, Map<String, String>> = emptyMap(),
         force: Boolean = false
     ): AnalyzerResult {
         // ... existing validation ...
         if (!force) {
             val cachedResult = cacheService.getCachedResult(managedFiles)
             if (cachedResult != null) return cachedResult
         }
         // ... rest unchanged ...
     }
     ```
  2. In `SourcesViewService.sync()`, line 77: change `dependencyAnalyzer.analyzeDependencies(projectRoot)` to `dependencyAnalyzer.analyzeDependencies(projectRoot, force = fresh)`
  3. Update `testFreshModeEvictsCacheAndReanalyzes` to also verify result changes:
     - Sync once → capture `depHash`.
     - Modify a dependency file on disk.
     - Sync with `fresh=true` → verify `depHash` differs from first sync.
     - Verify `analyzeDependencies` was called exactly twice.
- **Verification Strategy** (from review report + expanded):
  - Modify `testFreshModeEvictsCacheAndReanalyzes` (SourcesViewServiceTest.kt:153): write `tempDir.resolve("build.gradle").writeText("plugins { id 'java' }")` BETWEEN the two sync calls to simulate changed dependency files.
  - Assert the two view results have different `depHash` values.
  - Run `testInMemoryViewCaching` to ensure normal cache hits still work.
  - Run `testCheckFreshView` to ensure staleness detection still works.
- **Dependencies**: Finding #1 should be done before or together with Finding #2 (same section of `sync()`)

#### Finding #2 — `redownload=true` Cannot Force CAS Re-download

- **Target Finding**: #2 — `redownload=true` Cannot Force CAS Re-download
- **Status**: N/A (first review)
- **Rationale & Goal**: `DependencySourcesDownloader.downloadSources()` has a fast-path guard at line 38-41: `if (targetDir.exists()) return targetDir`. Since CAS entries are append-only and never deleted, this guard is ALWAYS true for previously downloaded packages. The `redownload=true` flag only evicts the in-memory cache. **Goal**: When `redownload=true`, the system must actually re-download sources to fresh CAS entries.
- **General Context & Gotchas**:
  - The CAS is designed as immutable/append-only — existing entries are never deleted or modified. This is a core architectural constraint.
  - `redownload=true` should create NEW CAS entries (with different content hashes if sources have changed), not overwrite existing ones.
  - The approach: add a `forceRedownload: Boolean = false` parameter to `DependencySourcesDownloader.downloadSources()` that skips the fast-path guard and always proceeds to download + process.
  - Since `downloadAndProcess` calls `FileUtils.atomicMoveIfAbsent`, new downloads get new CAS paths. Old CAS entries remain valid.
  - The in-memory `viewCache` eviction + new UUID-keyed view directory already handles the "different view" part — the fix is just making the downloader actually re-download.
  - **Concurrency test note**: The existing `testConcurrentSyncSameKeyCreatesOneView` test uses mocked `downloader` and `analyzer` — it won't exercise this change. But the `testRedownloadModeReDownloads` test at line 171 currently passes because both calls return the same mocked `casDir` — after this fix, the downloader should be called twice, which the test already verifies: `verify(exactly = 2) { downloader.downloadSources(pkg) }`. However, this test doesn't verify the downloaded content is different. We should enhance it.
- **Execution Steps**:
  1. Add `forceRedownload: Boolean = false` parameter to `DependencySourcesDownloader.downloadSources()`:
     ```kotlin
     fun downloadSources(pkg: Package, forceRedownload: Boolean = false): Path {
         val casKey = getCasKey(pkg)
         val targetDir = env.casDir.resolve(casKey)
         if (!forceRedownload && targetDir.exists()) {
             return targetDir
         }
         // ... rest unchanged ...
     }
     ```
  2. In `SourcesViewService.sync()`, line 99: change `downloader.downloadSources(pkg)` to `downloader.downloadSources(pkg, forceRedownload = redownload)`
  3. Enhance `testRedownloadModeReDownloads`:
     - Create two different CAS dirs with distinct content.
     - Have the downloader return different paths on each call (using `answers { ... }` sequencing).
     - Verify the second sync's view has content from the second downloader return value.
  4. Ensure the spec at `view-directory-creation/spec.md` Behavior table (lines 41-46) is accurate — it currently says `redownload=true` should "invalidate CAS entries (via advisory lock), re-download+process dependencies to new CAS locations." The "invalidate CAS entries" part is misleading since we never delete CAS entries. The spec should be updated to say "bypass CAS fast-path, re-download+process to new CAS locations." But this is a nit and the report doesn't flag it, so optional.
- **Verification Strategy** (from review report + expanded):
  - Write a test that syncs normally, replaces CAS content with known-different content, calls `sync(redownload=true)`, and verifies new view has different content — this requires real directories, not mocks.
  - Verify `testRedownloadModeReDownloads` still passes (download called twice).
  - Verify after `redownload=true`, the old CAS entry still exists (CAS is immutable).
- **Dependencies**: Should be done together with Finding #1 (both modify the `sync()` method and the `fresh || redownload` block)

#### Finding #3 — Scope Filtering Is Broken

- **Target Finding**: #3 — Scope Filtering Is Broken
- **Status**: N/A (first review)
- **Rationale & Goal**: ORT `DependencyGraph.createScopes()` returns scope objects with **qualified** names (e.g., `"Maven:com.example:project:1.0.0:compileClasspath"`). Users pass **unqualified** names (e.g., `"compileClasspath"`). The exact string match `depScopes.any { it in scopes }` at line 83-84 always fails. **Goal**: Make scope filtering work for both graph-based and project-based scopes.
- **General Context & Gotchas**:
  - The `extractDependenciesWithScopes` method collects scope names from two sources:
    - Lines 287-295: `graph.createScopes()` → `.name` → **qualified** names
    - Lines 297-305: `project.scopes` → `.name` → **usually unqualified** names (project-level scopes)
  - Both populate the SAME `depToScopes` map per dependency. So for graph-only projects (increasingly common in modern ORT), all scopes are qualified. For mixed or project-only, some may be unqualified.
  - The fix: use **suffix matching**. A dependency qualifies if ANY of its scope names has the suffix `:$userScope` (indicating qualified name match) OR exactly equals `$userScope` (unqualified project scope). This handles both cases.
  - The test helper `makeAnalyzerResult` uses `DependencyGraph.qualifyScope(dummyProjectId, scope)` which already produces qualified names — so the test data correctly models the bug.
  - Tests `testSyncWithUnknownScopeNamesReturnsEmpty` with `scopes = setOf("nonexistent")` — this happens to pass because NO scope matches (not even qualified ones), but for the wrong reasons. It'll still pass after the fix.
  - Test `testSyncWithSpecificScopes` with `scopes = setOf("compile")` — this SHOULD match 1 dependency after the fix (via suffix match on `"Maven:com:root:1.0:compile"`).
- **Execution Steps**:
  1. In `SourcesViewService.sync()`, lines 83-84, replace:
     ```kotlin
     depScopes.any { it in scopes }
     ```
     with:
     ```kotlin
     depScopes.any { depScope ->
         scopes.any { userScope ->
             depScope == userScope || depScope.endsWith(":$userScope")
         }
     }
     ```
  2. Run the existing test suite. Expected: `testSyncWithSpecificScopes` now passes (currently may be failing or passing with wrong behavior). `testSyncWithUnknownScopeNamesReturnsEmpty` still passes.
  3. `testSyncWithEmptyScopesReturnsAllDependencies` should still pass (2 dependencies).
  4. Add a new test using a real `DependencyGraph` (not mocked) with known qualified scope names, pass `scopes = ["compile"]`, assert correct dependencies are included.
- **Verification Strategy** (from review report + expanded):
  - Verify `testSyncWithSpecificScopes` (line 258) passes — 1 dependency should match.
  - Verify `testSyncWithUnknownScopeNamesReturnsEmpty` (line 300) passes — 0 dependencies.
  - Verify `testSyncWithEmptyScopesReturnsAllDependencies` passes — 2 dependencies.
  - Verify `testDependencyInMultipleScopesIncludedOnce` passes — 1 dependency.
  - Add a suffix-match edge case test: scope with qualified name `"Gradle:root:1.0:compileClasspath"`, user passes `scopes = ["compileClasspath"]`, verify match.
- **Dependencies**: Should be done after Findings #1 and #2 (those modify nearby code in the same method)

---

### Phase 3: Major Logic & Concurrency Fixes

#### Finding #7 — `check()`/`ViewCacheKey.create()` Crash on Non-Existent Roots

- **Target Finding**: #7 — Crash on Non-Existent Project Roots
- **Status**: N/A (first review)
- **Rationale & Goal**: `Path.toRealPath()` throws `IOException` if the path does not exist. The design spec says "If no view exists on disk → `{ viewExists: false, fresh: false }`." Currently passing a non-existent root crashes instead. **Goal**: Handle non-existent paths gracefully.
- **General Context & Gotchas**:
  - Three locations need fixing:
    1. `SourcesViewService.check()` in-memory lookup (line 167): `projectRoot.toAbsolutePath().normalize().toRealPath()`
    2. `SourcesViewService.check()` disk fallback (line 195): `projectRoot.toAbsolutePath().normalize().toRealPath().toString()`
    3. `ViewModels.ViewCacheKey.create()` (ViewModels.kt:50): `projectRoot.toAbsolutePath().normalize().toRealPath()`
  - For `check()`: wrap in `runCatching` and return the "no view" fallback on failure.
  - For `ViewCacheKey.create()`: use `toAbsolutePath().normalize()` which does NOT throw on non-existent paths. `toRealPath()` is used here for symlink resolution — but `normalize()` already handles simple `.` and `..` collapsing. The `toRealPath()` provides canonical path resolution which is nice but not critical. If we keep `toRealPath()`, wrap it in `runCatching` and fall back to `toAbsolutePath().normalize()`.
  - The design doc (line 114) says "Resolve symlinks for the project root (but not intermediate components)" — `toRealPath()` resolves all symlinks. If we drop `toRealPath()`, we lose symlink resolution. Compromise: try `toRealPath()`, fall back to `toAbsolutePath().normalize()` on failure.
- **Execution Steps**:
  1. Fix `ViewCacheKey.create()` (ViewModels.kt:50):
     ```kotlin
     fun create(projectRoot: Path, scopes: Set<String>): ViewCacheKey {
         val normalized = runCatching { projectRoot.toAbsolutePath().normalize().toRealPath() }
             .getOrDefault(projectRoot.toAbsolutePath().normalize())
         return ViewCacheKey(normalized, scopes)
     }
     ```
  2. Fix `check()` in-memory lookup (SourcesViewService.kt:167): wrap `projectRoot.toAbsolutePath().normalize().toRealPath()` in `runCatching`:
     ```kotlin
     val cachedEntry = viewCache.entries.firstOrNull {
         val normalized = runCatching { projectRoot.toAbsolutePath().normalize().toRealPath() }.getOrNull()
         normalized != null && it.key.normalizedProjectRoot == normalized
     }
     ```
  3. Fix `check()` disk fallback (SourcesViewService.kt:195): wrap in `runCatching`:
     ```kotlin
     val normalizedRoot = runCatching { projectRoot.toAbsolutePath().normalize().toRealPath().toString() }
         .getOrNull() ?: return StalenessCheck(viewExists = false, fresh = false, ...)
     ```
  4. Add a test: `testCheckNonExistentProjectRoot()` that calls `service.check(Path.of("/nonexistent"))` and asserts `viewExists = false, fresh = false` without throwing.
- **Verification Strategy** (from review report + expanded):
  - `service.check(Path.of("/nonexistent"))` returns `{ viewExists: false, fresh: false, ... }` without throwing.
  - `service.sync(Path.of("/nonexistent"))` — does this crash at a different point (e.g., `require(projectRoot.exists())` in DependencyAnalyzer)? The sync path is expected to fail, but should fail with a clear error, not a cryptic `NoSuchFileException`.
  - Run all existing `check()` tests to ensure no regressions.
- **Dependencies**: None (can be done independently)

#### Finding #8 — Deduplicate SHA-256 Hashing Logic

- **Target Finding**: #8 — Duplicated SHA-256 Hashing Logic
- **Status**: N/A (first review)
- **Rationale & Goal**: `SourcesViewService.computeDepHash()` and `DependencyCacheService.calculateCacheKey()` are character-for-character identical. If the hashing algorithm changes, both must be updated independently — and they will eventually diverge, breaking staleness detection. **Goal**: Single shared implementation.
- **General Context & Gotchas**:
  - `DependencyCacheService.calculateCacheKey()` is `private`. Must be made `internal` (or `public` with an appropriate accessibility modifier) so `SourcesViewService` can call it.
  - `SourcesViewService.computeDepHash()` additionally instantiates its own `Analyzer` + `AnalyzerConfiguration` (lines 313-314) — this is actually duplicate logic with `DependencyAnalyzer.analyzeDependencies()` which does the same thing. However, `computeDepHash` needs to compute the hash WITHOUT running full analysis, so it needs a lightweight `Analyzer` just for `findManagedFiles`. This is reasonable but could share the `Analyzer` instantiation pattern.
  - The shared method should accept the `ManagedFileInfo` and return the hex string.
  - After deduplication, `grep` for `MessageDigest.getInstance("SHA-256")` in main source should show exactly one occurrence (in `DependencyCacheService`).
- **Execution Steps**:
  1. Make `calculateCacheKey` `internal` in `DependencyCacheService`:
     ```kotlin
     internal fun calculateCacheKey(managedFiles: Analyzer.ManagedFileInfo): String { ... }
     ```
  2. In `SourcesViewService.computeDepHash()` (line 312-335), replace the hashing loop body with:
     ```kotlin
     private fun computeDepHash(projectRoot: Path): String {
         val analyzerConfig = AnalyzerConfiguration()
         val analyzer = Analyzer(analyzerConfig)
         val managedFiles = analyzer.findManagedFiles(
             absoluteProjectPath = projectRoot.toFile(),
             packageManagers = PackageManagerFactory.ALL.values.filter { it.descriptor.id != "Unmanaged" },
             repositoryConfiguration = RepositoryConfiguration()
         )
         return cacheService.calculateCacheKey(managedFiles)
     }
     ```
     Note: This also eliminates the duplicate `MessageDigest.getInstance("SHA-256")` and the entire hash loop from `SourcesViewService`.
  3. If `cacheService` is removed per Finding #9, the delegation path changes — but since we're doing this first, delegate to `cacheService.calculateCacheKey`. If Finding #9 removes `cacheService` from SourcesViewService, we'll need `DependencyCacheService` to be accessible another way (e.g., through `dependencyAnalyzer`). See dependency note below.
  4. Add a test: pass the same `ManagedFileInfo` through both paths and assert identical output.
- **Verification Strategy** (from review report + expanded):
  - `grep` for `MessageDigest.getInstance("SHA-256")` in main source tree shows only one occurrence.
  - Run `testCheckFreshView` and `testCheckStaleView` — both should pass unchanged.
  - New test: create `ManagedFileInfo` from temp directory, compute via both paths, assert equality.
- **Dependencies**: Interacts with Finding #9 (if `cacheService` is removed from `SourcesViewService`, need alternate access to `DependencyCacheService`). Resolve Finding #9 FIRST, then do Finding #8. Alternatively: do #8 using the `cacheService` field, then #9 removes the field but the delegation moves to going through `dependencyAnalyzer.cacheService` (made internal).

#### Finding #9 — Remove Unused `cacheService` Field

- **Target Finding**: #9 — Unused `cacheService` Field in `SourcesViewService`
- **Status**: N/A (first review)
- **Rationale & Goal**: The `cacheService` constructor parameter in `SourcesViewService` is never referenced in any method body. It wastes memory, misleads about the service's dependencies, and causes tests to silently mock an unused dependency. **Goal**: Remove the dead parameter.
- **General Context & Gotchas**:
  - `DependencyAnalyzer` creates its own internal `DependencyCacheService` instance (line 14: `private val cacheService: DependencyCacheService = DependencyCacheService()`).
  - After removal: Finding #8 (deduplicate hashing) needs to delegate through `dependencyAnalyzer`'s cache service instead. This means `DependencyAnalyzer.cacheService` needs to be `internal` — or we need a new accessor.
  - Actually, simpler approach for #8: make `DependencyCacheService.calculateCacheKey` a **static/companion** method or a **top-level function** so it doesn't need an instance. But `calculateCacheKey` uses `MessageDigest` which is stateless, so a companion object method or extension function would work. This avoids the accessor problem entirely.
  - **Best approach for combined #8+#9**: Extract the hashing logic into a top-level `internal fun computeManagedFilesHash(managedFiles: Analyzer.ManagedFileInfo): String` in a shared location, then both `DependencyCacheService` and `SourcesViewService` call it. OR make it a companion method on `DependencyCacheService`.
  - Wait, the simplest approach: extract `calculateCacheKey` into a companion object method on `DependencyCacheService`. Then `DependencyCacheService` instances call it, and `SourcesViewService` calls `DependencyCacheService.calculateCacheKey(managedFiles)` (static call). The removal of the instance field in SourcesViewService becomes trivial.
- **Execution Steps**:
  1. Move `calculateCacheKey` logic to a companion object in `DependencyCacheService`:
     ```kotlin
     companion object {
         fun calculateCacheKey(managedFiles: Analyzer.ManagedFileInfo): String { ... }
     }
     ```
  2. The instance method becomes a delegation: `private fun calculateCacheKey(...) = Companion.calculateCacheKey(...)` — or just call the companion directly.
  3. In `SourcesViewService.computeDepHash()`, delete the duplicate hashing and call `DependencyCacheService.calculateCacheKey(managedFiles)`.
  4. Remove the `cacheService` constructor parameter from `SourcesViewService` (line 34). Remove it from the test setup (SourcesViewServiceTest.kt:47,51) and concurrency test setup.
  5. Verify all tests pass without modification to mocks.
- **Verification Strategy** (from review report + expanded):
  - `grep` for `cacheService` in `SourcesViewService.kt` yields zero results.
  - All tests pass without modification (the mock was never consulted).
  - Run `testInMemoryViewCaching`, `testFreshModeEvictsCacheAndReanalyzes`, `testCheckFreshView` — all pass.
- **Dependencies**: Must be done BEFORE or TOGETHER WITH Finding #8. The combined approach described here solves both.

#### Finding #10 — Concurrent Read/Write Race on Analysis Cache

- **Target Finding**: #10 — Concurrent Read/Write Race on Shared Analysis Cache Files
- **Status**: N/A (first review)
- **Rationale & Goal**: `DependencyCacheService.getCachedResult()` and `saveResult()` operate on shared disk files without any locking. Two concurrent calls for different project roots that produce identical managed file sets (→ identical cache key) can race: both read miss, both run expensive ORT analysis, both write (last write wins, or partial read). **Goal**: Protect cache file access with `FileLockManager.withLock()`.
- **General Context & Gotchas**:
  - The `FileLockManager` already exists in the codebase (`src/main/kotlin/dev/rnett/sources/mcp/FileLockManager.kt`). It provides `withLock(lockFile) { ... }` with timeout and retry logic. It's already used by `DependencySourcesDownloader`.
  - The lock file should be based on the cache key: `cacheDir.resolve("$cacheKey.lock")`.
  - The lock should protect BOTH `getCachedResult` (read) AND `saveResult` (write) — not just writes.
  - The per-key `Mutex` in `SourcesViewService` does NOT protect `DependencyCacheService` because different project roots use different `Mutex` instances but can share the same cache key.
  - `FileLockManager.withLock` is NOT a coroutine function (uses `runBlocking` internally) — the `getCachedResult` and `saveResult` are currently synchronous, so this is fine.
  - **Gotcha**: The `cacheDir` is created by `SourcesMcpEnvironment.init` so it should always exist at this point. But `locksDir` (where lock files would go) is also created. We should use `env.locksDir` or a subdirectory for analyzer cache locks to avoid mixing with CAS download locks.
- **Execution Steps**:
  1. Add a lock file path derivation in `DependencyCacheService`:
     ```kotlin
     private fun lockFileFor(cacheKey: String) = env.locksDir.resolve("analyzer-$cacheKey.lock")
     ```
  2. Wrap `getCachedResult()`:
     ```kotlin
     fun getCachedResult(managedFiles: Analyzer.ManagedFileInfo): AnalyzerResult? {
         val cacheKey = calculateCacheKey(managedFiles)
         val cachedFile = cacheDir.resolve("$cacheKey.yml")
         return FileLockManager.withLock(lockFileFor(cacheKey)) {
             if (cachedFile.exists()) cachedFile.toFile().readValue<AnalyzerResult>() else null
         }
     }
     ```
  3. Wrap `saveResult()` similarly:
     ```kotlin
     fun saveResult(managedFiles: Analyzer.ManagedFileInfo, ortResult: AnalyzerResult): AnalyzerResult {
         val cacheKey = calculateCacheKey(managedFiles)
         val cachedFile = cacheDir.resolve("$cacheKey.yml")
         return FileLockManager.withLock(lockFileFor(cacheKey)) {
             cachedFile.toFile().writeValue(ortResult)
             ortResult
         }
     }
     ```
  4. Write a concurrency test: two coroutines calling `analyzeDependencies()` for two different directories with identical `build.gradle` files. Verify exactly one save occurs per cache key. Use `runTest` with real temp directories.
- **Verification Strategy** (from review report + expanded):
  - Concurrency test with two coroutines → exactly one `saveResult` call per unique cache key.
  - Verify no `NoSuchFileException` or partial-deserialization errors occur during concurrent access.
  - Run existing dependency analyzer tests to ensure no regressions.
- **Dependencies**: None (can be done independently; requires `DependencyCacheService` import of `FileLockManager`)

#### Finding #11 — `ViewCleanupService` Can Delete In-Use Views

- **Target Finding**: #11 — `ViewCleanupService` Can Delete In-Use Views
- **Status**: N/A (first review)
- **Rationale & Goal**: The cleanup service iterates and deletes view directories solely by age (24+ hours). It is entirely unaware of the in-memory `viewCache` in `SourcesViewService`. If an agent holds a view reference for >24h, cleanup deletes the junction tree, leaving broken junctions. **Goal**: Prevent cleanup from deleting views that are still in the in-memory cache, OR validate view existence before returning from `getView()`.
- **General Context & Gotchas**:
  - Two complementary approaches:
    1. `SourcesViewService.getView()` validates directory existence before returning, evicting stale cache entries (simple, defensive).
    2. `ViewCleanupService` checks `viewCache` before deleting (requires the cleanup service to KNOW about the cache, introducing unwanted coupling).
  - Approach 1 is preferred — it's simpler and follows the principle of "validate on read." Even without cleanup, if someone manually deletes a view directory, `getView()` returning a `SessionView` pointing to a nonexistent directory is a bug.
  - `getView()` currently at line 239-242: just returns `viewCache[cacheKey]` — add existence check.
  - Also consider: should `sync()`'s first check `viewCache[cacheKey]?.let { return it }` at line 71 also validate? If the cleanup deleted the view, this returns a stale SessionView. YES — add validation here too.
- **Execution Steps**:
  1. Add a private helper in `SourcesViewService`:
     ```kotlin
     private fun SessionView.isValid(): Boolean = baseDir.exists() && sourcesDir.exists()
     
     private fun getValidCachedView(key: ViewCacheKey): SessionView? {
         val view = viewCache[key] ?: return null
         if (view.isValid()) return view
         viewCache.remove(key)
         logger.warn("Evicted stale cache entry for view ${view.sessionId} — directory no longer exists")
         return null
     }
     ```
  2. In `sync()`, replace `viewCache[cacheKey]?.let { return it }` (line 71) with `getValidCachedView(cacheKey)?.let { return it }`.
  3. In the `mutex.withLock` block, replace `viewCache[cacheKey]?.let { return@withLock it }` (line 75) with `getValidCachedView(cacheKey)?.let { return@withLock it }`.
  4. In `getView()` (line 239-242), replace `viewCache[cacheKey]` with `getValidCachedView(cacheKey)`.
  5. Write a test: create view via `sync()`, manually delete view directory on disk, call `getView()` → assert `null`.
  6. Write a test: create view via `sync()`, manually delete view directory on disk, call `sync()` again → assert new view is created (different sessionId).
- **Verification Strategy** (from review report + expanded):
  - Create view → delete view dir → `getView()` returns null.
  - Create view → delete view dir → `sync()` creates new view (new dir, new sessionId).
  - Create view → keep it intact → `getView()` still returns the valid view.
  - Run concurrency tests to ensure no race between existence check and cleanup.
- **Dependencies**: None (can be done independently)

#### Finding #13 — Orphaned Directories from Failed Sync

- **Target Finding**: #13 — Orphaned Directories from Failed Sync
- **Status**: N/A (first review)
- **Rationale & Goal**: `baseDir` and `sourcesDir` are created at lines 88-90 at the start of `sync()`. If any subsequent step fails (analysis, all downloads fail, coroutine exception), the method exits without caching the view, and the directories remain orphaned on disk for up to 24 hours. **Goal**: Clean up on failure.
- **General Context & Gotchas**:
  - This fix should be applied AFTER the `mutex.withLock` block is entered (so we hold the lock) and AFTER `baseDir` creation.
  - The cleanup should use `baseDir.toFile().deleteRecursively()` in a `catch`/`finally` block.
  - Be careful: if the view IS successfully created, we should NOT delete it.
  - The `try/finally` should surround everything from directory creation to cache insertion.
  - This also means: if `computeDepHash` or `normalizationService.normalize()` (currently no-op) throw, the directories are cleaned up.
- **Execution Steps**:
  1. Restructure the body of `sync()`'s `mutex.withLock` block to wrap in `try/catch`:
     ```kotlin
     mutex.withLock {
         viewCache[cacheKey]?.let { return@withLock it }
         
         val result = dependencyAnalyzer.analyzeDependencies(projectRoot, force = fresh)
         // ... filtering ...
         
         val sessionId = UUID.randomUUID().toString()
         val baseDir = env.viewsDir.resolve(sessionId).also { it.createDirectories() }
         val sourcesDir = baseDir.resolve("sources").also { it.createDirectories() }
         
         try {
             // ... dependency processing, manifest creation, cache insertion ...
         } catch (e: Exception) {
             if (baseDir.exists()) {
                 baseDir.toFile().deleteRecursively()
             }
             throw e
         }
     }
     ```
  2. Test: mock `analyzeDependencies` to throw after directory creation, verify `baseDir` does not exist on disk after `sync()` rethrows.
  3. Test: full successful sync, verify `baseDir` exists and contains expected files.
- **Verification Strategy** (from review report + expanded):
  - When `analyzeDependencies()` throws, `baseDir` does not exist after `sync()` rethrows.
  - When all downloads fail (empty `filteredPackages`), view is still created with empty sources dir (per spec, this is valid).
  - When a single download fails (logged warning), view is created without that dependency but `baseDir` persists.
- **Dependencies**: Should be done after Findings #1, #2, #11 (these modify the same method body)

---

### Phase 4: MCP Protocol & Interface Fixes

#### Finding #14 — Missing `toolAnnotations`

- **Target Finding**: #14 — Missing `toolAnnotations` on Both MCP Tools
- **Status**: N/A (first review)
- **Rationale & Goal**: Both tools set `toolAnnotations = null`. LLMs cannot distinguish a read-only query (`check_dependencies`) from a mutating operation (`sync_dependencies`) via standard MCP metadata. **Goal**: Provide accurate annotations.
- **General Context & Gotchas**:
  - `sync_dependencies`: mutates filesystem (creates directories, downloads files) — `readOnlyHint = false`, `destructiveHint = true`, `idempotentHint = false` (subsequent calls may return cached result but the first call IS mutating).
  - `check_dependencies`: purely read-only — `readOnlyHint = true`, `destructiveHint = false`, `idempotentHint = true`.
  - The `Tool` class from MCP Kotlin SDK expects `toolAnnotations` of type `Tool.Annotations?`. Need to check the SDK's exact API for constructing annotations.
- **Execution Steps**:
  1. Replace `toolAnnotations = null` on `sync_dependencies` (SyncCheckTools.kt:61):
     ```kotlin
     toolAnnotations = Tool.Annotations(
         readOnlyHint = false,
         destructiveHint = true,
         idempotentHint = false
     ),
     ```
  2. Replace `toolAnnotations = null` on `check_dependencies` (SyncCheckTools.kt:83):
     ```kotlin
     toolAnnotations = Tool.Annotations(
         readOnlyHint = true,
         destructiveHint = false,
         idempotentHint = true
     ),
     ```
  3. Verify the SDK's `Tool.Annotations` constructor — it may use named parameters, defaults, or a different instantiation pattern. Check existing usages in the codebase or SDK sources.
- **Verification Strategy** (from review report + expanded):
  - Confirm via MCP Inspector that both tools carry non-null annotations with correct values.
  - Or: unit test that instantiates `SyncCheckTools`, registers on a mock `Server`, and verifies annotations on registered tools.
- **Dependencies**: None (can be done independently)

#### Finding #15 — `age` Field Returns Custom Format, Not ISO-8601

- **Target Finding**: #15 — `age` Field Returns Custom Format, Not ISO-8601
- **Status**: N/A (first review)
- **Rationale & Goal**: The spec declares `age: String? # ISO-8601 duration`. The implementation produces `"2d 3h 45m 12.345s"`. An LLM or tool expecting `Duration.parse()` compatibility will fail. **Goal**: Return parseable ISO-8601 durations.
- **General Context & Gotchas**:
  - `java.time.Duration.toString()` produces ISO-8601 format: `PT48H45M12.345S`. This is directly parseable by `Duration.parse()`.
  - The `formatDuration` function at SyncCheckTools.kt:143-156 can be entirely replaced OR kept alongside the ISO-8601 field.
  - Since this is for LLM consumption, the spec requires machine-parseable format. Use `Duration.toString()`.
  - BUT: is this actually an improvement for LLM usability? Human-readable `"2d 3h 45m"` is more intuitive for agents reading the output. The spec says ISO-8601, but the spec might be wrong. This is a judgment call — the report says option (a) ISO-8601 is preferred. I'll go with that since it's what the report recommends and what the spec says.
  - Consider adding BOTH: `ageIso: "PT48H..."` + `age: "2d 3h 45m"` in the output. But that's scope creep — stick to the spec.
- **Execution Steps**:
  1. In `checkDependenciesHandler` (SyncCheckTools.kt:134), change:
     ```kotlin
     appendLine("age: ${stalenessCheck.age?.let { formatDuration(it) } ?: "N/A"}")
     ```
     to:
     ```kotlin
     appendLine("age: ${stalenessCheck.age?.toString() ?: "N/A"}")
     ```
  2. Remove the `formatDuration` function if it's no longer used. (It won't be, since it was only called here.)
  3. Update `testStalenessCheckFreshViewExists` in ViewModelsTest.kt to also verify `check.age.toString()` is parseable. But `StalenessCheck.age` is a `Duration?` — the serialization format decision happens in the MCP tool output, not in the data class. So the test for the data class doesn't change.
  4. Add a test in `SyncCheckTools` or an integration test: verify `age` output is parseable by `java.time.Duration.parse()`.
- **Verification Strategy** (from review report + expanded):
  - Verify `age` output is parseable by `java.time.Duration.parse()`.
  - Run `testStalenessCheckFreshViewExists` — still passes.
  - Verify existing integration tests (if any) that parse `check_dependencies` output.
- **Dependencies**: None (can be done independently; may conflict with Finding #21 which also touches `formatDuration`)

#### Finding #16 — Error Handling Throws Raw Exceptions

- **Target Finding**: #16 — Error Handling Throws Raw Exceptions
- **Status**: N/A (first review)
- **Rationale & Goal**: When `projectRoot` is missing from arguments, `error("projectRoot is required")` throws `IllegalStateException`. The LLM receives a raw exception with no recovery guidance. **Goal**: Return structured `isError: true` MCP responses with instructional text.
- **General Context & Gotchas**:
  - Both handlers have the same pattern: `syncDependenciesHandler` at line 92-93 and `checkDependenciesHandler` at line 124-125.
  - The fix: wrap the handler bodies in `try/catch` and return `CallToolResult(isError = true, content = [TextContent("Error: ...")])` on validation failures.
  - Also consider: what about runtime exceptions from the service layer? Those should also be caught and returned as structured errors. But that's a broader change — for now, fix the immediate validation error paths.
  - Pattern to follow:
    ```kotlin
    private suspend fun syncDependenciesHandler(request: CallToolRequest): CallToolResult {
        return try {
            // ... existing logic ...
        } catch (e: IllegalArgumentException) {
            CallToolResult(isError = true, content = listOf(TextContent("Error: ${e.message}")))
        } catch (e: Exception) {
            CallToolResult(isError = true, content = listOf(TextContent("Unexpected error: ${e.message}")))
        }
    }
    ```
  - But the `error()` call throws `IllegalStateException`, not `IllegalArgumentException`. We should change the `error()` to a proper validation with `require()` which throws `IllegalArgumentException`, OR catch `IllegalStateException` specifically.
- **Execution Steps**:
  1. In `syncDependenciesHandler` (line 92-93), replace:
     ```kotlin
     val projectRoot = Path.of(args["projectRoot"]?.jsonPrimitive?.content
         ?: error("projectRoot is required")).absolute()
     ```
     with proper validation:
     ```kotlin
     val projectRootContent = args["projectRoot"]?.jsonPrimitive?.content
         ?: return CallToolResult(
             isError = true,
             content = listOf(TextContent("Error: 'projectRoot' is required. Provide the absolute path to the project root directory."))
         )
     val projectRoot = Path.of(projectRootContent).absolute()
     ```
  2. Do the same for `checkDependenciesHandler` (line 124-125).
  3. Optionally: also catch the `IllegalArgumentException` from `require(projectRoot.exists())` in `DependencyAnalyzer` and return a structured error. But that propagates through the service layer — the simplest approach is to add a general `catch (Exception e)` around the handler body.
  4. Test: call each tool without `projectRoot` → verify structured error with instructional text.
- **Verification Strategy** (from review report + expanded):
  - Call `sync_dependencies` without `projectRoot` → `isError: true` response with descriptive text.
  - Call `check_dependencies` without `projectRoot` → `isError: true` response with descriptive text.
  - Call both with valid projectRoot → normal successful responses.
- **Dependencies**: None (can be done independently)

---

### Phase 5: Performance & Monitoring Fixes

#### Finding #12 — No Path-Length Check for Windows MAX_PATH

- **Target Finding**: #12 — No Path-Length Check for Windows MAX_PATH
- **Status**: N/A (first review)
- **Rationale & Goal**: Design doc Decision #15 says "Log warnings when created symlinks exceed 200 characters." The implementation never checks path lengths. Deep dependency paths can silently exceed 260 characters, causing `rg --follow` through such paths to fail with cryptic errors. **Goal**: Implement the warning as mandated by the design.
- **General Context & Gotchas**:
  - The check should be in `createLink`/`createJunction` in `SourcesViewService`.
  - 200 chars is the warning threshold (from design doc). 260 is Windows MAX_PATH.
  - The path being checked is the SYMLINK/JUNCTION path, which is the longest component: `{viewsDir}/{uuid}/sources/{ecosystem}/{namespace}/{name}/{version}`.
  - Log a warning at 200 chars, log an error at 250+ chars (close to MAX_PATH).
  - The warning should include the actual path length and the view sessionId for debugging.
- **Execution Steps**:
  1. In `createLink` or `createJunction` (SourcesViewService.kt:256-281), after computing the link path, add:
     ```kotlin
     val linkPathStr = linkPath.toAbsolutePath().normalize().toString()
     if (linkPathStr.length > 250) {
         logger.error("Junction path exceeds 250 characters (${linkPathStr.length}): $linkPathStr — may hit Windows MAX_PATH (260)")
     } else if (linkPathStr.length > 200) {
         logger.warn("Junction path exceeds 200 characters (${linkPathStr.length}): $linkPathStr")
     }
     ```
  2. Write a test: create a test environment with a very long `viewsDir` prefix (200+ chars), sync a dependency with long identifiers, verify warning is logged.
  3. Run the test on Windows to validate the MAX_PATH behavior.
- **Verification Strategy** (from review report + expanded):
  - Test with deliberately long directory prefix exceeding 200 chars → verify warning is logged.
  - Test with prefix exceeding 250 chars → verify error is logged.
  - Test with normal-length paths → no warnings logged.
- **Dependencies**: None (can be done independently)

#### Finding #17 — `check()` Disk Fallback O(n)

- **Target Finding**: #17 — `check()` Disk Fallback O(n)
- **Status**: N/A (first review)
- **Rationale & Goal**: When the in-memory cache misses, `check()` lists all subdirectories in `viewsDir` and reads/deserializes every `manifest.json`. With 100+ stale views, this is hundreds of filesystem reads for a "lightweight" operation. **Goal**: Maintain an index or at least cap the scan cost.
- **General Context & Gotchas**:
  - The reviewer's recommendation: maintain an on-disk index mapping `normalizedProjectRoot → sessionId`, updated on every `sync`. Fall back to full scan only if the index is missing or corrupted.
  - A simpler approach (lower implementation cost): the view directories are UUID-named and cannot encode the project root. But the `manifest.json` contains `projectRoot`. This is inherently O(n).
  - The index approach: maintain a simple JSON file at `viewsDir/index.json` mapping `normalizedProjectRoot → latestSessionId`. Update on every `sync()`. When `check()` misses the in-memory cache, read the index file, look up the project root, read only THAT manifest.
  - **Gotcha**: The index is shared state across all sync operations — it needs its own locking. Use `FileLockManager.withLock` on `viewsDir/index.json.lock`.
  - The index file should be small (one entry per distinct project root, usually < 10 entries in practice).
  - If the index points to a non-existent view directory (deleted by cleanup, corrupted), fall back to full scan.
- **Execution Steps**:
  1. Create a private helper `readViewIndex(): Map<String, String>` that reads `viewsDir/index.json` (or returns empty map on missing/invalid).
  2. Create a private helper `writeViewIndex(map: Map<String, String>)` that writes `viewsDir/index.json` with `FileLockManager.withLock`.
  3. In `sync()`, after successful view creation, update the index: `writeViewIndex(readViewIndex() + (normalizedProjectRoot to sessionId))`.
  4. In `check()`, when in-memory cache misses, read the index. If the project root is in the index, try to read ONLY that `manifest.json`. If the manifest exists and matches, return result. Otherwise fall back to full scan.
  5. Run existing `testCheckViewOnDiskButNotInMemoryCache` (should be faster now, but same result).
  6. Write a performance test: create 100 stale views, restart service (clear memory cache), call `check()` → verify it reads at most 2 manifest files (index + target).
- **Verification Strategy** (from review report + expanded):
  - Test with 100 views, restart service, verify `check()` latency is ~O(1), not O(n).
  - Test that `check()` still finds views when the index is correct.
  - Test that `check()` falls back to full scan and still works when the index is corrupted or points to a deleted view.
- **Dependencies**: None (can be done independently)

---

### Phase 6: Cleanup & Idiom Fixes (Minor #18-#29)

These are simple, low-risk improvements that can be batched together.

#### Finding #18 — Redundant `equals()`/`hashCode()` on Data Class

- **Execution Steps**: Delete lines 38-46 in `ViewModels.kt` (the entire manual override block).
- **Verification**: Run all tests — `ViewCacheKey` equality still works. `testConcurrentSyncSameKeyCreatesOneView` still passes.
- **Gotchas**: If future customization is needed, add with KDoc explaining why.

#### Finding #19 — Inconsistent `kotlin.io.path` vs `java.io.File`

- **Execution Steps**:
  - `SourcesViewService.kt:196`: `viewsDir.toFile().listFiles()` → `Files.list(viewsDir).use { it.toList() }` (already imported: `java.nio.file.Files`)
  - `SourcesViewService.kt:203`: `manifestFile.toFile().readText()` → `manifestFile.readText()` (already imported: `kotlin.io.path.readText`)
  - `SourcesViewService.kt:82` (`ViewCleanupService`): `viewDir.toFile().deleteRecursively()` → `viewDir.deleteRecursively()` — need to add import for `kotlin.io.path.deleteRecursively`
  - `DependencySourcesDownloader.kt:109`: `dir.toFile().listFiles()` → `Files.list(dir).use { it.toList() }` 
  - `DependencySourcesDownloader.kt:113`: `subDir.listFiles()` → `Files.list(subDir.toPath()).use { it.toList() }` — wait, `subDir` is a `java.io.File`. Convert to `Path` first or keep as-is. This is in the dependency downloader, not the view service. Scope to the view service files only.
  - **Scope**: Only fix the view service files (`SourcesViewService.kt`, `ViewCleanupService.kt`) per the reviewer's scope.
- **Verification**: Compiles. Run `testCheckViewOnDiskButNotInMemoryCache` (exercises disk scan). Run `testOldViewsAreDeleted`.

#### Finding #20 — Duplicated Scope-Collection Logic

- **Execution Steps**: Extract a shared helper:
  ```kotlin
  private fun collectScopesFrom(
      scopeCollections: List<Collection<Scope>>,
      depToScopes: MutableMap<Identifier, MutableSet<String>>
  ) {
      for (scopes in scopeCollections) {
          for (scope in scopes) {
              val scopeName = scope.name
              val depIds = scope.collectDependencies()
              for (depId in depIds) {
                  depToScopes.getOrPut(depId) { mutableSetOf() } += scopeName
              }
          }
      }
  }
  ```
  Then call it with `result.dependencyGraphs.values.flatMap { it.createScopes() }` and `result.projects.flatMap { it.scopes }`.
- **Verification**: Run `testSyncWithSpecificScopes`, `testDependencyInMultipleScopesIncludedOnce` — all pass.

#### Finding #21 — `formatDuration` Manual Arithmetic → `Duration.toComponents()`

- **Execution Steps**: Replace the body of `formatDuration` with:
  ```kotlin
  private fun formatDuration(duration: Duration): String {
      val parts = mutableListOf<String>()
      duration.toComponents { days, hours, minutes, seconds, nanos ->
          if (days > 0) parts += "${days}d"
          if (hours > 0 || days > 0) parts += "${hours}h"
          if (minutes > 0 || hours > 0 || days > 0) parts += "${minutes}m"
          val millis = nanos / 1_000_000
          if (millis > 0) parts += "${seconds}.%03d".format(millis) + "s"
          else parts += "${seconds}s"
      }
      return parts.joinToString(" ")
  }
  ```
  Note: `toComponents` is available since Kotlin 1.6 / Duration API. Verify the project's Kotlin version supports it.
  - **BUT**: If Finding #15 (ISO-8601 format) is already applied and `formatDuration` is removed entirely, this finding becomes irrelevant. If `formatDuration` is kept (e.g., for internal logging), apply this fix.
  - **Decision**: Finding #15 recommends removing custom format entirely → `formatDuration` is removed → this finding is automatically resolved. Skip unless `formatDuration` is retained.
- **Verification**: Run tests with duration formatting. Verify `Duration.toComponents` works on the project's Kotlin version.

#### Finding #22 — `isWindows` Per-Instance → Companion Object

- **Execution Steps**: Move from line 47 to inside the class body as:
  ```kotlin
  companion object {
      private val IS_WINDOWS: Boolean = System.getProperty("os.name").lowercase().contains("win")
  }
  ```
  Then replace `isWindows` references (line 257) with `IS_WINDOWS`.
- **Verification**: Compile. All junction tests still work on Windows.

#### Finding #23 — Remove `@JvmName` on Private Method

- **Execution Steps**: Delete `@JvmName("syncDependenciesHandler")` from line 88 in SyncCheckTools.kt.
- **Verification**: Compile. Register tools on server — both handlers still work.

#### Finding #24 — Inconsistent Fully-Qualified `Path.of`

- **Execution Steps**: `SourcesViewService.kt:206`: change `java.nio.file.Path.of(manifest.projectRoot)` to `Path.of(manifest.projectRoot)`. The `Path` import at line 21 already covers this.
- **Verification**: Compile. Run `testCheckViewOnDiskButNotInMemoryCache`.

#### Finding #25 — `sync()` Method Too Long — Extract Download Pipeline

- **Execution Steps**: Extract lines 94-129 (the `coroutineScope` download-and-link block) into:
  ```kotlin
  private suspend fun downloadAndLinkDependencies(
      filteredPackages: List<Package>,
      sourcesDir: Path
  ): List<ViewDependency> = coroutineScope {
      // ... existing code ...
  }
  ```
  Update `sync()` to call: `val dependencies = downloadAndLinkDependencies(filteredPackages, sourcesDir)`
- **Verification**: All sync tests pass without modification.

#### Finding #26 — `check()` Method Too Long — Extract Disk Scan

- **Execution Steps**: Extract lines 183-228 into:
  ```kotlin
  private fun findViewOnDisk(normalizedRoot: String): StalenessCheck? { ... }
  private fun computeAge(timestamp: String): Duration? =
      runCatching { Duration.between(Instant.parse(timestamp), Instant.now()) }.getOrNull()
  ```
  The `check()` method then becomes: in-memory check → return or findViewOnDisk → return or "no view" fallback.
- **Verification**: Run all `check()` tests — `testCheckFreshView`, `testCheckStaleView`, `testCheckNoView`, `testCheckViewOnDiskButNotInMemoryCache`.

#### Finding #27 — `NormalizationService` Zero Documentation

- **Execution Steps**: Add KDoc to `NormalizationService.kt`:
  ```kotlin
  /**
   * Post-processes downloaded dependency sources within a view directory.
   *
   * Normalization may include directory structure flattening, character encoding fixes,
   * or removal of redundant wrapper directories. Implementations SHOULD operate in-place
   * and return the (possibly modified) [sourcesDir] path.
   *
   * Currently a no-op — actual normalization is deferred to a future iteration.
   */
  ```
  Also add KDoc to `NoOpNormalizationService`.
- **Verification**: KDoc exists. No behavioral change.

#### Finding #28 — `NormalizationService` Variable on Hot Path (Micro-Optimization)

- **Context**: The reviewer suggests removing the `normalizedSourcesDir` variable from the hot path because it's currently a no-op. However, the design doc explicitly calls this out as a placeholder interface.
- **Decision**: **Keep as-is.** The variable assignment at line 131 is a single no-op function call — the performance cost is zero. Removing it would only remove the integration point that makes future normalization implementation seamless. The alternative would require touching every call site when normalization is implemented. **Skip this fix** — it's correct architecture.
- **Verification**: No changes needed.

#### Finding #29 — `Thread.sleep` in Coroutine Tests

- **Execution Steps**:
  1. `ViewCleanupServiceTest.kt:31,89`: Remove `Thread.sleep(1100)`. The timestamps are already 2 hours old (`minusSeconds(7200)`) and `maxAge` is 1 second — no sleep needed. Delete both calls.
  2. `SourcesViewServiceConcurrencyTest.kt:25,57`: Replace `Thread.sleep(100)` with `delay(100)` inside `runTest`. Add import for `kotlinx.coroutines.delay`.
  3. In concurrency test (line 40-44): replace `async(Dispatchers.IO)` with `async` (default dispatcher in `runTest` is `StandardTestDispatcher` which runs on virtual time). This makes tests deterministic.
- **Verification**: Run `ViewCleanupServiceTest` and `SourcesViewServiceConcurrencyTest` — all pass, now with predictable test times.

---

### Phase 7: Test Coverage Gaps (Findings #30-#35)

#### Finding #30 — `getView()` Untested

- **Execution Steps**: Add to `SourcesViewServiceTest.kt`:
  1. `testGetViewReturnsNullWhenNoViewSynced`: call `getView(tempDir)` → assert null.
  2. `testGetViewReturnsViewAfterSync`: sync then getView → assert same sessionId.
  3. `testGetViewRespectsScopeKeying`: sync with scopes "compile", getView with different scopes "test" → assert null (cache key mismatch).
  4. `testGetViewReturnsNullAfterEviction`: sync, evictView, getView → assert null.
  5. `testGetViewReturnsNullAfterDirectoryDeleted`: sync, delete view dir on disk, getView → assert null (requires Finding #11 fix to be applied first).
- **Verification**: All 5 tests pass. Run alongside other view service tests.

#### Finding #31 — `ViewCacheKey` Create/Equals/HashCode Untested

- **Execution Steps**: Add to `ViewModelsTest.kt`:
  1. Create two `ViewCacheKey`s from same root/scopes → assert equal, same hashCode.
  2. Create two `ViewCacheKey`s from same root but different scopes → assert not equal.
  3. Create two `ViewCacheKey`s from different roots but same scopes → assert not equal.
  4. Create `ViewCacheKey` from root with trailing `/` vs without → assert equal (normalization eliminates difference).
  5. `ViewCacheKey.create` normalizes path (collapses `..`, removes trailing separators).
- **Verification**: Run `ViewModelsTest` — all tests pass.

#### Finding #32 — `ViewCleanupService.start()`/`stop()` Untested

- **Execution Steps**: Add to `ViewCleanupServiceTest.kt`:
  1. `testStartPerformsInitialCleanup`: create an old view dir, call `start()`, cancel scope after, assert old view is deleted.
  2. `testPeriodicCleanupRuns`: use `runTest` with virtual time, create an old view dir, call `start()`, advance time by `cleanupInterval + 1ms`, assert view is deleted.
  3. `testStopCancelsLoop`: call `start()`, call `stop()`, advance time by multiple intervals, verify cleanup only ran once (the initial scan).
- **Verification**: Run `ViewCleanupServiceTest` — all tests pass using virtual time.

#### Finding #33 — StalenessCheck Missing "Stale View Exists" State Test

- **Execution Steps**: Add to `ViewModelsTest.kt`:
  ```kotlin
  @Test
  fun testStalenessCheckStaleViewExists() {
      val age = Duration.ofHours(4)
      val check = StalenessCheck(
          viewExists = true,
          fresh = false,
          age = age,
          depHash = "sha256:stored-hash",
          currentDepHash = "sha256:current-different-hash",
          dependencyCount = 15,
      )
      assertEquals(true, check.viewExists)
      assertEquals(false, check.fresh)
      assertEquals(age, check.age)
      assertEquals("sha256:stored-hash", check.depHash)
      assertEquals("sha256:current-different-hash", check.currentDepHash)
      assertEquals(15, check.dependencyCount)
  }
  ```
- **Verification**: Run `ViewModelsTest` — new test passes.

#### Finding #34 — No Gradle Integration Test

- **Execution Steps**: Add an integration test (either in test source or a separate integration test module):
  1. Use a `ProjectFixture.gradle` (if the test harness provides one) or create a temp Gradle project with a single dependency.
  2. Call `sync(projectRoot)` end-to-end (no mocks).
  3. Assert: valid sessionId (UUID format), `sourcesDir` exists and is a directory, `manifest.json` exists and deserializes correctly.
  4. Assert: `manifest.dependencies` contains at least the expected dependency.
  5. Call `check(projectRoot)` → assert `viewExists = true, fresh = true`.
- **Verification**: Integration test passes with real ORT analysis and real CAS downloads. May be slow — tag as `@Tag("integration")` or place in an integration test source set.
- **Gotchas**: Requires network access (dependency downloads) and a compatible Gradle environment. Use `ProjectFixture.gradle` from the test harness per project patterns.

#### Finding #35 — `readTimestamp` Fallback Paths Uncovered

- **Execution Steps**: Add to `ViewCleanupServiceTest.kt`:
  1. `testReadTimestampFallbackToLastModifiedWhenNoTimestampKey`: create manifest without `"timestamp"` key, verify cleanup uses `getLastModifiedTime` as fallback. The view is old (lastModifiedTime is old) and should be deleted.
  2. `testReadTimestampFallbackToLastModifiedWhenNoManifestFile`: create view dir without manifest.json, verify cleanup uses `getLastModifiedTime` as fallback.
- **Verification**: Run `ViewCleanupServiceTest` — new tests pass.

---

### Phase 8: Server Bootstrap (Finding #6)

#### Finding #6 — Wiring Dead Code

- **Target Finding**: #6 — `SyncCheckTools` and `ViewCleanupService` are Dead Code
- **Status**: N/A (first review)
- **Rationale & Goal**: Both `registerAll()` and `start()` have zero callers. The entire feature is non-functional at the MCP protocol layer. **Goal**: Wire services into the MCP server bootstrap.
- **General Context & Gotchas**:
  - The project is bootstrapping (per AGENTS.md: "WE ARE BOOTSTRAPPING THE PROJECT"). The wiring may be intentionally deferred.
  - If wiring now: need to find or create the server bootstrap file where `Server` is instantiated and `addTool` calls happen.
  - If deferring: update `tasks.md` to uncheck items 5.1/5.2 and add a comment about wiring.
  - **Check**: Is there an existing MCP server bootstrap? Search for `Server` instantiation or `addTool` calls in `src/main/`.
  - If no bootstrap exists yet, creating one may be out of scope for this review fix — it's a separate task. In that case, the fix is: update the task list to accurately reflect status.
- **Execution Steps**:
  1. Search `src/main/` for existing `Server` bootstrap code: `tilth_tilth_search` for `Server` symbol.
  2. **If bootstrap exists**: wire `SyncCheckTools.registerAll(server)` and `ViewCleanupService.start()` in the appropriate initialization block.
  3. **If bootstrap does not exist**: 
     - Mark tasks.md item 5.1 and 5.2 as `[ ]` (not yet done).
     - Add a note: "Tool registration and cleanup service wiring deferred until server bootstrap is established."
     - This finding is resolved by accurate tracking — the code itself is correct, it's just not wired yet.
- **Verification Strategy** (from review report + expanded):
  - If wired: integration test that instantiates Server, registers tools, invokes via client protocol layer.
  - If deferred: task list accurately reflects `[ ]` state. A follow-up task exists for wiring.
- **Dependencies**: Done last (after all the code it would wire has been fixed)

---

## Section 6: Proposed Invariants to Codify

The review report identifies 12 invariants. Here's where to add each:

| # | Invariant | Location | Format |
|---|-----------|----------|--------|
| 1 | `fresh`/`redownload` Propagation: any mode flag on `SourcesViewService.sync()` MUST thread through to `DependencyAnalyzer` and `DependencySourcesDownloader` | `AGENTS.md` under "Architecture & Design Rules" section | Add a new `## View Service Invariants` sub-section |
| 2 | Path Resolution Safety: `toRealPath()` throws on non-existent paths; use `runCatching` or `toAbsolutePath().normalize()` | `AGENTS.md` (same section) | Bullet point under View Service Invariants |
| 3 | ORT Scope Qualification: graph scopes are always qualified; filtering MUST use suffix matching | `AGENTS.md` (same section) | Bullet point |
| 4 | No Shell Invocation: never use `ProcessBuilder` with interpreters for filesystem operations; use Java NIO or JNA | `AGENTS.md` under Security section | Bullet point |
| 5 | DRY Hashing Logic: `ManagedFileInfo` hash computation MUST use single shared implementation in `DependencyCacheService` | `AGENTS.md` (View Service Invariants) | Bullet point |
| 6 | Resource Cleanup: every `createDirectories()` before fallible ops MUST have error-path cleanup | `AGENTS.md` (View Service Invariants) | Bullet point |
| 7 | Cache Coherence: in-memory cache entries MUST validate filesystem resource existence before returning | `AGENTS.md` (View Service Invariants) | Bullet point |
| 8 | File API Consistency: always use `kotlin.io.path` extensions on `Path` objects within `dev.rnett.sources.mcp` | `AGENTS.md` under Kotlin/Idioms section | Bullet point |
| 9 | Data Class Convention: never manually override `equals/hashCode` on data classes without documented reason | `AGENTS.md` under Kotlin/Idioms section | Bullet point |
| 10 | Test Dispatcher Hygiene: concurrency tests MUST use `runTest` with `StandardTestDispatcher`; use `delay()` not `Thread.sleep()` | `AGENTS.md` under Testing section | Bullet point |
| 11 | MCP Tool Annotations: every MCP tool MUST set `toolAnnotations` (readOnlyHint, destructiveHint, idempotentHint) | `AGENTS.md` under MCP Design section | Bullet point |
| 12 | MCP Error Responses: validation errors MUST return structured `isError: true` with instructional recovery text | `AGENTS.md` under MCP Design section | Bullet point |

---

## Phase Order Summary

```
Phase 1: Critical Security      → #4, #5                      (blockers; ship-gate)
Phase 2: Core Logic Fixes        → #1, #2, #3                  (depends on nothing else)
Phase 3: Major Logic/Concurrency → #7, #8+#9, #10, #11, #13    (can parallelize within phase)
Phase 4: MCP Protocol/Interface  → #14, #15, #16               (independent)
Phase 5: Performance/Monitoring  → #12, #17                    (independent)
Phase 6: Cleanup/Idioms          → #18-#29 (minus #28)         (batch; independent)
Phase 7: Test Coverage           → #30-#35                     (depends on Phases 2-6 fixes)
Phase 8: Server Bootstrap        → #6                          (last; wires it all up)
```

**Total: 34 actionable fixes** (Finding #28 is a deliberate skip — keeping the placeholder interface variable is correct architecture per the design doc).
</task_result>