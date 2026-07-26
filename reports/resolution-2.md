# Resolution Plan — Review #2

## Classification Summary

| # | Title | Classification | Valid? | Complexity | Depends On |
|---|-------|---------------|--------|------------|------------|
| 1 | SyncCheckTools & ViewCleanupService unwired | Critical | ✅ Valid | Moderate | 11, 12, 13 |
| 2 | Concurrency tests violate Invariant 10 | Critical | ✅ Valid | Simple | 24, 6 (for correctness) |
| 3 | Shell metacharacter injection via `cmd /c mklink` | Major | ✅ Valid | Moderate | — |
| 4 | Untrusted `projectRoot` in `Path.of()` — UNC DoS | Major | ✅ Valid | Simple | — |
| 5 | `createDirectories()` outside `try-catch` | Major | ✅ Valid | Trivial | — |
| 6 | `parallelism` does not limit concurrency | Major | ✅ Valid | Simple | — |
| 7 | `view-index.json` scope collision + corruption | Major | ✅ Valid | Simple | — |
| 8 | `SourcesViewService` God Object (433 lines) | Major | ✅ Valid | Complex | 3, 5, 6, 7, 9, 11, 18 |
| 9 | `StalenessCheck` `""` vs `null` sentinels | Major | ✅ Valid | Trivial | — |
| 10 | Tests with zero meaningful assertions | Major | ✅ Valid | Trivial | — |
| 11 | Boolean flags → sealed class/enum | Major | ✅ Valid | Simple | — |
| 12 | Missing input validation + raw exceptions in tool handlers | Major | ✅ Valid | Simple | — |
| 13 | `readTimestamp()` manual JSON parsing | Major | ✅ Valid | Trivial | — |
| 14 | Spec coverage gaps — 4 scenarios untested | Major | ✅ Valid | Simple | 9 |
| 15 | Broken junction silently returns on nonzero exit code | Minor | ✅ Valid | Trivial | — |
| 16 | Magic number 200 → named constant | Minor | ✅ Valid | Trivial | — |
| 17 | `toFile()` / `java.io.File` API usage | Minor | ✅ Valid | Simple | — |
| 18 | `computeDepHash()` duplicates ORT setup | Minor | ✅ Valid | Simple | — |
| 19 | Test boilerplate repeated | Minor | ✅ Valid | Trivial | — |
| 20 | ViewCache eviction TOCTOU outside mutex | Minor | ✅ Valid | Trivial | — |
| 21 | Unused `cacheService` param + side-effect constructors | Minor | ✅ Valid (partial) | Trivial | — |
| 22 | `testNewViewsAreNotDeleted` timing-fragile | Minor | ✅ Valid | Trivial | — |
| 23 | `testFreshModeEvictsCacheAndReanalyzes` weak assertion | Minor | ✅ Valid | Trivial | — |
| 24 | Inconsistent `normalizationService` in concurrency tests | Minor | ✅ Valid | Trivial | — |

---

## Invalid / No-Action Findings

**None.** All 24 findings are independently verified against source code and found valid. No false positives.

The only nuance: **Finding 21** has two sub-claims:
- "Unused `cacheService` parameter" — ✅ valid. `SourcesViewService` declares `private val cacheService: DependencyCacheService` in constructor, but the class body only ever calls `DependencyCacheService.calculateCacheKey()` as a static/companion method, never using the instance. Remove it.
- "Default constructors with side effects (environment reads, directory creation)" — ✅ valid observation, but the design explicitly chooses this pattern for bootstrapping simplicity. No action: document as intentional and suggest a future factory/wiring refactor. The removal of `cacheService` is the actionable part.

---

## Spec/Design Doc Changes Required FIRST

Before any code changes, update the following OpenSpec documents:

1. **`openspec/changes/view-directory-management/design.md`**:
   - **Decision: `view-index.json` removal** (Finding 7). Document that cross-process index is removed; disk scan is the sole discovery mechanism. If index is later reintroduced, it must use scope-inclusive keys and atomic writes.
   - **Decision: `SyncMode` enum** (Finding 11). Replace free-text mentions of `fresh`/`redownload` booleans with the new `SyncMode` sealed class.
   - **Decision: Platform link service** (Finding 3, Finding 8). Document the extraction of `PlatformLinkService` and the elimination of `cmd.exe` via JNA/Kernel32.
   - **Decision: `computeDepHash` ownership** (Finding 18). Document that `DependencyAnalyzer` owns hash computation.

2. **`openspec/changes/view-directory-management/tasks.md`**:
   - Add tasks for each step in this resolution plan.
   - Mark Phase 5 ("Server Bootstrap") as *active* (not deferred) since Finding 1 requires it.

3. **`AGENTS.md`**:
   - Add the 5 proposed invariants from `review-2.md` §"Proposed Invariants/Rules":
     - **Invariant 13** (Server Bootstrap Rule)
     - **Invariant 14** (Concurrency Limiter Verification)
     - **Invariant 15** (`createDirectories()` Try-Scope Rule)
     - **Invariant 16** (Scope-Keyed Indexes Rule)
     - **Invariant 17** (Manifest Deserialization Guard)

---

## Execution Plan (in dependency order)

### Step 1: Fix `createDirectories()` placement (Finding 5)
**Rationale:** Foundational safety fix. Trivial, no dependencies, must be done before any refactoring touches the same lines.

**Files:** `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`

**Exact Changes:**
- Delete lines 95–97:
  ```kotlin
  val sessionId = UUID.randomUUID().toString()
  val baseDir = env.viewsDir.resolve(sessionId).also { it.createDirectories() }
  val sourcesDir = baseDir.resolve("sources").also { it.createDirectories() }
  ```
- Insert after line 101 (inside `try {`), before the "Pre-validate" comment (line 102):
  ```kotlin
  val sessionId = UUID.randomUUID().toString()
  val baseDir = env.viewsDir.resolve(sessionId)
  val sourcesDir = baseDir.resolve("sources")
  // ...
  try {
      baseDir.createDirectories()
      sourcesDir.createDirectories()
      // Pre-validate all dependency identifiers before processing
  ```
  This moves `sessionId`/`baseDir`/`sourcesDir` variable declarations up (keep them before `try` for scope), but moves the `.createDirectories()` calls inside the `try` block.

**Verification:**
- All existing `SourcesViewServiceTest` tests pass unchanged (22 tests).
- Run: `./gradlew :test --tests "dev.rnett.sources.mcp.SourcesViewServiceTest"`
- The `testSyncCleansUpOnAnalysisFailure` test in particular must continue to pass — its cleanup-on-failure logic now covers the directory creation step.

**Complexity:** Trivial

---

### Step 2: Normalize `StalenessCheck` sentinels to `null` (Finding 9)
**Rationale:** Eliminates the `""`/`0` vs `null` inconsistency across 7 construction sites. Any downstream code only checks `viewExists`.

**Files:** `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`

**Exact Changes:**
- Line 207: `computeDepHash` failed path. Change:
  ```kotlin
  // OLD:
  return StalenessCheck(viewExists = false, fresh = false, age = null, depHash = "", currentDepHash = "", dependencyCount = 0)
  // NEW:
  return StalenessCheck(viewExists = false, fresh = false, age = null, depHash = null, currentDepHash = currentDepHash ?: "", dependencyCount = null)
  ```
  Wait — in the current code, `currentDepHash` comes from `runCatching { computeDepHash() }.getOrNull()`, so it's already nullable. At L206-207:
  ```kotlin
  val currentDepHash = runCatching { computeDepHash(projectRoot) }.getOrNull()
      ?: return StalenessCheck(viewExists = false, ..., depHash = "", currentDepHash = "", dependencyCount = 0)
  ```
  This returns early when `computeDepHash` fails. The sentinel values should be `null`:
  ```kotlin
  ?: return StalenessCheck(viewExists = false, fresh = false, age = null, depHash = null, currentDepHash = "", dependencyCount = null)
  ```
- Line 229: no view found at all. Already uses `null` — correct. No change.
- Line 277: `normalizedRoot` resolution failed. Change `depHash = ""` and `dependencyCount = 0` to `null`:
  ```kotlin
  // OLD:
  .getOrNull() ?: return StalenessCheck(viewExists = false, fresh = false, age = null, depHash = "", currentDepHash = currentDepHash, dependencyCount = 0)
  // NEW:
  .getOrNull() ?: return StalenessCheck(viewExists = false, fresh = false, age = null, depHash = null, currentDepHash = currentDepHash, dependencyCount = null)
  ```
- Lines 244–252: `findViewOnDisk()` when `viewsDir` doesn't exist. Already uses `null` for `depHash` and `dependencyCount` — correct. No change.
- Also add a comment in `StalenessCheck` data class in `ViewModels.kt`:
  ```kotlin
  data class StalenessCheck(
      val viewExists: Boolean,
      val fresh: Boolean,
      val age: Duration?,
      val depHash: String?,          // null when viewExists == false
      val currentDepHash: String,
      val dependencyCount: Int?,     // null when viewExists == false
  )
  ```

**Verification:**
- All `SourcesViewServiceTest` tests pass.
- `SourcesViewServiceTest.testCheckNoView` — verify `depHash` is null, not `""`.
- `SourcesViewServiceTest.testCheckNonExistentProjectRoot` — same.
- Any `findViewOnDisk` test — verify `null` for no-view paths.

**Complexity:** Trivial

---

### Step 3: Minor trivial fixes in `SourcesViewService.kt` (Findings 15, 16, 20, 21)
**Rationale:** Bundle independent, single-line fixes.

**Files:** `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`

**Exact Changes:**

**Finding 16 — Named constants:**
Add to `companion object`:
```kotlin
companion object {
    private val IS_WINDOWS: Boolean = System.getProperty("os.name").lowercase().contains("win")
    private const val WINDOWS_MAX_PATH = 260
    private const val JUNCTION_PATH_WARN_THRESHOLD = 200
}
```
Then at line 347, change:
```kotlin
// OLD:
if (linkStr.length > 200) {
    logger.warn("Junction path may approach Windows MAX_PATH limit (${linkStr.length} chars): $linkStr")
}
// NEW:
if (linkStr.length > JUNCTION_PATH_WARN_THRESHOLD) {
    logger.warn("Junction path may approach Windows MAX_PATH ($WINDOWS_MAX_PATH chars) limit (${linkStr.length} chars): $linkStr")
}
```

**Finding 15 — Broken junction warning:**
At line 358, after the `if (!linkPath.exists())` block, add an `else`:
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

**Finding 20 — TOCTOU comment:**
At line 70, add a comment:
```kotlin
// Evict from in-memory cache before acquiring per-key mutex.
// This is safe because the double-check inside withLock (L78) re-validates cache state.
if (fresh || redownload) {
    viewCache.remove(cacheKey)
}
```

**Finding 21 — Remove unused `cacheService` parameter:**
Remove `cacheService` from the constructor:
```kotlin
// OLD:
class SourcesViewService(
    private val dependencyAnalyzer: DependencyAnalyzer = DependencyAnalyzer(),
    private val downloader: DependencySourcesDownloader = DependencySourcesDownloader(),
    private val cacheService: DependencyCacheService = DependencyCacheService(),
    private val env: SourcesMcpEnvironment = SourcesMcpEnvironment.fromEnv(),
    private val normalizationService: NormalizationService = NoOpNormalizationService(),
)
// NEW:
class SourcesViewService(
    private val dependencyAnalyzer: DependencyAnalyzer = DependencyAnalyzer(),
    private val downloader: DependencySourcesDownloader = DependencySourcesDownloader(),
    private val env: SourcesMcpEnvironment = SourcesMcpEnvironment.fromEnv(),
    private val normalizationService: NormalizationService = NoOpNormalizationService(),
)
```
Verify: the class body only references `DependencyCacheService.calculateCacheKey()` as a static method (line 420). No instance reference to `cacheService` exists.

Note: Also remove the import of `DependencyCacheService` if it becomes unused. However, `calculateCacheKey` is still called as a static import — check if the import is needed. At line 420: `DependencyCacheService.calculateCacheKey(managedFiles)` — this is a qualified call, so the import IS needed in `SourcesViewService.kt`.

**Verification:**
- Compile: `./gradlew :compileKotlin`
- All 22 `SourcesViewServiceTest` tests pass.

**Complexity:** Trivial

---

### Step 4: Move `computeDepHash()` to `DependencyAnalyzer` (Finding 18)
**Rationale:** Eliminates ORT setup duplication. `DependencyAnalyzer` already constructs `AnalyzerConfiguration`, `Analyzer`, filters `Unmanaged` package manager. `computeDepHash()` does the exact same thing. Reduces `SourcesViewService` ORT imports from 8 to 5 (once `PlatformLinkService` is later extracted).

**Files:**
- `src/main/kotlin/dev/rnett/sources/mcp/DependencyAnalyzer.kt`
- `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`

**Exact Changes:**

1. Add `computeDepHash(projectRoot: Path): String` to `DependencyAnalyzer`:
   ```kotlin
   fun computeDepHash(projectRoot: Path): String {
       val analyzerConfig = AnalyzerConfiguration()
       val analyzer = Analyzer(analyzerConfig)
       val managedFiles = analyzer.findManagedFiles(
           absoluteProjectPath = projectRoot.toFile(),  // ORT API constraint — see Invariant 8 exception
           packageManagers = PackageManagerFactory.ALL.values.filter { it.descriptor.id != "Unmanaged" },
           repositoryConfiguration = RepositoryConfiguration()
       )
       return DependencyCacheService.calculateCacheKey(managedFiles)
   }
   ```
   Note: Since this duplicates the `findManagedFiles` call from `analyzeDependencies()`, consider extracting a private `findProjectManagedFiles(projectRoot: Path)` helper to share between `analyzeDependencies()` and `computeDepHash()`. That reduces the duplication to zero:
   ```kotlin
   private fun findProjectManagedFiles(projectRoot: Path) = Analyzer(AnalyzerConfiguration()).findManagedFiles(
       absoluteProjectPath = projectRoot.toFile(),
       packageManagers = PackageManagerFactory.ALL.values.filter { it.descriptor.id != "Unmanaged" },
       repositoryConfiguration = RepositoryConfiguration()
   )

   fun analyzeDependencies(projectRoot: Path, ...): AnalyzerResult {
       // ...
       val managedFiles = findProjectManagedFiles(projectRoot)
       // ...
   }

   fun computeDepHash(projectRoot: Path): String {
       val managedFiles = findProjectManagedFiles(projectRoot)
       return DependencyCacheService.calculateCacheKey(managedFiles)
   }
   ```

2. In `SourcesViewService.kt`:
   - Delete the `computeDepHash()` method (lines 411–421).
   - Replace calls: `computeDepHash(projectRoot)` → `dependencyAnalyzer.computeDepHash(projectRoot)`
     - Line 99: `val depHash = computeDepHash(projectRoot)` → `val depHash = dependencyAnalyzer.computeDepHash(projectRoot)`
     - Line 206: `val currentDepHash = runCatching { computeDepHash(projectRoot) }.getOrNull()` → `val currentDepHash = runCatching { dependencyAnalyzer.computeDepHash(projectRoot) }.getOrNull()`
   - Remove now-unnecessary ORT imports if they're only used by `computeDepHash()`: `Analyzer`, `PackageManagerFactory`, `AnalyzerConfiguration`, `RepositoryConfiguration`, `Identifier`, `Scope`. Check: `Identifier` and `Scope` are also used by `extractDependenciesWithScopes()` and `collectScopeDeps()` — keep. `Analyzer` and `AnalyzerConfiguration` and `PackageManagerFactory` and `RepositoryConfiguration` — only used in the deleted `computeDepHash()` — remove these 4 imports.

**Verification:**
- All `SourcesViewServiceTest` tests pass (especially `testCheckFreshView`, `testCheckStaleView`, `testCheckNoView` which exercise the `check()` path).
- All `DependencyAnalyzer` tests pass.
- `SourcesViewService.kt` ORT imports reduced to `AnalyzerResult`, `Identifier`, `Package`, `Scope`.

**Complexity:** Simple

---

### Step 5: Eliminate shell invocation — extract `PlatformLinkService` (Finding 3)
**Rationale:** Removes `cmd.exe` dependency (Invariant 4 violation), strengthens metacharacter defense. Also a prerequisite for Finding 8 (God Object refactor).

**Files:**
- **New:** `src/main/kotlin/dev/rnett/sources/mcp/PlatformLinkService.kt`
- `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`

**Exact Changes:**

1. Create `PlatformLinkService.kt`:
   ```kotlin
   package dev.rnett.sources.mcp

   import com.sun.jna.platform.win32.Kernel32
   import com.sun.jna.platform.win32.WinNT
   import com.sun.jna.platform.win32.WinBase.SYMBOLIC_LINK_FLAG_DIRECTORY
   import org.slf4j.LoggerFactory
   import java.nio.file.Files
   import java.nio.file.Path
   import kotlin.io.path.exists

   class PlatformLinkService {
       private val logger = LoggerFactory.getLogger(PlatformLinkService::class.java)

       companion object {
           private val IS_WINDOWS: Boolean = System.getProperty("os.name").lowercase().contains("win")
           private const val WINDOWS_MAX_PATH = 260
           private const val JUNCTION_PATH_WARN_THRESHOLD = 200
       }

       /**
        * Creates a directory junction (Windows) or symbolic link (Unix/Mac).
        * On Windows 10 v1703+ with Developer Mode, uses [Kernel32.CreateSymbolicLinkW].
        * Falls back to [Files.createSymbolicLink] on non-Windows.
        * No shell invocation — all done via JNA or NIO.
        */
       fun createDirectoryLink(linkPath: Path, targetPath: Path) {
           if (linkPath.exists()) return

           if (IS_WINDOWS) {
               createWindowsJunction(linkPath, targetPath)
           } else {
               Files.createSymbolicLink(linkPath, targetPath)
           }
       }

       private fun createWindowsJunction(linkPath: Path, targetPath: Path) {
           val linkStr = linkPath.toAbsolutePath().normalize().toString()
           val targetStr = targetPath.toAbsolutePath().normalize().toString()

           if (linkStr.length > JUNCTION_PATH_WARN_THRESHOLD) {
               logger.warn(
                   "Junction path may approach Windows MAX_PATH ($WINDOWS_MAX_PATH chars) limit " +
                   "(${linkStr.length} chars): $linkStr"
               )
           }

           // Use JNA Kernel32.CreateSymbolicLinkW with SYMBOLIC_LINK_FLAG_DIRECTORY.
           // This creates a directory junction without requiring admin (Windows 10 v1703+ w/Developer Mode).
           // If it fails, attempt NIO createSymbolicLink as fallback.
           val created = Kernel32.INSTANCE.CreateSymbolicLinkW(
               linkStr,
               targetStr,
               SYMBOLIC_LINK_FLAG_DIRECTORY
           )

           if (!created) {
               val errorCode = Kernel32.INSTANCE.GetLastError()
               if (!linkPath.exists()) {
                   throw RuntimeException(
                       "Failed to create junction $linkStr -> $targetStr. " +
                       "Windows error code: $errorCode"
                   )
               } else {
                   logger.warn(
                       "CreateSymbolicLinkW returned false (error $errorCode) but junction exists at $linkStr. " +
                       "May point to wrong target."
                   )
               }
           }
       }
   }
   ```

   **Dependency note:** Requires adding `net.java.dev.jna:jna-platform` to `build.gradle.kts` (or wherever dependencies are declared). Add:
   ```kotlin
   implementation("net.java.dev.jna:jna-platform:5.15.0")
   ```

2. In `SourcesViewService.kt`:
   - Replace `private fun createLink(...)` and `private fun createJunction(...)` and `private fun validateSafeForShell(...)` with:
     ```kotlin
     private val linkService = PlatformLinkService()
     ```
   - At the single call site (line 178): `createLink(linkDir, casPath)` → `linkService.createDirectoryLink(linkDir, casPath)`.
   - Remove the `IS_WINDOWS` companion object value (now in `PlatformLinkService`).
   - Remove shell validation from the identifier validation loop (lines 109–110: `validateSafeForShell(value, fieldName)` is no longer needed since identifiers never touch a shell). Keep the path-separator and path-traversal checks.
   - Remove the `validateSafeForShell` method entirely.
   - Remove logger if it's no longer used — but it still is (for warnings at L70, L78, L191, L430).

**Alternative (if JNA is not feasible):**
Keep `cmd /c mklink /J` but switch to an **allowlist** for path validation and add `/d` flag:
- `validateSafeForShell` uses `Regex("^[a-zA-Z0-9._\\- \\[\\]]+$")` (allowlist of known-safe chars).
- ProcessBuilder uses `"cmd", "/d", "/c", "mklink", "/J", linkStr, targetStr`.
The JNA approach is **strongly preferred** per Invariant 4.

**Verification:**
- All 22 `SourcesViewServiceTest` tests pass.
- `testRejectsShellMetacharactersInDependencyName` still catches unsafe identifiers (now through path traversal/separator checks).
- If keep shell approach: add tests for `"`, `(`, `)`, newline in identifiers → all must reject.
- On Windows: `testUuidKeyedViewDirectoryCreation` creates junctions and linked files are accessible.
- On Unix/Mac: `Files.createSymbolicLink` path still works (unchanged logic).

**Complexity:** Moderate (due to JNA dependency and Windows API integration)

---

### Step 6: Fix `view-index.json` — remove it entirely (Finding 7)
**Rationale:** Simplest correct fix. The index is buggy (scope collision), has no integrity (corrupted JSON → all entries lost), and is redundant with the disk scan which already works correctly. Remove now; reintroduce later only if profiling proves the disk scan is too slow.

**Files:** `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`

**Exact Changes:**
- Lines 142–145 in `sync()`: Delete the three lines that write to the index:
  ```kotlin
  // DELETE:
  val normalizedRoot = projectRoot.toAbsolutePath().normalize().toString()
  val index = readViewIndex()
  index[normalizedRoot] = sessionId
  writeViewIndex(index)
  ```
- Lines 254–274 in `findViewOnDisk()`: Delete the index-based lookup block (lines 254–274):
  ```kotlin
  // DELETE:
  val index = readViewIndex()
  val sessionIdFromIndex = index[projectRoot.toAbsolutePath().normalize().toString()]
  if (sessionIdFromIndex != null) {
      val indexedManifestFile = viewsDir.resolve(sessionIdFromIndex).resolve("manifest.json")
      if (indexedManifestFile.exists()) {
          val indexedManifest = runCatching {
              json.decodeFromString(ViewManifest.serializer(), indexedManifestFile.readText())
          }.getOrNull()
          if (indexedManifest != null) {
              val age = computeAge(indexedManifest.timestamp)
              return StalenessCheck(
                  viewExists = true,
                  fresh = indexedManifest.depHash == currentDepHash,
                  age = age,
                  depHash = indexedManifest.depHash,
                  currentDepHash = currentDepHash,
                  dependencyCount = indexedManifest.dependencies.size
              )
          }
      }
  }
  ```
  The remaining code (lines 276–304) is the disk scan loop — this is now the sole discovery mechanism.
- Delete `readViewIndex()` method (lines 371–377) and `writeViewIndex()` method (lines 379–385).
- Remove unused import: `FileLockManager` (if it was only imported for `writeViewIndex` — check: it is only used in `writeViewIndex`, so yes, remove).

**Verification:**
- All `SourcesViewServiceTest` tests pass.
- `testCheckViewOnDiskButNotInMemoryCache` — still passes (disk scan finds the view without the index).
- Multi-scope test: sync with `compile`, then sync with `test`, then `check()` finds both independently via disk scan.
- No reference to `view-index.json` remains in the codebase.

**Complexity:** Simple

---

### Step 7: Add `SyncMode` enum (Finding 11)
**Rationale:** Eliminates boolean blindness. The two-boolean API has 4 modes; the enum names them explicitly. MCP tool layer maps string params to the enum internally.

**Files:**
- `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`
- `src/main/kotlin/dev/rnett/sources/mcp/SyncCheckTools.kt` (Step 9 will consume it)

**Exact Changes:**

1. Add enum in `SourcesViewService.kt`:
   ```kotlin
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
   ```

2. Change `sync()` signature:
   ```kotlin
   // OLD:
   suspend fun sync(
       projectRoot: Path,
       scopes: Set<String> = emptySet(),
       fresh: Boolean = false,
       redownload: Boolean = false
   ): SessionView
   // NEW:
   suspend fun sync(
       projectRoot: Path,
       scopes: Set<String> = emptySet(),
       mode: SyncMode = SyncMode.CACHED,
   ): SessionView
   ```

3. Update method body:
   ```kotlin
   // OLD:
   if (fresh || redownload) viewCache.remove(cacheKey)
   // NEW:
   if (mode != SyncMode.CACHED) viewCache.remove(cacheKey)

   // OLD:
   val result = dependencyAnalyzer.analyzeDependencies(projectRoot, force = fresh)
   // NEW:
   val result = dependencyAnalyzer.analyzeDependencies(
       projectRoot,
       force = mode == SyncMode.REFRESH_ANALYSIS || mode == SyncMode.FULL_REFRESH
   )

   // OLD:
   val dependencies = downloadAndLinkDependencies(filteredPackages, sourcesDir, redownload)
   // NEW:
   val dependencies = downloadAndLinkDependencies(
       filteredPackages,
       sourcesDir,
       redownload = mode == SyncMode.REDOWNLOAD_SOURCES || mode == SyncMode.FULL_REFRESH
   )
   ```

4. Update KDoc for `sync()` — remove `@param fresh` and `@param redownload`, add `@param mode`.

**Verification:**
- All 22 `SourcesViewServiceTest` tests updated to use `mode = SyncMode.XXX` instead of `fresh = true`/`redownload = true`.
- `testFreshModeEvictsCacheAndReanalyzes` → uses `mode = SyncMode.REFRESH_ANALYSIS`.
- `testRedownloadModeReDownloads` → uses `mode = SyncMode.REDOWNLOAD_SOURCES`.
- `testBothFreshAndRedownload` (new from Step 12/Finding 14) → uses `mode = SyncMode.FULL_REFRESH`.

**Complexity:** Simple

---

### Step 8: Fix `ViewCleanupService.readTimestamp()` — use `ViewManifest` serializer (Finding 13)
**Rationale:** Eliminates fragile manual JSON parsing. Single source of truth for manifest schema.

**Files:** `src/main/kotlin/dev/rnett/sources/mcp/ViewCleanupService.kt`

**Exact Changes:**
Replace `readTimestamp()` body:
```kotlin
// OLD (lines 91-103):
private fun readTimestamp(viewDir: Path): Instant? {
    val manifestFile = viewDir.resolve("manifest.json")
    if (manifestFile.exists()) {
        try {
            val element = json.parseToJsonElement(manifestFile.readText())
            val timestampValue = element.jsonObject["timestamp"]?.jsonPrimitive?.content
            if (timestampValue != null) return Instant.parse(timestampValue)
        } catch (e: Exception) {
            logger.debug("Failed to read timestamp from manifest for view ${viewDir.name}, falling back to last-modified", e)
        }
    }
    return try {
        viewDir.getLastModifiedTime().toInstant()
    } catch (e: Exception) {
        logger.debug("Failed to read last-modified time for view ${viewDir.name}", e)
        null
    }
}

// NEW:
private fun readTimestamp(viewDir: Path): Instant? {
    val manifestFile = viewDir.resolve("manifest.json")
    if (manifestFile.exists()) {
        try {
            val manifest = json.decodeFromString(ViewManifest.serializer(), manifestFile.readText())
            return Instant.parse(manifest.timestamp)
        } catch (e: Exception) {
            logger.debug("Failed to read timestamp from manifest for view ${viewDir.name}, falling back to last-modified", e)
        }
    }
    return try {
        viewDir.getLastModifiedTime().toInstant()
    } catch (e: Exception) {
        logger.debug("Failed to read last-modified time for view ${viewDir.name}", e)
        null
    }
}
```
- The `json` instance already has `ignoreUnknownKeys = true` (line 34), so adding new fields to `ViewManifest` won't break timestamp extraction.
- Remove unused imports: `kotlinx.serialization.json.jsonObject`, `kotlinx.serialization.json.jsonPrimitive` (now only `kotlinx.serialization.json.Json` needed for `json` instance).

**Verification:**
- All `ViewCleanupServiceTest` tests pass.
- `testOldViewsAreDeleted` — manifests with `timestamp` are correctly parsed.
- `testCleanupWithInvalidManifest` — invalid JSON still caught by `try-catch`.
- New test: create manifest with extra unknown fields → timestamp still extracted correctly (via `ignoreUnknownKeys = true`).

**Complexity:** Trivial

---

### Step 9: Add input validation and error handling to `SyncCheckTools` handlers (Finding 12)
**Rationale:** MCP tool handlers must validate inputs and return structured errors per Invariant 12. Currently: blank strings pass, no length limit, no try-catch around service calls, raw stack traces leak filesystem paths.

**Files:** `src/main/kotlin/dev/rnett/sources/mcp/SyncCheckTools.kt`

**Exact Changes:**

1. Add a shared `extractProjectRoot(args: JsonObject): Path` helper that validates:
   ```kotlin
   private fun extractProjectRoot(args: JsonObject): Path {
       val content = args["projectRoot"]?.jsonPrimitive?.content
       if (content == null || content.isBlank()) {
           throw IllegalArgumentException("Error: 'projectRoot' is required and must not be blank.")
       }
       require(content.length <= 4096) {
           "Error: 'projectRoot' path exceeds maximum length of 4096 characters."
       }
       val path = Path.of(content).toAbsolutePath().normalize()
       // Reject non-existent paths with a clear message (check() tolerates this, but sync() doesn't)
       return path
   }
   ```
   Note: Don't reject non-existent here — let the service layer decide. `sync()` will throw at `require(projectRoot.exists())`, `check()` handles it gracefully. But add a note that the caller should handle this.

2. In `syncDependenciesHandler`:
   - Replace lines 101–108 with the helper.
   - Wrap the `viewService.sync()` call in try-catch:
     ```kotlin
     try {
         val sessionView = viewService.sync(projectRoot, scopes, mode)
         // ... build output ...
         return CallToolResult(content = listOf(TextContent(text = output)))
     } catch (e: IllegalArgumentException) {
         return CallToolResult(
             content = listOf(TextContent("Error: ${e.message}")),
             isError = true,
         )
     } catch (e: Exception) {
         logger.error("sync_dependencies failed for $projectRoot", e)
         return CallToolResult(
             content = listOf(TextContent("Error: Internal error while syncing dependencies. Check server logs for details.")),
             isError = true,
         )
     }
     ```
   - Update the handler to use `SyncMode` (from Step 7): map `fresh`/`redownload` to the enum:
     ```kotlin
     val mode = when {
         (freshArg ?: false) && (redownloadArg ?: false) -> SyncMode.FULL_REFRESH
         freshArg ?: false -> SyncMode.REFRESH_ANALYSIS
         redownloadArg ?: false -> SyncMode.REDOWNLOAD_SOURCES
         else -> SyncMode.CACHED
     }
     ```

3. In `checkDependenciesHandler`:
   - Replace lines 139–146 with the helper.
   - Wrap `viewService.check()` in try-catch:
     ```kotlin
     try {
         val stalenessCheck = viewService.check(projectRoot)
         // ... build output ...
         return CallToolResult(content = listOf(TextContent(text = output)))
     } catch (e: Exception) {
         logger.error("check_dependencies failed for $projectRoot", e)
         return CallToolResult(
             content = listOf(TextContent("Error: Internal error while checking dependencies. Check server logs for details.")),
             isError = true,
         )
     }
     ```

4. Add `logger`:
   ```kotlin
   private val logger = LoggerFactory.getLogger(SyncCheckTools::class.java)
   ```
   And import `org.slf4j.LoggerFactory`.

**Gotchas:** The `SyncMode` enum mapping uses the OLD parameter names (`fresh`, `redownload`) in the MCP tool input schema. The tool description already uses these names — keep them for backward compat in the MCP interface. The enum mapping happens internally.

**Verification:**
- SyncCheckTools tests: handlers with blank projectRoot → return `isError = true`.
- Handlers with 100KB projectRoot string → return `isError = true`.
- Handlers with whitespace-only projectRoot → return `isError = true`.
- Handlers with non-existent path for `sync_dependencies` → returns structured error, not raw stack trace.
- Handlers with thrown exception from `viewService` → returns sanitized message, no filesystem paths leaked.
- `testCheckNonExistentProjectRoot` — still works.

**Complexity:** Simple

---

### Step 10: Fix `parallelism` — use `Semaphore` (Finding 6)
**Rationale:** `chunked(N)` + `flatMap` + `awaitAll()` launches all downloads simultaneously. Replace with `Semaphore`-guarded concurrency.

**Files:** `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`

**Exact Changes:**
Replace `downloadAndLinkDependencies()` body:
```kotlin
// NEW:
private suspend fun downloadAndLinkDependencies(
    packages: List<Package>,
    sourcesDir: Path,
    redownload: Boolean
): List<ViewDependency> = coroutineScope {
    val semaphore = Semaphore(parallelism.coerceAtLeast(1))

    packages.map { pkg ->
        async {
            semaphore.withPermit {
                try {
                    val casPath = downloader.downloadSources(pkg, forceRedownload = redownload)
                    val casKey = casPath.fileName.toString()

                    val ecosystem = pkg.id.type
                    val namespace = pkg.id.namespace
                    val name = pkg.id.name
                    val version = pkg.id.version

                    val linkDir = sourcesDir.resolve("$ecosystem/$namespace/$name/$version").normalize()
                    linkDir.parent.createDirectories()

                    if (!linkDir.exists()) {
                        linkService.createDirectoryLink(linkDir, casPath)
                    }

                    ViewDependency(
                        id = pkg.id.toCoordinates(),
                        casKey = casKey,
                        ecosystem = ecosystem,
                        namespace = namespace,
                        name = name,
                        version = version
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to process dependency ${pkg.id.toCoordinates()}: ${e.message}")
                    null
                }
            }
        }
    }.awaitAll().filterNotNull()
}
```

**Imports:** Add `import kotlinx.coroutines.sync.Semaphore` and `import kotlinx.coroutines.sync.withPermit`.

**Note:** The `(Dispatchers.IO)` from `async(Dispatchers.IO)` is removed — `async` in `coroutineScope` uses the caller's dispatcher by default. The `Semaphore` limits how many coroutines are doing I/O simultaneously, and the downstream `downloadSources` call itself will internally dispatch to I/O threads as needed.

**Verification:**
- All 22 `SourcesViewServiceTest` tests pass.
- New test (from Step 12 / Finding 14 for `parallelism` verification): 20 mock packages, `parallelism = 2`, track concurrent `downloadSources` invocations with `AtomicInteger` — max never exceeds 2. **This test MUST FAIL with the old implementation and PASS with the new one.**
- Run large sync with 100+ packages on a limited-connection machine — no connection exhaustion.

**Complexity:** Simple

---

### Step 11: Add `projectRoot` safety validation in `findViewOnDisk()` (Finding 4)
**Rationale:** `manifest.projectRoot` is deserialized from `manifest.json` and fed to `Path.of()` without validation. A planted manifest with a UNC path causes SMB resolution (DoS, credential leak).

**Files:** `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt`

**Exact Changes:**
In `findViewOnDisk()`, line 290, add validation before `Path.of()`:
```kotlin
// OLD:
if (runCatching { Path.of(manifest.projectRoot).toAbsolutePath().normalize().toRealPath().toString() }.getOrNull() == normalizedRoot) {

// NEW:
val projectRootStr = manifest.projectRoot
// Validate before constructing Path (prevents UNC resolution, DoS, credential leakage)
val safeToResolve = projectRootStr.length <= 4096 &&
    !projectRootStr.startsWith("\\\\") &&
    (IS_WINDOWS && projectRootStr.matches(Regex("^[A-Za-z]:\\\\.*")) ||
     !IS_WINDOWS && projectRootStr.startsWith("/"))

if (safeToResolve && runCatching {
    Path.of(projectRootStr).toAbsolutePath().normalize().toRealPath().toString()
}.getOrNull() == normalizedRoot) {
```

Note: `IS_WINDOWS` needs to be accessible. After Step 5, it lives in `PlatformLinkService`. Either:
- Make `IS_WINDOWS` a top-level constant in a shared location, OR
- Move the validation to a method on `PlatformLinkService`, OR
- Simply use `System.getProperty("os.name").lowercase().contains("win")` inline here.

Simplest approach: use the inline check since `PlatformLinkService` is being created in Step 5. Or better: move `IS_WINDOWS` to a top-level constant in a new tiny file `Platform.kt`:
```kotlin
package dev.rnett.sources.mcp

val IS_WINDOWS: Boolean = System.getProperty("os.name").lowercase().contains("win")
val WINDOWS_MAX_PATH = 260
```
Both `PlatformLinkService` and `SourcesViewService` can reference this. This is a small refactoring worth doing as part of Step 5 or 11.

**Verification:**
- `SourcesViewServiceTest` all pass.
- New test: create manifest with `"projectRoot": "\\\\192.168.1.100\\share\\project"`, call `check()`, verify returns `viewExists = false` without hanging (should complete in <1s).
- Manifest with legitimate `"C:\\Users\\test\\project"` still matches correctly.
- Manifest with 100KB `projectRoot` string → `check()` returns safely, no OOM.

**Complexity:** Simple

---

### Step 12: Add spec coverage tests (Finding 14)
**Rationale:** Four documented scenarios from the OpenSpec spec have no test coverage. Add targeted tests.

**Files:**
- `src/test/kotlin/dev/rnett/sources/mcp/SourcesViewServiceTest.kt` (4 new tests)
- `src/test/kotlin/dev/rnett/sources/mcp/SourcesViewServiceConcurrencyTest.kt` (if parallelism test goes here)

**Exact Changes:**

1. **`testBothFreshAndRedownload`** (→ `testModeFullRefresh`):
   ```kotlin
   @Test
   fun testModeFullRefresh() = runTest {
       val pkgId = makePkgId("full-refresh-lib")
       val pkg = makePkg(pkgId)
       val result = makeAnalyzerResult(pkg to setOf("compile"))
       every { analyzer.analyzeDependencies(tempDir, force = any()) } returns result
       val casDir = env.casDir.resolve("full-refresh-hash").also { it.createDirectories() }
       casDir.resolve("File.kt").writeText("full")
       every { downloader.downloadSources(pkg, forceRedownload = any()) } returns casDir

       service.sync(tempDir, mode = SyncMode.FULL_REFRESH)

       verify(exactly = 1) {
           analyzer.analyzeDependencies(tempDir, force = true)
       }
       verify(exactly = 1) {
           downloader.downloadSources(pkg, forceRedownload = true)
       }
   }
   ```

2. **`testFreshWhenCacheMissing`** (→ `testModeRefreshAnalysisWhenCacheMissing`):
   ```kotlin
   @Test
   fun testModeRefreshAnalysisWhenCacheMissing() = runTest {
       val pkgId = makePkgId("cache-miss-lib")
       val pkg = makePkg(pkgId)
       val result = makeAnalyzerResult(pkg to setOf("compile"))
       // Mock cache service to always return null (simulates missing cache)
       every { analyzer.analyzeDependencies(tempDir, force = false) } returns result

       val casDir = env.casDir.resolve("cache-miss-hash").also { it.createDirectories() }
       casDir.resolve("File.kt").writeText("miss")
       every { downloader.downloadSources(pkg, forceRedownload = any()) } returns casDir

       val view = service.sync(tempDir, mode = SyncMode.REFRESH_ANALYSIS)

       assertNotNull(view)
       verify(exactly = 1) { analyzer.analyzeDependencies(tempDir, force = true) }
   }
   ```
   Note: This test is slightly subtle — `force = false` on `CACHED` mode, `force = true` on `REFRESH_ANALYSIS`. The test verifies the correct `force` value is passed.

3. **`testCheckWithNoAnalysisCache`**:
   Verify `check()` returns meaningful data even when analysis cache is unavailable. The current `SourcesViewService.check()` calls `computeDepHash()` which hits `DependencyAnalyzer.computeDepHash()` — but no cache lookup. So this code path naturally works. The test should verify that `check()` doesn't throw when no prior `sync()` was done:
   ```kotlin
   @Test
   fun testCheckReturnsNoViewWhenNoAnalysisCache() = runTest {
       val staleness = service.check(tempDir)
       assertTrue(!staleness.viewExists)
       // currentDepHash should still be populated (from computeDepHash, not from cache)
       assertTrue(staleness.currentDepHash.isNotEmpty())
   }
   ```

4. **`testCheckDoesNotDownloadOrAnalyze`**:
   ```kotlin
   @Test
   fun testCheckDoesNotDownloadOrAnalyze() = runTest {
       // Sync first to create a view
       val pkgId = makePkgId("no-download-lib")
       val pkg = makePkg(pkgId)
       val result = makeAnalyzerResult(pkg to setOf("compile"))
       every { analyzer.analyzeDependencies(tempDir, force = any()) } returns result
       val casDir = env.casDir.resolve("no-dl-hash").also { it.createDirectories() }
       casDir.resolve("File.kt").writeText("nodl")
       every { downloader.downloadSources(pkg, forceRedownload = any()) } returns casDir
       service.sync(tempDir)

       // Now check — should not trigger analysis or download
       val staleness = service.check(tempDir)

       assertTrue(staleness.viewExists)
       // Verify only the initial sync called analyzeDependencies, not check()
       verify(exactly = 1) { analyzer.analyzeDependencies(any()) }
       verify(exactly = 1) { downloader.downloadSources(any()) }
   }
   ```

**Verification:**
- All new tests pass.
- `./gradlew :test --tests "dev.rnett.sources.mcp.SourcesViewServiceTest"` — 26 tests (22 existing + 4 new).

**Complexity:** Simple

---

### Step 13: Fix `toFile()` / `java.io.File` API usage (Finding 17)
**Rationale:** Invariant 8 mandates `kotlin.io.path` extensions. Fix all non-ORT-API usages.

**Files:**
- `src/main/kotlin/dev/rnett/sources/mcp/DependencySourcesDownloader.kt`
- `src/test/kotlin/dev/rnett/sources/mcp/SourcesViewServiceTest.kt`
- `src/test/kotlin/dev/rnett/sources/mcp/SourcesViewServiceConcurrencyTest.kt`
- `src/test/kotlin/dev/rnett/sources/mcp/ViewCleanupServiceTest.kt`
- `src/test/kotlin/dev/rnett/sources/mcp/SourcesViewServiceIntegrationTest.kt` (if it exists)

**Exact Changes:**

**`DependencySourcesDownloader.kt`:**
- Line 70: `tempDir.toFile().deleteRecursively()` → `tempDir.deleteRecursively()`
- Line 78: `downloader.download(pkg, tempDir.toFile())` — **ORT API constraint, keep as is.** Add comment `// ORT API requires java.io.File`.
- Lines 109, 113, 116: `flattenSubdirectory()` method — rewrite using `kotlin.io.path`:
  ```kotlin
  // OLD:
  private fun flattenSubdirectory(dir: Path) {
      val files = dir.toFile().listFiles() ?: return
      if (files.size == 1 && files[0].isDirectory) {
          val subDir = files[0]
          logger.info("Flattening subdirectory: ${subDir.name}")
          val subFiles = subDir.listFiles() ?: return
          for (file in subFiles) {
              val target = dir.resolve(file.name)
              java.nio.file.Files.move(file.toPath(), target)
          }
          subDir.delete()
      }
  }
  // NEW:
  private fun flattenSubdirectory(dir: Path) {
      val entries = Files.list(dir).use { it.toList() }
      if (entries.size != 1 || !entries[0].isDirectory()) return
      val subDir = entries[0]
      logger.info("Flattening subdirectory: ${subDir.name}")
      val subEntries = Files.list(subDir).use { it.toList() }
      for (entry in subEntries) {
          val target = dir.resolve(entry.fileName)
          Files.move(entry, target)
      }
      subDir.deleteRecursively()
  }
  ```
  Add imports: `import java.nio.file.Files` (already present), `import java.nio.file.Path` (already present), `import kotlin.io.path.deleteRecursively` (already present), `import kotlin.io.path.fileName`, `import kotlin.io.path.isDirectory` (already present).

**`SourcesViewServiceTest.kt`:**
- Line 37: `it.toFile().mkdirs()` → `it.createDirectories()` (import already present).
- Line 68: `it.toFile().mkdirs()` → `it.createDirectories()`.
- Line 69: `it.toFile().mkdirs()` → `it.createDirectories()`.
- Line 147: `linkPath.resolve("Source.kt").toFile().readText()` → `linkPath.resolve("Source.kt").readText()`.
- Line 433: `viewDir.toFile().deleteRecursively()` → `viewDir.deleteRecursively()`.
- Line 464: `viewDir.toFile().deleteRecursively()` → `viewDir.deleteRecursively()`.

**`SourcesViewServiceConcurrencyTest.kt`:**
- Line 37: `it.toFile().mkdirs()` → `it.createDirectories()`.
- Line 68: `it.toFile().mkdirs()` → `it.createDirectories()`.
- Line 69: `it.toFile().mkdirs()` → `it.createDirectories()`.

**`ViewCleanupServiceTest.kt`:**
- Line 54: `viewsDir.toFile().deleteRecursively()` → `viewsDir.deleteRecursively()`.
- Line 94: `viewDir.toFile().deleteRecursively()` → `viewDir.deleteRecursively()`.

**`SourcesViewServiceTest.kt` line 466:** `viewsDir.toFile().listFiles()?.filter { ... }` — this is in the `testSyncCleansUpOnAnalysisFailure` test. Replace with `Files.list(viewsDir).use { stream -> stream.filter { it.isDirectory() && ... }.toList() }` but this is more complex. Since this is a test assertion checking cleanup, a simpler approach for now: use `Files.list(viewsDir)`:
```kotlin
// OLD:
val remainingDirs = viewsDir.toFile().listFiles()?.filter { it.isDirectory && it.name != "lost+found" } ?: emptyList()
// NEW:
val remainingDirs = Files.list(viewsDir).use { stream ->
    stream.filter { Files.isDirectory(it) && it.fileName.toString() != "lost+found" }.toList()
}
```
Add import `import java.nio.file.Files` (already present). Add `import kotlin.io.path.name` or use `fileName.toString()`.

**Verification:**
- All tests pass: `./gradlew :test`
- Grep for `toFile()` in non-ORT-API contexts returns empty:
  ```bash
  rg 'toFile\(\)' src/main/kotlin/ src/test/kotlin/ --include='*.kt'
  ```
  Only allowed exceptions: ORT API calls (`projectRoot.toFile()` in `DependencyAnalyzer.kt` and `downloader.download(pkg, tempDir.toFile())` in `DependencySourcesDownloader.kt`).

**Complexity:** Simple (many files, but each change is trivial)

---

### Step 14: Fix concurrency tests (Finding 2, Finding 24)
**Rationale:** Tests currently use `Thread.sleep()`, `Dispatchers.IO`, wrong MockK argument matchers, and inconsistent `normalizationService`. Must be rewritten to use virtual time and correct mocks.

**Files:** `src/test/kotlin/dev/rnett/sources/mcp/SourcesViewServiceConcurrencyTest.kt`

**Exact Changes:**
Rewrite both tests completely:

```kotlin
package dev.rnett.sources.mcp

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
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
import org.ossreviewtoolkit.model.PackageLinkage
import org.ossreviewtoolkit.model.RemoteArtifact
import org.ossreviewtoolkit.model.VcsInfo
import java.nio.file.Path
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
        val latch = CompletableDeferred<Unit>()

        val pkg = makePkg(makePkgId("concurrent-lib"))
        val result = makeMinimalAnalyzerResult(pkg)
        val mockAnalyzer = mockk<DependencyAnalyzer> {
            coEvery { analyzeDependencies(any(), any(), any()) } coAnswers {
                latch.await()  // Suspend until latch completes, simulating work
                result
            }
        }
        val mockDownloader = mockk<DependencySourcesDownloader>(relaxed = true) {
            // downloadSources returns a valid Path
            every { downloadSources(any(), any()) } returns tempDir.resolve("cas").also { it.createDirectories(); it.resolve("File.kt").writeText("") }
        }

        val env = SourcesMcpEnvironment(tempDir)
        val service = SourcesViewService(
            dependencyAnalyzer = mockAnalyzer,
            downloader = mockDownloader,
            env = env,
            normalizationService = NoOpNormalizationService(),
        )

        val projectDir = tempDir.resolve("project").also { it.createDirectories() }

        // Launch 5 concurrent syncs for same key
        val jobs = (1..5).map {
            async(testDispatcher) {
                service.sync(projectDir)
            }
        }

        // Advance time slightly to let all coroutines reach the latch
        testScheduler.advanceUntilIdle()

        // Release the latch — only ONE coroutine should proceed with analysis
        latch.complete(Unit)
        testScheduler.advanceUntilIdle()

        val results = jobs.awaitAll()
        val first = results.first()
        results.forEach { assertTrue(it === first, "All results should be the same object") }

        // Analysis must have been called exactly once for this key
        coVerify(exactly = 1) { mockAnalyzer.analyzeDependencies(any(), any(), any()) }
    }

    @Test
    fun testConcurrentSyncDifferentKeysProceedIndependently() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)

        val pkg = makePkg(makePkgId("diff-key-lib"))
        val result = makeMinimalAnalyzerResult(pkg)
        val mockAnalyzer = mockk<DependencyAnalyzer> {
            coEvery { analyzeDependencies(any(), any(), any()) } returns result
        }
        val mockDownloader = mockk<DependencySourcesDownloader>(relaxed = true) {
            every { downloadSources(any(), any()) } returns tempDir.resolve("cas").also { it.createDirectories(); it.resolve("File.kt").writeText("") }
        }

        val env = SourcesMcpEnvironment(tempDir)
        val service = SourcesViewService(
            dependencyAnalyzer = mockAnalyzer,
            downloader = mockDownloader,
            env = env,
            normalizationService = NoOpNormalizationService(),
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
        coVerify(exactly = 2) { mockAnalyzer.analyzeDependencies(any(), any(), any()) }
    }
}
```

**Key changes:**
1. **`StandardTestDispatcher` + `async(testDispatcher)`** — virtual time control, no `Dispatchers.IO`.
2. **`CompletableDeferred<Unit>` latch** — replaces `Thread.sleep(100)` with proper coroutine suspension.
3. **`testScheduler.advanceUntilIdle()`** — controls virtual time deterministically.
4. **`coEvery { analyzeDependencies(any(), any(), any()) }`** — matches all 3 args (projectRoot, packageManagerOptions=default, force).
5. **`coVerify`** — proper coroutine-aware verification.
6. **`normalizationService = NoOpNormalizationService()`** — consistent with `SourcesViewServiceTest`.
7. **Real `AnalyzerResult`** — not `mockk(relaxed = true)` cast.

**Gotchas:** If `async(testDispatcher)` is not sufficient (some internal dispatcher switching in the service), consider making `SourcesViewService` accept a `CoroutineDispatcher` parameter. Current code doesn't do internal dispatcher switching in the sync path except in `downloadAndLinkDependencies`, which Step 10 removed `Dispatchers.IO` from.

**Verification:**
- Both tests pass deterministically.
- `testConcurrentSyncSameKeyCreatesOneView` — `analyzeDependencies` called exactly once.
- `testConcurrentSyncDifferentKeysProceedIndependently` — `analyzeDependencies` called exactly twice.
- Tests complete in virtual time (wall-clock ~0ms).

**Complexity:** Simple

---

### Step 15: Fix assertion-less and weak tests (Finding 10, 22, 23)
**Rationale:** Tests that pass without verifying anything create false CI confidence.

**Files:**
- `src/test/kotlin/dev/rnett/sources/mcp/ViewCleanupServiceTest.kt`
- `src/test/kotlin/dev/rnett/sources/mcp/ViewModelsTest.kt`
- `src/test/kotlin/dev/rnett/sources/mcp/SourcesViewServiceTest.kt`

**Exact Changes:**

**Finding 10a — `testCleanupWithNoViewsDirectory`:**
```kotlin
// OLD (lines 51-58):
@Test
fun testCleanupWithNoViewsDirectory() {
    val env = SourcesMcpEnvironment(tempDir)
    val viewsDir = env.viewsDir
    viewsDir.toFile().deleteRecursively()
    val service = ViewCleanupService(env, maxAge = Duration.ofSeconds(1))
    service.cleanup()
}

// NEW:
@Test
fun testCleanupWithNoViewsDirectory() {
    val env = SourcesMcpEnvironment(tempDir)
    val viewsDir = env.viewsDir
    viewsDir.deleteRecursively()
    val service = ViewCleanupService(env, maxAge = Duration.ofSeconds(1))
    service.cleanup()
    // Verify cleanup does NOT recreate the views directory
    assertFalse(viewsDir.exists(), "cleanup should not recreate deleted views directory")
}
```

**Finding 10b — `testStopPreventsFurtherCleanup`:**
Rename and add a meaningful assertion:
```kotlin
// OLD (lines 118-128):
@Test
fun testStopPreventsFurtherCleanup() {
    val service = ViewCleanupService(env, maxAge = Duration.ofHours(24))
    service.stop()
    assertTrue(true)
}

// NEW:
@Test
fun testStopIsNoOpOnUnstartedService() {
    val service = ViewCleanupService(env, maxAge = Duration.ofHours(24))
    // stop() on a never-started service should not throw or hang
    service.stop()
    // If we reach here without exception, test passes
}
```

**Finding 10c — Delete 3 `StalenessCheck` constructor tests:**
Delete these tests from `ViewModelsTest.kt` (lines 57–147): `testStalenessCheckFreshViewExists`, `testStalenessCheckNoViewExists`, `testStalenessCheckStaleViewExists`. These only verify Kotlin data class property access, which is trivially correct. Correctness is verified implicitly by `SourcesViewServiceTest` tests that assert `StalenessCheck` return values.

**Finding 22 — `testNewViewsAreNotDeleted` timing:**
```kotlin
// OLD (line 43):
val service = ViewCleanupService(env, maxAge = Duration.ofSeconds(10))
// NEW:
val service = ViewCleanupService(env, maxAge = Duration.ofMinutes(5))
```

**Finding 23 — `testFreshModeEvictsCacheAndReanalyzes` weak assertion:**
Strengthen lines 162–167:
```kotlin
// OLD:
tempDir.resolve("build.gradle").writeText("plugins { id 'java' }")
val view2 = service.sync(tempDir, mode = SyncMode.REFRESH_ANALYSIS)
// ...
assertTrue(view1.manifest.depHash != view2.manifest.depHash, "fresh sync should produce different depHash when dependency files change")

// NEW:
// Compute the pre-change hash explicitly
val hashBefore = service.check(tempDir).depHash
assertNotNull(hashBefore)
tempDir.resolve("build.gradle.kts").writeText("plugins { id(\"java\") }")
val hashAfter = service.check(tempDir).currentDepHash
assertNotEquals(hashBefore, hashAfter, "Hash must change after modifying build file")

// Now sync with REFRESH_ANALYSIS — should detect the change
val view2 = service.sync(tempDir, mode = SyncMode.REFRESH_ANALYSIS)
assertTrue(view1.manifest.depHash != view2.manifest.depHash, "fresh sync should produce different depHash when dependency files change")
assertEquals(hashAfter, view2.manifest.depHash, "New view's depHash should match current file state")
```

**Verification:**
- All tests pass.
- `ViewModelsTest` reduced from 149 lines to ~100 lines (3 tests removed).
- No `assertTrue(true)` anywhere in the codebase.

**Complexity:** Trivial

---

### Step 16: Extract test boilerplate helper (Finding 19)
**Rationale:** 14+ test methods repeat 5–7 lines of setup. Extract helper to reduce maintenance cost.

**Files:** `src/test/kotlin/dev/rnett/sources/mcp/SourcesViewServiceTest.kt`

**Exact Changes:**
Add a helper method:
```kotlin
private suspend fun setupSyncScenario(
    pkgName: String,
    vararg scopes: String = arrayOf("compile"),
    namespace: String = "com.example",
    version: String = "1.0.0",
): Triple<Package, String, AnalyzerResult> {
    val pkgId = makePkgId(pkgName, namespace, version)
    val pkg = makePkg(pkgId)
    val result = makeAnalyzerResult(pkg to scopes.toSet())
    every { analyzer.analyzeDependencies(tempDir, force = any()) } returns result

    val casKey = "hash-${pkgName}"
    val casDir = env.casDir.resolve(casKey).also { it.createDirectories() }
    casDir.resolve("File.kt").writeText(pkgName)
    every { downloader.downloadSources(pkg, forceRedownload = any()) } returns casDir
    return Triple(pkg, casKey, result)
}
```

Then refactor tests to use it. Example — `testUuidKeyedViewDirectoryCreation`:
```kotlin
// OLD (lines 89-110):
@Test
fun testUuidKeyedViewDirectoryCreation() = runTest {
    val pkgId = makePkgId("test-lib")
    val pkg = makePkg(pkgId)
    val result = makeAnalyzerResult(pkg to setOf("compile"))
    every { analyzer.analyzeDependencies(tempDir, force = any()) } returns result
    val casDir = env.casDir.resolve("fake-hash").also { it.createDirectories() }
    casDir.resolve("SomeFile.kt").writeText("content")
    every { downloader.downloadSources(pkg, forceRedownload = any()) } returns casDir
    val view = service.sync(tempDir)
    assertNotNull(view)
    assertTrue(view.sessionId.isNotEmpty())
    // ...
}

// NEW:
@Test
fun testUuidKeyedViewDirectoryCreation() = runTest {
    setupSyncScenario("test-lib")
    val view = service.sync(tempDir)
    assertNotNull(view)
    assertTrue(view.sessionId.isNotEmpty())
    // ...
}
```

Don't force this on every test — some tests need non-standard setup (multiple packages, `throws`, specific CAS paths). Apply judgment: refactor the 10+ tests that follow the simple single-package pattern. Leave complex ones as-is.

**Verification:**
- All 26 tests pass (22 existing + 4 new from Step 12).
- Line count of `SourcesViewServiceTest.kt` reduced by ~60 lines.

**Complexity:** Trivial

---

### Step 17: Wire everything — create server bootstrap (Finding 1)
**Rationale:** `SyncCheckTools.registerAll()` and `ViewCleanupService.start()` are never called by non-test code. This is the single most impactful fix — tools must be invocable by MCP clients.

**Files:**
- **New:** `src/main/kotlin/dev/rnett/sources/mcp/SourcesMcpServer.kt`
- Possibly `src/main/kotlin/dev/rnett/sources/mcp/Main.kt` or similar entry point (if one exists)

**Exact Changes:**

1. Create `SourcesMcpServer.kt`:
   ```kotlin
   package dev.rnett.sources.mcp

   import io.modelcontextprotocol.kotlin.sdk.server.Server
   import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
   import org.slf4j.LoggerFactory

   class SourcesMcpServer {
       private val logger = LoggerFactory.getLogger(SourcesMcpServer::class.java)

       private val viewService = SourcesViewService()
       private val syncCheckTools = SyncCheckTools(viewService)
       private val cleanupService = ViewCleanupService(SourcesMcpEnvironment.fromEnv())

       fun start() {
           val server = Server(
               serverInfo = Implementation(
                   name = "sources-mcp",
                   version = "0.1.0",
               ),
               capabilities = ServerCapabilities(
                   tools = ServerCapabilities.Tools(listChanged = false),
               ),
           )

           syncCheckTools.registerAll(server)
           cleanupService.start()

           logger.info("Sources MCP server starting on stdio transport")

           val transport = StdioServerTransport()
           runBlocking {
               server.connect(transport)
           }
       }

       fun stop() {
           cleanupService.stop()
       }
   }

   fun main() {
       SourcesMcpServer().start()
   }
   ```

   **Imports needed:**
   - `io.modelcontextprotocol.kotlin.sdk.server.Server`
   - `io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport`
   - `io.modelcontextprotocol.kotlin.sdk.Implementation`
   - `io.modelcontextprotocol.kotlin.sdk.ServerCapabilities`
   - `kotlinx.coroutines.runBlocking`

2. Verify `build.gradle.kts` has a way to produce a runnable JAR or use `application` plugin. If using `application` plugin, set:
   ```kotlin
   application {
       mainClass.set("dev.rnett.sources.mcp.SourcesMcpServerKt")
   }
   ```

**Gotchas:**
- The Kotlin MCP SDK transport (`StdioServerTransport`) may require specific initialization. Check the SDK version used in the project.
- The `Implementation` and `ServerCapabilities` classes may have different fully-qualified names depending on the SDK version.
- `SourcesMcpEnvironment.fromEnv()` in the `SourcesViewService` default constructor creates `viewsDir`, `casDir`, `locksDir` directories — this is the side effect Finding 21 mentioned. It's fine for bootstrap; just be aware.

**Verification:**
- `grep -r "SyncCheckTools(" src/main/` shows at least one non-test instantiation.
- `grep -r "ViewCleanupService(" src/main/` shows at least one non-test instantiation.
- Integration test: start server on stdio, send `sync_dependencies` tool call, verify structured response.
- `./gradlew :build` compiles the full project including new server bootstrap.

**Complexity:** Moderate (due to MCP SDK integration)

---

### Step 18: Refactor `SourcesViewService` God Object (Finding 8)
**Rationale:** After all previous steps, `SourcesViewService` has been simplified: `computeDepHash` moved out, `view-index.json` removed, link logic extracted to `PlatformLinkService`, shell validation removed, `cacheService` removed, `SyncMode` enum added. The remaining monolithic 300+ lines need splitting into focused components.

**Files:**
- `src/main/kotlin/dev/rnett/sources/mcp/SourcesViewService.kt` (refactor, not rewrite)
- **New (optional):** `src/main/kotlin/dev/rnett/sources/mcp/DependencyFilter.kt` (extract scope filtering)
- **New (optional):** `src/main/kotlin/dev/rnett/sources/mcp/ViewDiscoveryService.kt` (extract disk scan)

**Exact Changes:**

1. **Extract `filterPackagesByScopes()` as private helper** (lines 82–93):
   ```kotlin
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
   ```

2. **Extract `validateDependencyIdentifiers()` as private helper** (lines 102–116):
   ```kotlin
   private fun validateDependencyIdentifiers(packages: List<Package>, sourcesDir: Path) {
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
           }
           val testPath = sourcesDir.resolve(
               "${pkg.id.type}/${pkg.id.namespace}/${pkg.id.name}/${pkg.id.version}"
           ).normalize()
           require(testPath.startsWith(sourcesDir)) {
               "Dependency path escapes sources directory: ${testPath.toAbsolutePath()}"
           }
       }
   }
   ```

3. **Extract `findViewByProjectRootScan()` helper from `findViewOnDisk()`** (lines 276–304):
   Move the disk scan logic into a clearer helper method to reduce nesting in `findViewOnDisk()`.
   ```kotlin
   private fun findViewByScanning(
       viewsDir: Path,
       normalizedRoot: String,
       currentDepHash: String,
   ): StalenessCheck? {
       val viewDirs = Files.list(viewsDir).use { stream ->
           stream.filter { it.isDirectory() }.toList()
       }
       for (dir in viewDirs) {
           val manifestFile = dir.resolve("manifest.json")
           if (!manifestFile.exists()) continue

           val manifest = runCatching {
               json.decodeFromString(ViewManifest.serializer(), manifestFile.readText())
           }.getOrNull() ?: continue

           // Safety validation (from Step 11 / Finding 4)
           val projectRootStr = manifest.projectRoot
           val safeToResolve = projectRootStr.length <= 4096 &&
               !projectRootStr.startsWith("\\\\") &&
               (IS_WINDOWS && projectRootStr.matches(Regex("^[A-Za-z]:\\\\.*")) ||
                !IS_WINDOWS && projectRootStr.startsWith("/"))

           if (safeToResolve && runCatching {
               Path.of(projectRootStr).toAbsolutePath().normalize().toRealPath().toString()
           }.getOrNull() == normalizedRoot) {
               val age = computeAge(manifest.timestamp)
               return StalenessCheck(
                   viewExists = true,
                   fresh = manifest.depHash == currentDepHash,
                   age = age,
                   depHash = manifest.depHash,
                   currentDepHash = currentDepHash,
                   dependencyCount = manifest.dependencies.size,
               )
           }
       }
       return null
   }
   ```

4. Replace `findViewOnDisk()` to call the helper:
   ```kotlin
   private fun findViewOnDisk(projectRoot: Path, currentDepHash: String): StalenessCheck? {
       val viewsDir = env.viewsDir
       if (!viewsDir.exists() || !viewsDir.isDirectory()) {
           return StalenessCheck(
               viewExists = false, fresh = false, age = null,
               depHash = null, currentDepHash = currentDepHash, dependencyCount = null,
           )
       }

       val normalizedRoot = runCatching {
           projectRoot.toAbsolutePath().normalize().toRealPath().toString()
       }.getOrNull() ?: return StalenessCheck(
           viewExists = false, fresh = false, age = null,
           depHash = null, currentDepHash = currentDepHash, dependencyCount = null,
       )

       return findViewByScanning(viewsDir, normalizedRoot, currentDepHash)
   }
   ```

5. Final `SourcesViewService` structure (~200-250 lines):
   - `sync()` — pipeline orchestration (~30 lines)
   - `check()` — staleness check (~15 lines)
   - `getView()` / `evictView()` — cache accessors (~10 lines)
   - Private helpers: `filterPackagesByScopes()`, `validateDependencyIdentifiers()`, `downloadAndLinkDependencies()`, `findViewOnDisk()`, `findViewByScanning()`, `computeAge()`, `extractDependenciesWithScopes()`, `collectScopeDeps()`, `getValidCachedView()`, `SessionView.isValid()`

**Verification:**
- All 26 `SourcesViewServiceTest` tests pass unchanged.
- All concurrency tests pass.
- `SourcesViewService.kt` ORT imports reduced to `AnalyzerResult`, `Package` (check: may need `Identifier` and `Scope` too for `extractDependenciesWithScopes` and `collectScopeDeps`). Yes: `Identifier`, `Package`, `AnalyzerResult`, `Scope`. That's 4 — goal is ≤5, achieved.
- Line count of `SourcesViewService.kt` ≤ 300 lines.

**Complexity:** Complex (due to the number of moving parts and regression risk)

---

## Verification Checklist (final)

After all steps, run:

```bash
# Full test suite
./gradlew :test

# Integration tests (if applicable)
./gradlew :integrationTest

# Full build including compilation check
./gradlew :build

# Verify no dead code
grep -r "SyncCheckTools(" src/main/
grep -r "ViewCleanupService(" src/main/
# Both must show at least one non-test instantiation

# Verify no shell invocation for filesystem ops
grep -r "ProcessBuilder.*cmd" src/main/
# Must return empty (after Step 5)

# Verify no toFile() except allowed ORT API calls
rg 'toFile\(\)' src/main/kotlin/ src/test/kotlin/ --include='*.kt'
# Only allowed: ORT API calls in DependencyAnalyzer.kt and DependencySourcesDownloader.kt

# Verify no view-index.json references
grep -r "view-index" src/
# Must return empty (after Step 6)

# Verify no "" sentinel in StalenessCheck construction
rg 'depHash = ""' src/main/kotlin/
# Must return empty except possibly in currentDepHash defaults
```

---

## Summary

| Step | Findings Addressed | Complexity | Cumulative Changes |
|------|-------------------|------------|--------------------|
| 1 | F5 | Trivial | `SourcesViewService.kt` |
| 2 | F9 | Trivial | `SourcesViewService.kt` |
| 3 | F15, F16, F20, F21 | Trivial | `SourcesViewService.kt` |
| 4 | F18 | Simple | `DependencyAnalyzer.kt`, `SourcesViewService.kt` |
| 5 | F3 | Moderate | New `PlatformLinkService.kt`, `SourcesViewService.kt`, `build.gradle.kts` (JNA dep) |
| 6 | F7 | Simple | `SourcesViewService.kt` |
| 7 | F11 | Simple | `SourcesViewService.kt` |
| 8 | F13 | Trivial | `ViewCleanupService.kt` |
| 9 | F12 | Simple | `SyncCheckTools.kt` |
| 10 | F6 | Simple | `SourcesViewService.kt` |
| 11 | F4 | Simple | `SourcesViewService.kt` |
| 12 | F14 | Simple | `SourcesViewServiceTest.kt` |
| 13 | F17 | Simple | `DependencySourcesDownloader.kt`, 4 test files |
| 14 | F2, F24 | Simple | `SourcesViewServiceConcurrencyTest.kt` |
| 15 | F10, F22, F23 | Trivial | `ViewCleanupServiceTest.kt`, `ViewModelsTest.kt`, `SourcesViewServiceTest.kt` |
| 16 | F19 | Trivial | `SourcesViewServiceTest.kt` |
| 17 | F1 | Moderate | New `SourcesMcpServer.kt`, possibly `build.gradle.kts` |
| 18 | F8 | Complex | `SourcesViewService.kt` (refactor) |

**Total: 18 steps, 24 findings addressed, 0 findings dismissed.**
