## 1. Environment & Infrastructure

- [ ] 1.1 Add `viewsDir` property to `SourcesMcpEnvironment` with directory creation in init block
- [ ] 1.2 Create `ViewCleanupService` with time-based pruning (configurable max age, default 24h)
- [ ] 1.3 Integrate cleanup trigger on MCP server startup

## 2. View Models

- [ ] 2.1 Create `ViewManifest` data class with `@Serializable`: `sessionId`, `timestamp`, `projectRoot`, `scopes`, `dependencies`
- [ ] 2.2 Create `ViewDependency` data class with `@Serializable`: `id`, `casKey`, `ecosystem`, `namespace`, `name`, `version`
- [ ] 2.3 Create `SessionView` data class: `sessionId`, `baseDir`, `sourcesDir`, `manifest`

## 3. View Service

- [ ] 3.1 Create `SourcesViewService` with `createOrGetView(projectRoot: Path, analyzerResult: AnalyzerResult, scopes: Set<String> = emptySet(), fresh: Boolean = false, redownload: Boolean = false): SessionView` method
- [ ] 3.2 Implement in-memory view cache: `ConcurrentHashMap<ViewCacheKey, SessionView>` where `ViewCacheKey` is a data class with `normalizedProjectRoot: Path` and `scopes: Set<String>`. Path normalization follows the rules in design.md
  §5: convert to absolute, normalize separators, remove trailing slashes, resolve symlinks for project root.
- [ ] 3.3 Implement unique session ID generation (`UUID.randomUUID()`) and view directory creation under `viewsDir` as `{uuid}/`
- [ ] 3.4 Implement symlink creation for *nix systems (`Files.createSymbolicLink`)
- [ ] 3.5 Implement junction creation for Windows using `cmd /c mklink /J` (symbolic links via `Files.createSymbolicLink()` require elevated privileges on Windows)
- [ ] 3.6 Implement path mapping: `{viewDir}/sources/{ecosystem}/{namespace}/{name}/{version}` → CAS entry
- [ ] 3.7 Implement scope filtering when `scopes` parameter is provided. Filtering uses exact match (case-sensitive) against dependency scope names from ORT analysis. Multiple scopes use OR logic (a dependency is included if it matches ANY
  specified scope). Unknown scope names are silently ignored.
- [ ] 3.8 Generate `manifest.json` in view directory with session ID, timestamp, project root, scopes, and package list with CAS hash mappings
- [ ] 3.9 Parallelize dependency download and symlink creation using coroutines with configurable parallelism (default: `Runtime.getRuntime().availableProcessors()`)
- [ ] 3.10 Implement `fresh` mode: invalidate in-memory cache entry, re-run analysis, re-use CAS downloads
- [ ] 3.11 Implement `redownload` mode: re-use cached analysis, acquire advisory locks on existing CAS entries, download fresh sources to temporary directories, and atomically move to new CAS locations with different content hashes (due to
  timestamp differences). Old CAS entries remain valid for other sessions. See design.md §7 for locking details.
- [ ] 3.12 Implement locking for concurrent `createOrGetView` calls on the same project key (synchronized on cache key)
- [ ] 3.13 Implement `getView(projectRoot, scopes)` for lookup without creation
- [ ] 3.14 Implement `evictView(projectRoot, scopes)` to remove from in-memory cache

## 4. Testing

- [ ] 4.1 Write unit tests for `SourcesViewService` UUID-keyed view directory creation
- [ ] 4.2 Write unit tests for `SourcesViewService` in-memory view caching by inputs (project root + scopes)
- [ ] 4.3 Write unit tests for `SourcesViewService` symlink/junction creation
- [ ] 4.4 Write unit tests for `SourcesViewService` `fresh` and `redownload` modes
- [ ] 4.5 Write unit tests for `ViewManifest` serialization/deserialization
- [ ] 4.6 Write unit tests for `ViewCleanupService` time-based pruning
- [ ] 4.7 Write integration test for `createOrGetView` end-to-end flow
- [ ] 4.8 Run `./gradlew test` and ensure all tests pass
