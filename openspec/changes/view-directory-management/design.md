## Context

The `sources-mcp` project currently provides dependency analysis via ORT (`DependencyAnalyzer`) and source downloading to a Content-Addressable Storage (`DependencySourcesDownloader`). The CAS stores extracted sources keyed by content hash,
with advisory locking for concurrency. However, there is no unified "view" directory that presents these sources in a clean, searchable tree — a capability that `gradle-mcp` already provides via session views with symlinks/junctions.

The current `SourcesMcpEnvironment` defines `cacheDir`, `analyzerCacheDir`, `casDir`, and `locksDir`, but no `viewsDir`. The `DependencySourcesDownloader` performs basic flattening (single subdirectory hoisting) but lacks the sophisticated
normalization pipeline described in `NORMALIZATION.md`.

## Goals / Non-Goals

**Goals:**

- Provide a `SourcesViewService` with a `createOrGetView` method that orchestrates analysis → download → view creation
- Support `fresh` parameter to re-do analysis while re-using CAS-stored downloads
- Support `redownload` parameter to re-use analysis but remove and re-download+process referenced deps
- Re-use existing in-memory view if one was already created for the same project input
- Implement time-based cleanup of stale view directories
- Support Windows junctions and *nix symlinks transparently
- Careful locking and lock management, mitigated by ephemeral views

**Non-Goals:**

- Modifying the CAS structure or keying strategy
- Real-time view updates (views are immutable after creation)
- Cross-session view sharing (each session gets its own view)
- Full KMP cross-artifact deduplication in v1 (deferred to future iteration)
- Actual source normalization (deferred out of scope — normalization service is a no-op placeholder)
- MCP tool implementation (out of scope — service layer only)

## Decisions

### 1. View Directory Structure

**Decision:** Use `{viewDir}/{ecosystem}/{namespace}/{name}/{version}` path structure with ecosystem prefixes always present to prevent cross-manager collisions.
**Rationale:** Consistent with `gradle-mcp` approach; ecosystem prefixes prevent collisions between packages with same name from different ecosystems (e.g., NPM `lodash` vs. a hypothetical PyPI `lodash`).
**Alternatives considered:** Omitting ecosystem prefix for single-ecosystem projects (optimization mentioned in GAP_ANALYSIS) — deferred to future iteration for simplicity.

### 2. Filesystem Strategy

**Decision:** Use symbolic links on *nix systems and junctions on Windows via `cmd /c mklink /J`.
**Rationale:** On Windows, `Files.createSymbolicLink()` creates **symbolic links** (not junctions), and symbolic links require either Administrator privileges or Developer Mode enabled. Junctions (created via `mklink /J`) do not require
elevated privileges. Using `cmd /c mklink /J` for junction creation on Windows avoids the privilege requirement while maintaining cross-platform compatibility. On *nix, symbolic links via `Files.createSymbolicLink()` are the standard
approach. Portability is not a concern — functional accessibility for local tools (`rg`, `tilth`) is the priority.
**Alternatives considered:** Hard links (don't work across drives, don't work for directories on Windows); copy-on-write reflinks (filesystem-specific, not universally available); `Files.createSymbolicLink()` alone (creates symbolic links,
not junctions, and requires elevated privileges on Windows); Java NIO with `LinkOption.NOFOLLOW_LINKS` alone (does NOT create junctions despite the misleading name — it still creates symbolic links that require elevated privileges).

### 3. Separate View Service

**Decision:** Create a dedicated `SourcesViewService` with a single `createOrGetView(projectRoot, analyzerResult, scopes, fresh, redownload)` method.
**Rationale:** The view service is the orchestrator for the full pipeline. A single entry point with mode flags is simpler than multiple methods. In-memory caching of created views avoids redundant work within a session. The
`analyzerResult` parameter provides the dependency graph from ORT analysis. The `scopes` parameter filters dependencies by scope name (e.g., `compileClasspath`, `runtimeClasspath` for JVM projects); when empty, all dependencies are
included.
**Scope Filtering Semantics:** Scope filtering uses exact match (case-sensitive) against dependency scope names from ORT analysis. When multiple scopes are specified, a dependency is included if its scope matches ANY of the specified
scopes (OR logic). If a dependency appears in multiple scopes and at least one matches, it is included. If a scope name does not match any dependencies, it is silently ignored (no error).
**Mode Flag Interaction:** The `fresh` and `redownload` flags operate independently:

- `fresh=true, redownload=false`: Invalidate in-memory cache, re-run ORT analysis, re-use existing CAS downloads
- `fresh=false, redownload=true`: Re-use cached ORT analysis, invalidate CAS entries (via advisory lock), re-download+process dependencies to new CAS locations
- `fresh=true, redownload=true`: Invalidate both in-memory cache AND CAS entries, re-run full pipeline (equivalent to ignoring both flags and creating fresh)
- `fresh=false, redownload=false`: Return cached view if available, otherwise create new view
  **Alternatives considered:** Separate methods for create vs. get — adds complexity without benefit since the service can determine which path to take.

### 4. Normalization is a No-Op (Deferred)

**Decision:** Create a `NormalizationService` interface/placeholder that is currently a no-op. Actual normalization logic is deferred out of scope.
**Rationale:** The service boundary is important for future extensibility, but implementing the full normalization pipeline is out of scope for this change. The placeholder ensures the architecture is ready when normalization is needed.
**CAS vs. Normalization Relationship:** The CAS stores **raw extracted sources** as downloaded by ORT. Normalization (as described in `NORMALIZATION.md`) is a separate post-processing step that would transform raw sources into a clean,
agent-optimized view. When normalization is implemented, it would either:

- Create a separate `normalized/` subdirectory within the CAS entry, or
- Create a new CAS entry keyed by the normalized content hash

In v1 of this change, symlinks point directly to CAS entry directories containing raw sources. Future normalization would operate on these raw sources without modifying the CAS keying strategy.
**Alternatives considered:** No normalization service at all — would require refactoring later; integrating into downloader — tightly couples concerns that should be separate.

### 5. View Keying Strategy (UUID on Disk, Inputs in Memory)

**Decision:** Views are stored on disk keyed by a UUID (directory name: `{uuid}`), with an in-memory `ConcurrentHashMap` mapping project inputs to their corresponding `SessionView` reference. The cache key is a `ViewCacheKey` data class
containing `normalizedProjectRoot` (as `Path`) and `scopes` (as `Set<String>`). This mirrors the `gradle-mcp` approach where `createSessionView` generates a `UUID.randomUUID()` as `sessionId`, creates a directory under `sessionViewsDir`,
and the `SourcesService` maintains a cache keyed by `CacheKey(scope, dependencyFilter)`.
**ViewCacheKey Definition:**

```kotlin
data class ViewCacheKey(
  val normalizedProjectRoot: Path,
  val scopes: Set<String>
)
```

- **Path Normalization:** Project root paths are normalized to ensure consistent cache keys across different path representations:
    - Convert to absolute path
    - Normalize separators (`\` → `/`) for cross-platform consistency
    - Remove trailing slashes
    - Resolve symlinks for the project root (but not intermediate components)
- **Case Sensitivity:** Path comparison is case-sensitive (Paths are compared as-is); this matches typical filesystem behavior on Linux but may differ on case-insensitive filesystems like Windows — callers should normalize paths before
  calling the service.
  **Rationale:** UUID keying on disk provides unique, collision-free directory names and enables straightforward time-based cleanup. The in-memory map by inputs enables fast lookup without scanning the filesystem, and supports the `fresh`
  flag for controlled invalidation. This separation of concerns (disk identity vs. logical identity) is proven in `gradle-mcp`.
  **Alternatives considered:** Hash-based directory names from inputs — creates long, unreadable names and hash collisions are theoretically possible; input-based directory names — problematic with special characters in paths and makes
  cleanup harder.

### 6. View Directory Structure on Disk

**Decision:** Each view directory follows the pattern `{viewsDir}/{uuid}/` containing:

- `sources/` — symlinks/junctions to CAS entries organized as `{ecosystem}/{namespace}/{name}/{version}`
- `manifest.json` — serialized `ViewManifest` with `sessionId`, `timestamp`, `projectRoot`, `scopes`, and `dependencies` list
  **Rationale:** UUID-only directory names provide unique, collision-free directory names without redundant timestamp information. The manifest provides self-describing metadata for recovery and debugging.

### 7. Locking Strategy

**Decision:** Leverage ephemeral views to minimize locking complexity. Each view creation operates on its own unique directory. CAS-level locking (already implemented via `FileLockManager`) protects concurrent downloads. View directory
creation is single-writer per project key, synchronized in-memory.
**Rationale:** Ephemeral views eliminate the need for complex view-level locking. The CAS already has advisory locking for downloads. The view service only needs to ensure that concurrent `createOrGetView` calls for the same project don't
create duplicate views.
**redownload Mode and Advisory Locks:** In `redownload` mode, the service acquires advisory locks on CAS entries to prevent other concurrent operations from reading the entry being refreshed. The lock is acquired, fresh sources are
downloaded to a temporary directory, and atomically moved to a new CAS location (with a different content hash due to timestamp differences). The advisory lock ensures no other process attempts to read from an incomplete download. Old CAS
entries remain valid — they are never deleted or modified.
**Alternatives considered:** Full view-level locking — unnecessary complexity given ephemeral model; database-backed view registry — overkill for local development.

### 8. Cleanup Strategy

**Decision:** Time-based pruning with configurable max age (default 24 hours), triggered on MCP server startup. Session-end cleanup is a best-effort optimization when session tracking is available, but time-based pruning is the primary
mechanism.
**Session Tracking:** Session tracking via `sessionId` in the manifest is optional and used for correlation purposes. The cleanup service MAY track active sessions and attempt cleanup on session end, but time-based pruning serves as the
reliable fallback. This avoids requiring a session registry or complex session lifecycle management.
**Cleanup Trigger Mechanism:** Cleanup is triggered by a background scheduler that runs periodically (configurable interval, default: every 60 minutes). On MCP server startup, an initial cleanup scan is performed synchronously to remove any
stale views from previous sessions.
**Rationale:** Simple, reliable, doesn't require complex session tracking. Windows file locking issues are handled gracefully with retry-on-next-cycle.
**Alternatives considered:** Session-end-only cleanup — unreliable if sessions crash; LRU cache — overkill for temporary views.

### 9. Parallelism Strategy

**Decision:** Dependency download and symlink creation are parallelized using Kotlin coroutines with a configurable parallelism level (default: `Runtime.getRuntime().availableProcessors()`).
**Rationale:** ORT analysis is the primary bottleneck; parallelizing downloads and symlink creation reduces view creation latency. Coroutines provide structured concurrency that integrates well with the existing codebase.
**Configuration:** The parallelism level is configurable via `ViewServiceConfig.parallelism` or environment variable `SOURCES_VIEW_PARALLELISM`. If not configured, it defaults to the number of available CPU cores.
**Implementation Notes:**

- Each dependency's download and symlink creation is a separate coroutine task
- The service uses a `CoroutineScope` with a `Dispatchers.IO` dispatcher for I/O-bound work
- If a dependency's CAS entry already exists, symlink creation is nearly instantaneous (no download needed)

### 10. Path Length Considerations

**Decision:** The system shall document and handle Windows `MAX_PATH` (260 character) limitations gracefully.
**Rationale:** Deep dependency trees combined with UUID-keyed view directories can produce paths exceeding Windows' 260 character limit. This is especially problematic when symlinks create long relative paths.
**Mitigation Strategies:**

- Use UUID-only directory names (the `{uuid}` format is compact and collision-free)
- Document the limitation and recommend running on paths with shorter base directories (e.g., `C:\sources\` rather than deeply nested paths)
- Log warnings when created symlinks exceed 200 characters to alert users of potential issues
- Future optimization: detect and warn about paths that would exceed MAX_PATH before creation
  **Alternatives considered:** Automatic path shortening — adds complexity and potential collisions; NTFS long path support — requires registry changes on Windows, not universally available.

## Risks / Trade-offs

| Risk                                                            | Mitigation                                                                               |
|-----------------------------------------------------------------|------------------------------------------------------------------------------------------|
| Windows junction creation requires directory (not file) targets | Only create junctions to CAS directories (which are always directories after extraction) |
| File-in-use errors during cleanup on Windows                    | Graceful error handling with retry on next cycle; log warnings                           |
| ORT analysis is the primary bottleneck                          | Leverage existing `DependencyCacheService` for analyzer result caching                   |
| Concurrent `createOrGetView` calls for same project             | In-memory synchronization per project key; ephemeral views reduce contention             |
| Symlink/junction count limits on some filesystems               | Unlikely to hit limits (thousands of dependencies); monitor if issues arise              |
| Windows MAX_PATH (260 char) limitation                          | Document limitation; warn when paths exceed 200 chars; recommend short base paths        |

## Migration Plan

1. Add `viewsDir` to `SourcesMcpEnvironment` (backward compatible — new directory)
2. Create `NormalizationService` as a no-op placeholder
3. Add `SourcesViewService` with `createOrGetView` method and in-memory view cache
4. Implement symlink/junction creation logic
5. Add `ViewCleanupService` with time-based pruning
6. No data migration required — existing CAS entries remain valid

## Open Questions

- Should view directories include a `.gitignore` to prevent accidental version control? (Likely yes, but low priority)
- What is the appropriate default max age for view cleanup? (24 hours is reasonable for local development)
- Should the in-memory view cache have a size limit? (Probably not needed for local development)

