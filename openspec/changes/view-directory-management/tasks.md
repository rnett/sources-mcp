## Phase 1: Environment & Infrastructure

- [x] 1.1 Add `viewsDir` property to `SourcesMcpEnvironment` with directory creation in init block
- [x] 1.2 Create `ViewCleanupService` with time-based pruning (configurable max age, default 24h)
- [ ] 1.3 Integrate cleanup trigger: startup scan + background scheduler (configurable interval, default: 60min)
  - Deferred until server bootstrap is established (project is in bootstrapping phase).

## Phase 2: Models

- [x] 2.1 Create `ViewManifest` data class with `@Serializable`: `sessionId`, `timestamp`, `projectRoot`, `scopes`, `depHash`, `dependencies`
- [x] 2.2 Create `ViewDependency` data class with `@Serializable`: `id`, `casKey`, `ecosystem`, `namespace`, `name`, `version`
- [x] 2.3 Create `SessionView` data class: `sessionId`, `baseDir`, `sourcesDir`, `manifest`
- [x] 2.4 Create `ViewCacheKey` data class: `normalizedProjectRoot: Path`, `scopes: Set<String>` with path normalization (absolute, separator normalize, trim trailing slashes, resolve symlinks)
- [x] 2.5 Create `StalenessCheck` data class: `viewExists`, `fresh`, `age`, `depHash`, `currentDepHash`, `dependencyCount`

## Phase 3: SourcesViewService

- [x] 3.1 Create `SourcesViewService` class skeleton with constructor injection: `DependencyAnalyzer`, `DependencySourcesDownloader`, `DependencyCacheService`, `SourcesMcpEnvironment`
- [x] 3.2 Implement `sync(projectRoot, scopes?, fresh?, redownload?): SessionView` method:
  - Check in-memory cache → fast return if hit and !fresh
  - If `fresh`: evict cache entry
  - Call `DependencyAnalyzer.analyzeDependencies(projectRoot)` internally
  - Apply scope filtering (OR logic, case-sensitive exact match)
  - For each dependency: download via `DependencySourcesDownloader.downloadSources(pkg)`
  - Create view directory under `viewsDir/{uuid}/`
  - Create symlinks/junctions in `sources/{ecosystem}/{namespace}/{name}/{version}` → CAS entries
  - Generate `manifest.json` with `depHash`
  - Store in in-memory cache, return `SessionView`
- [x] 3.3 Implement `check(projectRoot): StalenessCheck` method:
  - Find view directory for project root (scan manifests or use known mapping)
  - If no view: return `{ viewExists: false }`
  - Read `manifest.json`, extract `depHash`
  - Compute current depHash from dependency declaration files via ORT `findManagedFiles`
  - Compare, return `StalenessCheck`
- [x] 3.4 Implement `getView(projectRoot, scopes?)` — lookup in-memory cache, return or null
- [x] 3.5 Implement `evictView(projectRoot, scopes?)` — remove from in-memory cache
- [x] 3.6 Implement in-memory cache: `ConcurrentHashMap<ViewCacheKey, SessionView>`
- [x] 3.7 Implement symlink creation for *nix: `Files.createSymbolicLink()`
- [x] 3.8 Implement junction creation for Windows: `cmd /c mklink /J`
- [x] 3.9 Implement scope filtering logic (OR, case-sensitive, silent ignore for unknowns)
- [x] 3.10 Implement manifest.json generation and serialization
- [x] 3.11 Implement depHash computation using ORT managed files
- [x] 3.12 Implement parallelism for downloads and symlink creation using Kotlin coroutines (`Dispatchers.IO`, configurable via `SOURCES_VIEW_PARALLELISM`)
- [x] 3.13 Implement `fresh` mode: evict cache, re-run analysis, re-use CAS
- [x] 3.14 Implement `redownload` mode: re-use cached analysis, acquire advisory locks, download fresh to temp, atomic move to new CAS locations
- [x] 3.15 Implement concurrent access protection: synchronized per `ViewCacheKey`, waiting callers get same view
- [x] 3.16 Handle edge cases: empty scopes, empty analyzer result, unknown scopes, missing CAS entries, no managed files found

## Phase 4: Normalization Placeholder

- [x] 4.1 Create `NormalizationService` interface with no-op implementation
- [x] 4.2 Wire into `SourcesViewService` as a pass-through (called during view creation, returns raw sources unmodified)

## Phase 5: MCP Tools

- [ ] 5.1 Implement `sync_dependencies` MCP tool:
  - Parse parameters: `projectRoot`, `scopes?`, `fresh?`, `redownload?`
  - Call `SourcesViewService.sync(...)`
  - Format response as structured Markdown with `viewPath`, `sessionId`, `createdAt`, `depHash`, `dependencyCount`, `scopes`
  - Deferred until server bootstrap is established (project is in bootstrapping phase).
- [ ] 5.2 Implement `check_dependencies` MCP tool:
  - Parse parameter: `projectRoot`
  - Call `SourcesViewService.check(...)`
  - Format response as structured Markdown with `viewExists`, `fresh`, `age`, `depHash`, `currentDepHash`, `dependencyCount`
  - Deferred until server bootstrap is established (project is in bootstrapping phase).

## Phase 6: Testing

- [x] 6.1 Write unit tests for `ViewManifest` serialization/deserialization (round-trip, including `depHash`)
- [x] 6.2 Write unit tests for `StalenessCheck` construction and property access
- [x] 6.3 Write unit tests for `SourcesViewService` UUID-keyed view directory creation
- [x] 6.4 Write unit tests for `SourcesViewService` in-memory view caching by inputs (project root + scopes)
- [x] 6.5 Write unit tests for `SourcesViewService` symlink/junction creation (mock filesystem or use temp dirs)
- [x] 6.6 Write unit tests for `SourcesViewService` `fresh` and `redownload` modes (verify cache invalidation, CAS re-use/re-download)
- [x] 6.7 Write unit tests for `SourcesViewService.check()` — fresh, stale, no view, no analysis cache scenarios
- [x] 6.8 Write unit tests for `SourcesViewService.sync()` — scope filtering, edge cases (empty, unknown, OR logic)
- [x] 6.9 Write unit tests for `ViewCleanupService` time-based pruning
- [x] 6.10 Write unit tests for concurrent `sync` calls (same key, different keys)
- [x] 6.11 Write integration test for `sync` end-to-end flow (real project, real ORT analysis, real downloads)
- [x] 6.12 Write integration test for `check` end-to-end flow with manipulated dependency files
- [x] 6.13 Run `./gradlew test` and ensure all tests pass
- [x] 6.14 Run `./gradlew integrationTest` and ensure integration tests pass

## Phase 7: Documentation & Polish

- [x] 7.1 Update tool descriptions in code for LLM discoverability
- [x] 7.2 Document the two-tool workflow in user-facing docs
- [x] 7.3 Add KDoc comments to all public API methods in `SourcesViewService`
