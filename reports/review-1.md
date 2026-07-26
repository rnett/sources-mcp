# 🏛️ Faceted Review Report — View Directory Management

**Change**: `view-directory-management` (OpenSpec) — Session View subsystem  
**Date**: 2026-05-07  
**Resolution finished**: [x]

---

## Executive Summary

This review assessed a greenfield feature implementing the **Session View** subsystem — an ephemeral, project-level directory containing junctions to CAS entries and a `manifest.json`. The change introduces 5 new source files (services + models + MCP tools), 4 test files, and an integration test directory, plus a single-line addition to `SourcesMcpEnvironment.kt`.

**Overall Assessment: Promising architecture, but not production-ready.** The design faithfully implements the A1 architecture from the OpenSpec design doc, and the per-key `Mutex`-based concurrency strategy is sound. However, three of the subsystem's core advertised features are **fundamentally non-functional**: `fresh=true` does not force re-analysis, `redownload=true` does not force re-download, and scope filtering compares qualified ORT scope names against unqualified user input (always fails). Additionally, the MCP tools are never wired into any server bootstrap — the implementation is **dead code** at the protocol layer. A critical security vulnerability (command injection via `cmd.exe /c mklink`) and a path-traversal risk round out the blocking concerns.

The test suite provides good foundational coverage but has significant gaps (untested `getView()`, `ViewCacheKey`, lifecycle methods). The documentation is thorough but contains numerous accuracy gaps between spec claims and actual implementation.

**Verdict**: Do not merge. The 5 Critical findings must be resolved first, followed by the 12 Major findings. The 18 Minor findings can be addressed incrementally.

---

## 🔴 Critical (Must Address)

### 1. **[Logic / Architecture / Spec Parity]** `[Mistake]`: `fresh=true` Cannot Force ORT Re-Analysis — Cached Analysis Result Always Returned

- **Code Context**: 
  - `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 67-69 and 77
  - `src/main/kotlin/dev/rnett/sources/mcp/DependencyAnalyzer.kt`, lines 44-47

```kotlin
// SourcesViewService.kt:67-69
if (fresh || redownload) {
    viewCache.remove(cacheKey)   // Only evicts in-memory view cache
}
// ...
viewCache[cacheKey]?.let { return it }
// ...
val result = dependencyAnalyzer.analyzeDependencies(projectRoot)  // Line 77

// DependencyAnalyzer.kt:44-47
val cachedResult = cacheService.getCachedResult(managedFiles)
if (cachedResult != null) {
    return cachedResult  // ALWAYS returns cached result from disk
}
```

- **Root Cause & Flaw**: `fresh=true` only evicts from the in-memory `viewCache`. The `DependencyAnalyzer.analyzeDependencies()` call at line 77 has **no mechanism to bypass its own disk cache** — it unconditionally returns `cacheService.getCachedResult()`. There is no `fresh` or `force` parameter in `DependencyAnalyzer`. The OpenSpec spec (`view-directory-creation/spec.md` lines 44-46) explicitly states `fresh=true` must "re-run ORT analysis, re-use existing CAS downloads." This invariant is violated.

- **Pragmatic Rationale**: Any caller that passes `fresh=true` expecting fresh analysis will silently receive stale data. This is the primary correctness guarantee of the `sync_dependencies` tool. Changes to `build.gradle` will not be detected — the old analysis result persists from the disk cache.

- **Recommendation**: Add a `force: Boolean = false` parameter to `DependencyAnalyzer.analyzeDependencies()` that skips the `getCachedResult` check. Alternatively, expose `evictResult(managedFiles)` on `DependencyCacheService` and call it from `sync()` before invoking the analyzer.

- **Verification Strategy**: Run `sync(fresh=true)` twice, modifying dependency files between calls. Assert the second invocation reflects the modified files. The existing test `testFreshModeEvictsCacheAndReanalyzes` (`SourcesViewServiceTest.kt:153`) needs to verify actual result changes, not just call counts.

- **Resolution**: Fixed

---

### 2. **[Logic / Architecture / Spec Parity]** `[Mistake]`: `redownload=true` Cannot Force CAS Re-download — Existing CAS Entry Fast-Path Always Returns

- **Code Context**: 
  - `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 67-69 and 97-100
  - `src/main/kotlin/dev/rnett/sources/mcp/DependencySourcesDownloader.kt`, lines 35-41

```kotlin
// SourcesViewService.kt:67-69
if (fresh || redownload) {
    viewCache.remove(cacheKey)   // Only evicts in-memory view cache
}
// ...
async(Dispatchers.IO) {
    try {
        val casPath = downloader.downloadSources(pkg)  // Line 99

// DependencySourcesDownloader.kt:35-41
fun downloadSources(pkg: Package): Path {
    val casKey = getCasKey(pkg)
    val targetDir = env.casDir.resolve(casKey)
    if (targetDir.exists()) {
        return targetDir  // ALWAYS returns existing CAS entry — fast-path
    }
```

- **Root Cause & Flaw**: The `redownload=true` flag only evicts from the in-memory `viewCache`. `DependencySourcesDownloader.downloadSources()` has **no mechanism to bypass its CAS fast-path check**. Since CAS entries are never deleted (append-only immutable design), `targetDir.exists()` is always true for previously downloaded packages. The OpenSpec spec (`view-directory-creation/spec.md` lines 45-46) says redownload should "invalidate CAS entries (via advisory lock), re-download+process dependencies to new CAS locations." Nothing in the code performs CAS invalidation. The concurrency test passes only because mocks bypass the real filesystem.

- **Pragmatic Rationale**: Users passing `redownload=true` expecting fresh source downloads (e.g., to recover from corrupt CAS entries or get updated source jars for the same version) receive the same stale CAS data. The `redownload` toggle is effectively a no-op beyond creating a new UUID-keyed view directory pointing at the same CAS entries.

- **Recommendation**: Either (a) add a `forceRedownload: Boolean` parameter to `downloadSources()` that skips the fast-path guard, or (b) have `sync()` delete the existing CAS entry directory (with advisory lock held) before calling `downloadSources()`. Given the CAS-is-immutable constraint, approach (b) aligns better — create fresh CAS entries with new keys when redownload is requested.

- **Verification Strategy**: A test that creates a view normally, replaces CAS content with known-different content, calls `sync(redownload=true)`, and verifies the new view has different content. Must use real directories (not mocks) to exercise the fast-path guard.

- **Resolution**: Fixed

---

### 3. **[Logic]** `[Mistake]`: Scope Filtering Is Broken — DependencyGraph Scope Names Are Qualified, User Input Is Unqualified

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 79-86 and 283-310

```kotlin
// Lines 79-86: filter logic
val filteredPackages = if (scopes.isEmpty()) {
    allDepsWithScopes.map { it.first }
} else {
    allDepsWithScopes.filter { (_, depScopes) ->
        depScopes.any { it in scopes }  // EXACT string match
    }.map { it.first }
}

// Lines 287-295: scope name extraction (uses qualified scope.name)
for ((_, graph) in result.dependencyGraphs) {
    for (scope in graph.createScopes()) {
        val scopeName = scope.name  // QUALIFIED: "Maven:com:root:1.0:compile"
        ...
    }
}
```

- **Root Cause & Flaw**: ORT's `DependencyGraph.createScopes()` returns scopes with **qualified** names (e.g., `"Maven:com.example:project:1.0.0:compileClasspath"`). Users pass **unqualified** names (e.g., `scopes = ["compileClasspath"]`). The exact string match at line 83-84 always fails. This makes the entire scope filtering feature non-functional. The test `testSyncWithSpecificScopes` constructs an `AnalyzerResult` with `projects = emptySet()` and a graph-based scope — since the filter cannot match, this test **should return 0 dependencies, not 1**, suggesting the tests were never run against this exact code.

- **Pragmatic Rationale**: Any MCP agent passing a scope filter receives an **empty view** instead of the filtered dependency set. The second loop (lines 297-305) over `result.projects` may add unqualified scope names from project-level scopes, but dependency-graph-only projects (common in modern ORT usage) are broken.

- **Recommendation**: Use suffix matching: a dependency qualifies if any scope name has suffix `:$userScope` (for qualified graph scopes) or equals the user scope exactly (for unqualified project scopes). Alternatively, use ORT's built-in `Scope.filter()` methods which handle qualification internally.

- **Verification Strategy**: Write a test with a real `DependencyGraph` (not mocked), pass `scopes = ["compile"]`, assert correct dependencies are included. Verify `testSyncWithSpecificScopes` fails against current code (confirming the bug), then fix.

- **Resolution**: Fixed

---

### 4. **[Security]** `[Mistake]`: Command Injection via `cmd.exe` in Junction Creation

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 267-272

```kotlin
val linkStr = linkPath.toAbsolutePath().normalize().toString()
val targetStr = targetPath.toAbsolutePath().normalize().toString()

val process = ProcessBuilder("cmd", "/c", "mklink", "/J", linkStr, targetStr)
    .redirectErrorStream(true)
    .start()
```

- **Root Cause & Flaw**: `cmd.exe /c` concatenates all arguments and parses them through the full command interpreter. Shell metacharacters (`&`, `|`, `>`, `<`, `^`, `%`) in `linkStr` or `targetStr` are interpreted as command separators. Both paths contain dependency identifier components (`ecosystem`, `namespace`, `name`, `version`) that originate from ORT analysis of the dependency graph. A malicious dependency with a crafted name like `foo&del /F /Q C:\important\*` would cause cmd.exe to execute arbitrary shell commands.

- **Pragmatic Rationale**: A supply-chain attacker who publishes a dependency with deliberately crafted identifiers could execute arbitrary commands on the developer's machine with the same privileges as the MCP server. This crosses a critical trust boundary: dependency metadata (external, untrusted) flows into a shell command (code execution).

- **Recommendation**: Replace shell invocation with a direct Windows API call. Options in preference order:
  1. Use `java.nio.file.Files.createSymbolicLink()` on Windows (supported since Java 13).
  2. Use JNA/Kernel32 to call `CreateJunction` directly.
  3. If a subprocess is unavoidable, pass arguments via a temp file with sanitized content.

- **Verification Strategy**: Test that a dependency with cmd.exe metacharacters in its name does NOT execute arbitrary commands — only junction creation (or a clean failure). Verify no `ProcessBuilder("cmd", "/c", ...)` calls remain in the junction creation path.

- **Resolution**: Fixed

---

### 5. **[Security]** `[Mistake]`: Path Traversal via Unvalidated Dependency Identifiers in Directory Construction

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 102-108

```kotlin
val ecosystem = pkg.id.type
val namespace = pkg.id.namespace
val name = pkg.id.name
val version = pkg.id.version

val linkDir = sourcesDir.resolve("$ecosystem/$namespace/$name/$version")
linkDir.parent.createDirectories()
```

- **Root Cause & Flaw**: Java's `Path.resolve()` does **not** normalize `..` components. If any dependency identifier component contains `../` sequences, the resolved path escapes `sourcesDir`. While `type` is constrained to registered package manager types, `name`, `namespace`, and `version` are free-form strings from package manifests — attacker-controlled in malicious packages. Combined with Finding #4 (command injection), this could be chained for full system compromise.

- **Pragmatic Rationale**: A poisoned dependency in a dependency tree could create directories in arbitrary filesystem locations. Combined with junction creation, this could plant broken junctions in sensitive paths.

- **Recommendation**: Add a normalization guard after path construction:
  ```kotlin
  val linkDir = sourcesDir.resolve("$ecosystem/$namespace/$name/$version").normalize()
  require(linkDir.startsWith(sourcesDir)) {
      "Dependency path escapes sources directory: $linkDir"
  }
  ```

- **Verification Strategy**: Test with a mocked `Package` whose `name` is `../../escape-test` — verify `sync()` throws or rejects the dependency rather than escaping `sourcesDir`. Test similar traversal patterns in `namespace` and `version`.

- **Resolution**: Fixed

---

## 🟡 Major (Highly Recommended)

### 6. **[Risk / Integration]** `[Unclear Intent]`: `SyncCheckTools` and `ViewCleanupService` are Dead Code — Never Wired Into MCP Server Bootstrap

- **Code Context**: 
  - `src/main/kotlin/dev/rnett/sources/mcp/SyncCheckTools.kt:22` — `fun registerAll(server: Server)` defined, never called
  - `src/main/kotlin/dev/rnett/sources/mcp/ViewCleanupService.kt:38` — `fun start()` defined, never called
  - No server bootstrap file in `src/main/` that instantiates or wires these services

- **Root Cause & Flaw**: Both services are fully implemented but have zero callers in the project. MCP tools `sync_dependencies` and `check_dependencies` will never be available to agents. View cleanup will never run. The entire change is non-functional at the MCP protocol layer. Tests directly instantiate services, masking the wiring gap.

- **Pragmatic Rationale**: The AGENTS.md says "WE ARE BOOTSTRAPPING THE PROJECT" so this may be intentionally deferred, but the task list marks items 5.1 and 5.2 as complete (`[x]`). If the tools can't be invoked, the feature isn't actually implemented.

- **Recommendation**: Either create a server bootstrap file that wires these services, or explicitly track the wiring as a follow-up task and revert the `[x]` checkmarks.

- **Verification Strategy**: An integration test that instantiates `Server`, registers tools, starts cleanup, and invokes tools via the client protocol layer.

- **Resolution**: Deferred

---

### 7. **[Logic / Concurrency]** `[Mistake]`: `check()` and `ViewCacheKey.create()` Crash on Non-Existent Project Roots

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 167, 195; `src/main/kotlin/dev/rnett/sources/mcp/ViewModels.kt`, line 50

```kotlin
// check() in-memory lookup (line 167):
it.key.normalizedProjectRoot == projectRoot.toAbsolutePath().normalize().toRealPath()
//                                              toRealPath() THROWS on non-existent path

// check() disk scan (line 195):
val normalizedRoot = projectRoot.toAbsolutePath().normalize().toRealPath().toString()

// ViewCacheKey.create() (line 50):
val normalized = projectRoot.toAbsolutePath().normalize().toRealPath()
```

- **Root Cause & Flaw**: `Path.toRealPath()` throws `IOException` if the path does not exist or cannot be accessed. `check_dependencies` is documented as a "lightweight check" that should always succeed — but passes a non-existent project root and crashes. The design spec (design.md line 78) states: "If no view exists on disk → `{ viewExists: false, fresh: false }`."

- **Pragmatic Rationale**: An agent passing a wrong or non-existent project root to `check_dependencies` receives a stack trace instead of `viewExists: false`.

- **Recommendation**: Wrap `toRealPath()` in `runCatching` at all three locations. For `check()`, return the "no view" fallback on failure. For `ViewCacheKey.create()`, either throw a domain-specific exception or use `toAbsolutePath().normalize()` (which does NOT throw on non-existent paths).

- **Verification Strategy**: Test `service.check(Path.of("/nonexistent"))` returns `viewExists = false, fresh = false` without throwing.

- **Resolution**: Fixed

---

### 8. **[Concurrency / Idiom / Clean Code]** `[Mistake]`: Duplicated SHA-256 Hashing Logic Between `SourcesViewService` and `DependencyCacheService`

- **Code Context**: 
  - `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 312-335 (`computeDepHash`)
  - `src/main/kotlin/dev/rnett/sources/mcp/DependencyCacheService.kt`, lines 32-46 (`calculateCacheKey`)

Both methods implement character-for-character identical SHA-256 hashing of ORT `ManagedFileInfo` — same digest algorithm, same sort orders, same `isFile` guard, same hex encoding.

- **Root Cause & Flaw**: Cross-module copy-paste DRY violation. If the hashing algorithm changes (SHA-512, added metadata, changed sort), both copies must be updated independently — they will inevitably diverge, causing staleness checks to silently break.

- **Pragmatic Rationale**: A future developer changing the cache key strategy in `DependencyCacheService` will have no idea they must also update `SourcesViewService.computeDepHash()`. This is a maintenance time-bomb.

- **Recommendation**: Make `calculateCacheKey` `internal` in `DependencyCacheService`. Have `computeDepHash` delegate to it after obtaining `ManagedFileInfo`. This also eliminates the duplicate `Analyzer` instantiation in `computeDepHash`.

- **Verification Strategy**: Create a test that passes the same `ManagedFileInfo` through both paths and asserts identical output. `grep` for `MessageDigest.getInstance("SHA-256")` should show only one occurrence in the main source tree.

- **Resolution**: Fixed

---

### 9. **[Architecture / Clean Code]** `[Mistake]`: `DependencyCacheService` Field Injected into `SourcesViewService` Is Entirely Unused

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 33-34

```kotlin
class SourcesViewService(
    private val dependencyAnalyzer: DependencyAnalyzer = DependencyAnalyzer(),
    private val downloader: DependencySourcesDownloader = DependencySourcesDownloader(),
    private val cacheService: DependencyCacheService = DependencyCacheService(),  // UNUSED
    private val env: SourcesMcpEnvironment = SourcesMcpEnvironment.fromEnv(),
    private val normalizationService: NormalizationService = NoOpNormalizationService(),
)
```

- **Root Cause & Flaw**: The `cacheService` field is never referenced in any method body. `DependencyAnalyzer` creates its own internal `DependencyCacheService` instance (line 14). Design Decision #2 (design.md:58-62) states the service "can leverage `DependencyCacheService` internally" — this is misleading since it doesn't.

- **Pragmatic Rationale**: Tests that mock `cacheService` at the `SourcesViewService` level silently do nothing (the mock is never invoked). Dead code wastes resources and misleadingly suggests the service uses analysis caching.

- **Recommendation**: Remove the unused parameter. The service delegates to `DependencyAnalyzer`, which handles its own caching.

- **Verification Strategy**: After removal, `grep` for `cacheService` in `SourcesViewService.kt` should yield zero results. All tests pass without modification.

- **Resolution**: Fixed

---

### 10. **[Concurrency]** `[Mistake]`: `DependencyCacheService` — Concurrent Read/Write Race on Shared Analysis Cache Files

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/DependencyCacheService.kt`, lines 15-30

```kotlin
fun getCachedResult(managedFiles: Analyzer.ManagedFileInfo): AnalyzerResult? {
    val cacheKey = calculateCacheKey(managedFiles)
    val cachedFile = cacheDir.resolve("$cacheKey.yml")
    return if (cachedFile.exists()) {
        cachedFile.toFile().readValue<AnalyzerResult>()  // READ without lock
    } else null
}

fun saveResult(managedFiles: Analyzer.ManagedFileInfo, ortResult: AnalyzerResult): AnalyzerResult {
    val cacheKey = calculateCacheKey(managedFiles)
    val cachedFile = cacheDir.resolve("$cacheKey.yml")
    cachedFile.toFile().writeValue(ortResult)  // WRITE without lock
    return ortResult
}
```

- **Root Cause & Flaw**: Two different project roots can produce identical managed file sets → identical cache key. The per-key `Mutex` in `SourcesViewService` does NOT protect `DependencyCacheService` because different project roots use different `Mutex` instances. TOCTOU: Thread A reads (miss), Thread B reads (miss), both run expensive ORT analysis, both write (overwrite race). A third thread reading during the write window could get partial data.

- **Pragmatic Rationale**: Wastes ORT analysis cycles and risks partial-read deserialization errors. The `FileLockManager` infrastructure already exists in the codebase — it should be used here.

- **Recommendation**: Wrap cache file access with `FileLockManager.withLock()` for both `getCachedResult` and `saveResult`.

- **Verification Strategy**: Write a concurrency test with two coroutines calling `analyzeDependencies()` for directories with identical managed files. Verify exactly one save occurs per cache key.

- **Resolution**: Fixed

---

### 11. **[Concurrency]** `[Mistake]`: `ViewCleanupService` Can Delete a View Directory Still Referenced by In-Memory `viewCache`

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, line 151; `src/main/kotlin/dev/rnett/sources/mcp/ViewCleanupService.kt`, lines 73-86

The cleanup service iterates and deletes view directories solely by age. It is entirely unaware of the in-memory `viewCache`. After 24 hours, if an agent holds a view reference for >24h, cleanup deletes the junction tree and the agent encounters broken junctions on next access.

- **Pragmatic Rationale**: Rare but disastrous when it occurs — the agent gets cryptic "junction target does not exist" errors.

- **Recommendation**: `SourcesViewService.getView()` should validate directory existence before returning, evicting stale cache entries. Or `ViewCleanupService` should check `viewCache` before deleting.

- **Verification Strategy**: Create a view via `sync()`, simulate cleanup by manually deleting the view directory, verify `getView()` returns `null` and evicts the stale entry.

- **Resolution**: Fixed

---

### 12. **[Risk]** `[Mistake]`: No Path-Length Check for Windows `MAX_PATH` (260 chars) Despite Design Doc Mandating Warnings

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, line 107; Design doc Decision #15

```kotlin
val linkDir = sourcesDir.resolve("$ecosystem/$namespace/$name/$version")
```

The design explicitly says "Log warnings when created symlinks exceed 200 characters." The implementation never checks path lengths. Deep dependency paths can silently exceed 260 characters. On Windows, `rg --follow` through such paths fails with cryptic errors.

- **Recommendation**: Add `linkPathStr.length > 200` check with `logger.warn(...)` in `createLink`/`createJunction`.

- **Verification Strategy**: Test with a deliberately long directory prefix exceeding 200 chars, verify warning is logged.

- **Resolution**: Fixed

---

### 13. **[Security]** `[Mistake]`: Orphaned Directories from Failed Sync (Resource Leak)

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 88-90

```kotlin
val baseDir = env.viewsDir.resolve(sessionId).also { it.createDirectories() }
val sourcesDir = baseDir.resolve("sources").also { it.createDirectories() }
```

`baseDir` and `sourcesDir` are created unconditionally at the start of `sync()`. If any subsequent step fails (analysis throws, all downloads fail, coroutine exception), the method exits without setting `viewCache[cacheKey]`. The directories remain orphaned on disk for up to 24 hours (cleanup cycle). A malicious actor could fill the views directory by repeatedly calling `sync()` on a project that triggers parse failures.

- **Recommendation**: Wrap the entire `sync()` body in a `try/catch` that deletes `baseDir` recursively on failure.

- **Verification Strategy**: Test that when `analyzeDependencies()` throws, `baseDir` does not exist on disk after `sync()` rethrows.

- **Resolution**: Fixed

---

### 14. **[Interface Contract]** `[Mistake]`: Missing `toolAnnotations` on Both MCP Tools

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SyncCheckTools.kt`, lines 61 and 83

```kotlin
toolAnnotations = null,
```

Both tools set `toolAnnotations = null`, depriving LLMs of critical classification signals (`readOnlyHint`, `destructiveHint`, `idempotentHint`). An LLM cannot distinguish a read-only query (`check_dependencies`) from a mutating operation (`sync_dependencies`) via standard MCP metadata.

- **Recommendation**: `sync_dependencies`: `readOnlyHint = false`, `destructiveHint = true`. `check_dependencies`: `readOnlyHint = true`, `destructiveHint = false`, `idempotentHint = true`.

- **Verification Strategy**: Confirm via MCP Inspector that both tools carry non-null annotations.

- **Resolution**: Fixed

---

### 15. **[Interface Contract / Tech Spec]** `[Mistake]`: `age` Field Returns Custom Format, Not ISO-8601 as Spec Requires

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SyncCheckTools.kt`, lines 134 and 143-156

```kotlin
appendLine("age: ${stalenessCheck.age?.let { formatDuration(it) } ?: "N/A"}")

private fun formatDuration(duration: Duration): String {
    // produces "2d 3h 45m 12.345s" — NOT ISO-8601 "PT48H45M12.345S"
}
```

The spec (`sync-check-tools/spec.md` line 62) declares `age: String? # ISO-8601 duration`. The implementation produces a custom human-readable format. An LLM or tool expecting `Duration.parse()` compatibility will fail.

- **Recommendation**: Either (a) switch to ISO-8601 via `Duration.toString()`, or (b) update the spec to reflect the custom format. Option (a) is preferred for machine-parseability.

- **Verification Strategy**: Verify `age` output is parseable by `java.time.Duration.parse()`.

- **Resolution**: Fixed

---

### 16. **[Interface Contract]** `[Mistake]`: Error Handling Throws Raw Exceptions Instead of MCP `isError` Responses

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SyncCheckTools.kt`, lines 92-93

```kotlin
val projectRoot = Path.of(args["projectRoot"]?.jsonPrimitive?.content
    ?: error("projectRoot is required")).absolute()
```

When `projectRoot` is missing, `error()` throws `IllegalStateException`. The LLM receives a raw exception with no recovery guidance.

- **Recommendation**: Return `CallToolResult(isError = true, content = [TextContent("Error: 'projectRoot' is required. Provide the absolute path to the project root directory.")])`.

- **Verification Strategy**: Call each tool without `projectRoot` and verify a structured error response with instructional text.

- **Resolution**: Fixed

---

### 17. **[Tech Spec / Risk]** `[Mistake]`: `check()` Disk Fallback Scans ALL View Directories — O(n) for a "Lightweight" Operation

- **Code Context**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 183-228

When the in-memory cache misses (server restart, eviction), `check()` lists all subdirectories in `viewsDir`, reads and deserializes every `manifest.json`. With 100+ stale views from 24h accumulation, this is hundreds of filesystem reads for what should be a fast hash comparison.

- **Recommendation**: Maintain an on-disk index mapping `normalizedProjectRoot → sessionId`, updated on every `sync`. Fall back to full scan only if the index is missing or corrupted.

- **Verification Strategy**: Test with 100 views, restart service, verify `check()` latency is proportional to O(1).

- **Resolution**: Fixed

---

## 🔵 Minor (Polishing/Idioms)

### 18. **[Idiom / Clean Code]** `[Mistake]`: Redundant `equals()`/`hashCode()` Override on Data Class `ViewCacheKey`

**File**: `src/main/kotlin/dev/rnett/sources/mcp/ViewModels.kt`, lines 38-46

The manual overrides produce identical semantics to auto-generated data class methods. Delete lines 38-46. If future customization is needed, add it with KDoc explaining why the default is insufficient.

---

### 19. **[Idiom]** `[Intentional — Flawed]`: Inconsistent `kotlin.io.path` vs `java.io.File` API Usage

**File**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 196, 203; `ViewCleanupService.kt`, line 82

Replace `viewsDir.toFile().listFiles()` with `Files.list(viewsDir)`, `manifestFile.toFile().readText()` with `manifestFile.readText()`, `viewDir.toFile().deleteRecursively()` with `viewDir.deleteRecursively()`.

---

### 20. **[Idiom]** `[Unclear Intent]`: `extractDependenciesWithScopes` Contains Duplicated Scope-Collection Logic

**File**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 283-310

Blocks A (dependency graphs) and B (projects) are structurally identical. Extract into a shared helper method.

---

### 21. **[Idiom]** `[Intentional — Flawed]`: `formatDuration` Manual Arithmetic vs `Duration.toComponents()`

**File**: `src/main/kotlin/dev/rnett/sources/mcp/SyncCheckTools.kt`, lines 143-156

Replace manual modulo decomposition with `duration.toComponents { days, hours, minutes, seconds, nanoseconds -> ... }`.

---

### 22. **[Idiom / Clean Code]** `[Intentional — Flawed]`: `isWindows` Computed Per-Instance Instead of Once

**File**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, line 47

Move to `companion object` as `IS_WINDOWS` — its value never changes during the JVM's lifetime.

---

### 23. **[Clean Code]** `[Unclear Intent]`: `@JvmName` on Private Method with No Apparent Name Collision

**File**: `src/main/kotlin/dev/rnett/sources/mcp/SyncCheckTools.kt`, line 88

Remove `@JvmName("syncDependenciesHandler")` — it's on a `private suspend` method with no JVM interop concern and no other method of the same name.

---

### 24. **[Clean Code]** `[Mistake]`: Inconsistent Fully-Qualified `java.nio.file.Path.of` vs Imported `Path.of`

**File**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, line 206

Change `java.nio.file.Path.of(manifest.projectRoot)` to `Path.of(manifest.projectRoot)` — the `Path` import already exists at line 21.

---

### 25. **[Clean Code]** `[Intentional — Flawed]`: `sync()` Method Too Long (96 lines, 6 Responsibilities)

**File**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 59-154

Extract the download-and-link pipeline into `private suspend fun downloadAndLinkDependencies(...): List<ViewDependency>`.

---

### 26. **[Clean Code]** `[Intentional — Flawed]`: `check()` Method Too Long with Three Distinct Lookup Strategies

**File**: `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`, lines 164-229

Extract `private fun findViewOnDisk(projectRoot: Path): StalenessCheck?` and shared `private fun computeAge(timestamp: String): Duration?`.

---

### 27. **[Clean Code]** `[Unclear Intent]`: `NormalizationService` Has Zero Documentation

**File**: `src/main/kotlin/dev/rnett/sources/mcp/NormalizationService.kt`, lines 1-11

Add KDoc explaining what "normalization" means, whether the method mutates in-place or returns a new path, and example use cases (directory flattening, encoding fixes).

---

### 28. **[Clean Code]** `[Unclear Intent]`: `NormalizationService` Abstraction Has No Current Behavioral Value

Keep the interface (it's well-justified by the design doc), but consider removing the `normalizedSourcesDir` variable from the hot path until normalization is actually implemented.

- **Resolution**: Deferred

---

### 29. **[Clean Code / Test]** `[Mistake]`: `Thread.sleep` in Coroutine Tests Bypasses Virtual Time

- `src/test/kotlin/.../ViewCleanupServiceTest.kt`, lines 31, 89: `Thread.sleep(1100)` — unnecessary (timestamps are already 2h old)
- `src/test/kotlin/.../SourcesViewServiceConcurrencyTest.kt`, lines 25, 57: `Thread.sleep(100)` in mock answers

Remove sleeps from `ViewCleanupServiceTest` (timestamps are already expired). For concurrency tests, use `delay()` with `runTest` virtual time.

---

### 30. **[Test — Coverage]** `[Mistake]`: `SourcesViewService.getView()` Completely Untested

No test invocations of `getView()` exist. Add tests for: returns null when no view synced, returns correct `SessionView` after sync, respects scope-based key differentiation, returns null after `evictView()`.

---

### 31. **[Test — Coverage]** `[Mistake]`: `ViewCacheKey` Create/Equals/HashCode Entirely Untested

`ViewModelsTest.kt` only tests `ViewManifest` and `StalenessCheck`. Add tests for: key creation normalizes paths, equality of same/different roots/scopes, `hashCode` consistency.

---

### 32. **[Test — Coverage]** `[Mistake]`: `ViewCleanupService.start()` and `stop()` Untested

Only `cleanup()` is tested directly. Add tests using `runTest` with virtual time to verify the periodic loop behavior and cancellation.

---

### 33. **[Test — Coverage]** `[Mistake]`: `StalenessCheck` Missing "Stale View Exists" State Test

Add `testStalenessCheckStaleViewExists` constructing a `StalenessCheck(viewExists=true, fresh=false, ...)` and asserting all fields round-trip.

---

### 34. **[Test — Coverage]** `[Unclear Intent]`: No Gradle Integration Test for Full `sync()` Pipeline

Add a `testSyncGradleEndToEnd` using `ProjectFixture.gradle` with a lightweight dependency, asserting valid sessionId, sourcesDir exists, manifest valid.

---

### 35. **[Test — Coverage]** `[Unclear Intent]`: `readTimestamp` Fallback Paths Uncovered

Add tests for: manifest JSON has no `timestamp` key (falls back to lastModifiedTime), no manifest file (falls back to lastModifiedTime).

---

## 💡 Proposed Invariants/Rules

1. **`fresh` and `redownload` Propagation**: Any mode flag added to `SourcesViewService.sync()` MUST be threaded through to `DependencyAnalyzer` and `DependencySourcesDownloader`. The flag propagation pattern must be verified as an invariant.

2. **Path Resolution Safety**: Any method accepting a user-supplied `Path` MUST handle non-existent paths. `toRealPath()` throws on non-existent paths; use `runCatching` or `toAbsolutePath().normalize()`.

3. **ORT Scope Qualification**: Scope names from `DependencyGraph.createScopes()` are always qualified. Manual filtering MUST use suffix matching or ORT's built-in filter methods.

4. **No Shell Invocation for Filesystem Operations**: Never use `ProcessBuilder` with `cmd.exe`, `sh`, or any interpreter for filesystem operations. Use Java NIO or JNA.

5. **DRY — Hashing Logic**: Hash-based identity computation on `ManagedFileInfo` must use a single shared implementation.

6. **Resource Cleanup in Error Paths**: Every `createDirectories()` preceding fallible operations MUST have corresponding cleanup in error paths.

7. **In-Memory Cache Coherence**: Cache entries pointing to filesystem resources MUST validate resource existence before returning.

8. **File Reading API Consistency**: Always use `kotlin.io.path` extensions on `Path` objects within `dev.rnett.sources.mcp`.

9. **Data Class Convention**: Never manually override `equals/hashCode` on data classes unless documented with a verifiable reason.

10. **Test Dispatcher Hygiene**: Concurrency tests MUST use `runTest` with `StandardTestDispatcher`. Use `delay()` not `Thread.sleep()`.

11. **MCP Tool Annotations**: Every MCP tool MUST set appropriate `toolAnnotations` (`readOnlyHint`, `destructiveHint`, `idempotentHint`).

12. **MCP Error Responses**: Validation errors MUST return structured `isError: true` responses with instructional recovery text.

---

## ✅ Positive Observations

- **Sound locking strategy**: The per-key `Mutex` + double-checked locking pattern correctly prevents duplicate view creation for concurrent callers. No deadlock vectors identified.
- **Clean MCP tool → service separation**: `SyncCheckTools` handles MCP protocol; `SourcesViewService` handles business logic. No cross-layer coupling.
- **Faithful A1 implementation**: No project symlinks, no `.dependencies/` directories. Matches the design doc's architectural decision exactly.
- **Proper `viewsDir` integration**: Follows the established `SourcesMcpEnvironment` pattern (next to `casDir`, `locksDir`, etc.).
- **Backward compatibility preserved**: Zero impact on existing components. All properties additive, no modifications to `DependencyAnalyzer`, `DependencySourcesDownloader`, or `FileLockManager`.
- **ORT mandate compliance**: All documented mandates (file.isFile, Unmanaged filter, directory flattening) are correctly followed.
- **Good test isolation**: Uses `@TempDir`, mockk, and clear setup methods. Tests are independent and well-structured.

---

## 📋 Review Status

- **Critical findings**: 5 (all must be addressed before merge)
- **Major findings**: 12
- **Minor findings**: 18
- **Proposed invariants**: 12
- **Open questions remaining**: 0 (all findings are self-contained and actionable)

## ❓ Open Questions for the Author

**None.** All issues identified are resolvable from the code and design artifacts alone. No user input is required to proceed with resolution.

However, one meta-question the author may wish to clarify: **Is the server bootstrap (wiring `SyncCheckTools` and `ViewCleanupService`) intentionally deferred as part of the "bootstrapping" phase?** If so, the task list items 5.1/5.2 should be un-checked and tracked separately.

---
