## ADDED Requirements

### Requirement: UUID-keyed view directory on disk

The system SHALL create a unique session view directory under `viewsDir` for each view generation request. The directory name SHALL be a UUID value generated via `UUID.randomUUID()` (e.g., `550e8400-e29b-41d4-a716-446655440000`).

#### Scenario: New view directory creation

- **WHEN** a view generation request is received via `sync`
- **THEN** a new directory is created under `viewsDir` with a name matching the UUID pattern

### Requirement: In-memory view cache keyed by inputs

The `SourcesViewService` SHALL maintain an in-memory `ConcurrentHashMap` mapping project inputs (normalized project root path + scopes set) to their corresponding `SessionView` reference. Subsequent calls to `sync` with the same inputs SHALL return the cached view without re-creating it. Calls to `check` do NOT populate the in-memory cache. When returning a cached view, the service SHALL verify that the view directory still exists on disk. If the directory has been removed, the cache entry SHALL be evicted and a new view SHALL be created.

#### Scenario: View cache hit

- **WHEN** `sync` is called with project inputs (root + scopes) that have an existing cached view
- **THEN** the cached `SessionView` is returned immediately

#### Scenario: Cached view re-validated on disk

- **WHEN** `sync` is called with inputs that have a cached view BUT the view directory has been deleted from disk (e.g., by cleanup)
- **THEN** the cache entry is evicted and a new view is created

#### Scenario: View cache miss

- **WHEN** `sync` is called with project inputs that have no cached view
- **THEN** a new view is created, stored in the cache, and returned

### Requirement: Service owns analysis pipeline

The `sync` method SHALL internally invoke `DependencyAnalyzer.analyzeDependencies(projectRoot)` to perform ORT analysis. Callers do NOT pass a pre-computed `AnalyzerResult`. The service SHALL use `DependencyCacheService` internally for analyzer result caching, making repeated analysis fast when dependency files haven't changed.

#### Scenario: Sync performs analysis internally

- **WHEN** `sync` is called with `mode=REFRESH_ANALYSIS or mode=FULL_REFRESH` or when no cached view exists
- **THEN** the service invokes `DependencyAnalyzer` internally, leverages `DependencyCacheService` for caching, downloads sources via `DependencySourcesDownloader`, and creates the view

### Requirement: sync method with SyncMode enum

The `sync` method SHALL have the signature: `sync(projectRoot: Path, scopes: Set<String> = emptySet(), mode: SyncMode = SyncMode.CACHED): SessionView`.

The `SyncMode` enum SHALL define the following modes:

| Mode | Behavior |
|------|----------|
| CACHED | Return cached view if available, otherwise create new view |
| REFRESH_ANALYSIS | Invalidate in-memory cache, re-run ORT analysis, re-use existing CAS downloads |
| REDOWNLOAD_SOURCES | Re-use cached ORT analysis, re-download sources to existing CAS keys (atomically refreshing content) |
| FULL_REFRESH | Invalidate both in-memory cache AND CAS entries, re-run full pipeline fresh |

Note: The MCP tools `sync_dependencies` translate `fresh` and `redownload` boolean parameters into the corresponding `SyncMode`:
- `fresh=false, redownload=false` → `CACHED`
- `fresh=true, redownload=false` → `REFRESH_ANALYSIS`
- `fresh=false, redownload=true` → `REDOWNLOAD_SOURCES`
- `fresh=true, redownload=true` → `FULL_REFRESH`

#### Scenario: Fresh analysis re-uses CAS downloads

- **WHEN** `sync` is called with `mode = SyncMode.REFRESH_ANALYSIS`
- **THEN** the in-memory cache entry is invalidated, ORT analysis is re-run, a new view is created, but existing CAS entries are not re-downloaded

#### Scenario: Redownload forces fresh downloads

- **WHEN** `sync` is called with `mode = SyncMode.REDOWNLOAD_SOURCES`
- **THEN** cached analysis is used but dependency sources are re-downloaded to the same CAS locations

#### Scenario: Both fresh and redownload are true

- **WHEN** `sync` is called with `mode = SyncMode.FULL_REFRESH`
- **THEN** the in-memory cache is invalidated, CAS entries are invalidated, and the full pipeline runs fresh

#### Scenario: fresh=true but analysis cache entry doesn't exist

- **WHEN** `sync` is called with `mode = SyncMode.REFRESH_ANALYSIS` for inputs where the analysis cache entry has been cleaned up
- **THEN** ORT analysis is re-run and missing CAS entries are downloaded as needed

### Requirement: check method for staleness detection

The `SourcesViewService` SHALL provide a `check(projectRoot: Path, scopes: Set<String> = emptySet()): StalenessCheck` method that performs lightweight staleness detection without downloads or analysis. When `scopes` is provided, the check is scoped to views matching those scopes. When `scopes` is empty, views are selected by most recent timestamp. The returned `StalenessCheck` SHALL contain:

- `viewExists: Boolean` — whether a view directory exists on disk for this project
- `fresh: Boolean` — whether the stored `depHash` matches the current dependency files
- `age: Duration?` — time since view creation (null if no view exists)
- `depHash: String?` — stored hash from manifest (null if no view/manifest)
- `currentDepHash: String` — freshly computed hash of current dependency files
- `dependencyCount: Int?` — number of dependencies in the stored manifest (null if no manifest)
- `error: String?` — error message if check could not be completed (null on success)

#### Scenario: check with existing fresh view

- **WHEN** `check` is called and a view exists with a `depHash` matching current dependency files
- **THEN** returns `{ viewExists: true, fresh: true, age: <duration>, depHash: <hash>, currentDepHash: <same>, dependencyCount: <n> }`

#### Scenario: check with stale view

- **WHEN** `check` is called and a view exists but `depHash` differs from current dependency files
- **THEN** returns `{ viewExists: true, fresh: false, age: <duration>, depHash: <stored>, currentDepHash: <different>, dependencyCount: <n> }`

#### Scenario: check with no view

- **WHEN** `check` is called and no view directory exists for this project
- **THEN** returns `{ viewExists: false, fresh: false, age: null, depHash: null, currentDepHash: <hash>, dependencyCount: null }`

#### Scenario: check with computeDepHash I/O error

- **WHEN** `check` is called and `computeDepHash` encounters an I/O error reading dependency files
- **THEN** returns `{ viewExists: false, fresh: false, age: null, depHash: null, currentDepHash: "", dependencyCount: null }`

#### Scenario: check with corrupt manifest

- **WHEN** `check` scans the views directory and encounters an unparseable manifest.json
- **THEN** that view directory is skipped and other views are checked

### Requirement: Symlink/junction creation to CAS entries

For each dependency package in the analysis result, the system SHALL create a symbolic link (on *nix) or junction (on Windows) from the view's `sources/` subdirectory to the corresponding CAS entry. The path structure SHALL follow `{viewDir}/sources/{ecosystem}/{namespace}/{name}/{version}`.

#### Scenario: Single ecosystem view

- **WHEN** all packages belong to a single ecosystem
- **THEN** symlinks are created under `{viewDir}/sources/{ecosystem}/{namespace}/{name}/{version}`

#### Scenario: Multi-ecosystem view

- **WHEN** packages belong to multiple ecosystems
- **THEN** symlinks are created under their respective ecosystem prefixes

### Requirement: Scope filtering

When the `scopes` parameter is provided to `sync`, the service SHALL filter dependencies by scope name using exact match (case-sensitive). Multiple scopes use OR logic — a dependency is included if its scope matches ANY of the specified scopes. Unknown scope names are silently ignored.

Additionally, suffix matching is supported for Gradle-style project-prefixed scopes: a dependency scope `project:scopeName` matches a filter scope `scopeName`. This is implemented as `depScope == userScope || depScope.endsWith(":$userScope")`.

#### Scenario: Scope filtering with exact match

- **WHEN** `sync` is called with `scopes = ["compileClasspath"]`
- **THEN** only dependencies with scope exactly equal to "compileClasspath" are included

#### Scenario: Scope suffix matching (Gradle project-prefixed)

- **WHEN** `sync` is called with `scopes = ["compileClasspath"]`
- **THEN** dependencies with scope "compileClasspath" OR scope matching "*.compileClasspath" (e.g., "main:compileClasspath") are included

#### Scenario: Multiple scopes with OR logic

- **WHEN** `sync` is called with `scopes = ["compileClasspath", "runtimeClasspath"]`
- **THEN** dependencies with scope "compileClasspath" OR "runtimeClasspath" are included

### Requirement: View manifest generation with depHash

The view service SHALL generate a `manifest.json` in each view directory containing a serialized `ViewManifest` with `sessionId` (UUID), `timestamp`, `projectRoot`, `scopes`, `depHash` (SHA-256 of dependency declaration files), and `dependencies` list (each with `id`, `casKey`, `ecosystem`, `namespace`, `name`, `version`).

**`casKey` Format:** The `casKey` is the content hash key used to store the dependency in CAS. It follows the format `{algorithm}-{hash}` where:
- `algorithm` is the hash algorithm used (e.g., `SHA256`)
- `hash` is the lowercase hex-encoded content hash of the dependency source directory
- Example: `SHA256-2c26b46b68ffc6dce2ec4174022e5c9a7e3e1c5c5d8c8f8a8b8c8d8e8f0a1b2c3d`

**`depHash` Computation:** The `depHash` SHALL be computed by hashing the same managed files identified by ORT's `findManagedFiles`, ensuring consistency between analysis caching and staleness detection.

**Example `ViewDependency` in manifest:**

```json
{
  "id": "pkg:maven/org.apache.commons/commons-lang3@3.12.0",
  "casKey": "SHA256-abc123...",
  "ecosystem": "Maven",
  "namespace": "org.apache.commons",
  "name": "commons-lang3",
  "version": "3.12.0"
}
```

#### Scenario: Manifest created with depHash

- **WHEN** a view is created via `sync`
- **THEN** a `manifest.json` file exists in the view root with session metadata including `depHash`

### Requirement: Concurrent access protection

The `SourcesViewService` SHALL protect against concurrent `sync` calls for the same project key. Only one view creation SHALL proceed at a time per project; concurrent callers SHALL wait and receive the same view.

#### Scenario: Concurrent calls for same project

- **WHEN** multiple threads call `sync` with the same project inputs simultaneously
- **THEN** only one view is created and all callers receive the same `SessionView`

### Requirement: View lookup without creation

The `SourcesViewService` SHALL provide a `getView(projectRoot, scopes)` method that returns the cached view for the given inputs, or `null` if none exists.

#### Scenario: View lookup succeeds

- **WHEN** `getView` is called with inputs that have a cached view
- **THEN** the cached `SessionView` is returned

#### Scenario: View lookup fails

- **WHEN** `getView` is called with inputs that have no cached view
- **THEN** `null` is returned

### Requirement: View eviction

The `SourcesViewService` SHALL provide an `evictView(projectRoot, scopes)` method that removes the cached view from the in-memory map without deleting the view directory on disk.

#### Scenario: View eviction

- **WHEN** `evictView` is called with inputs that have a cached view
- **THEN** the entry is removed from the in-memory cache but the view directory remains on disk

### Requirement: Empty and null handling

The `SourcesViewService` SHALL handle edge cases gracefully:

#### Scenario: Empty scopes (all dependencies)

- **WHEN** `sync` is called with `scopes = emptySet()`
- **THEN** all dependencies from the analyzer result are included in the view

#### Scenario: No dependencies in analyzer result

- **WHEN** `sync` is called and analysis produces zero dependencies
- **THEN** a valid view is created with an empty `sources/` directory and a manifest with an empty `dependencies` list

#### Scenario: Scopes filter removes all dependencies

- **WHEN** `sync` is called with scopes that match no dependencies
- **THEN** a valid view is created with an empty `sources/` directory and a manifest with an empty `dependencies` list

#### Scenario: Unknown scope names

- **WHEN** `sync` is called with scope names that do not match any dependency scopes in the analyzer result
- **THEN** the unknown scope names are silently ignored (no error), and all dependencies are excluded (empty view)

#### Scenario: Multiple scopes (OR logic)

- **WHEN** `sync` is called with multiple scope names (e.g., `scopes = ["compileClasspath", "runtimeClasspath"]`)
- **THEN** a dependency is included if its scope matches ANY of the specified scopes

#### Scenario: Dependency in multiple matching scopes

- **WHEN** a dependency appears in multiple scopes and at least one matches the filter
- **THEN** the dependency is included once in the view

#### Scenario: CAS entry does not exist during symlink creation

- **WHEN** a CAS entry referenced in the analyzer result does not exist
- **THEN** the service SHALL log a warning and skip creating the symlink for that dependency (the dependency is excluded from the view manifest's dependencies list)

#### Scenario: createDirectories failure with cleanup

- **WHEN** `sync` fails after creating the view directory (e.g., source directory creation fails)
- **THEN** the partial view directory is deleted before propagating the exception

#### Scenario: Dependency identifier validation rejects unsafe characters

- **WHEN** `sync` encounters a dependency with unsafe characters in its identifier (path separators, invalid characters)
- **THEN** the entire `sync` call throws `IllegalArgumentException` and no view is created

#### Scenario: computeDepHash failure before directory creation

- **WHEN** `computeDepHash` throws an exception (e.g., I/O error reading dependency files)
- **THEN** the exception propagates to the caller before any view directory is created on disk
