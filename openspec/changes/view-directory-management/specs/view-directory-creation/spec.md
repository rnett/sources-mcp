## ADDED Requirements

### Requirement: UUID-keyed view directory on disk

The system SHALL create a unique session view directory under `viewsDir` for each view generation request. The directory name SHALL be a UUID value generated via `UUID.randomUUID()` (e.g., `550e8400-e29b-41d4-a716-446655440000`).

#### Scenario: New view directory creation

- **WHEN** a view generation request is received
- **THEN** a new directory is created under `viewsDir` with a name matching the UUID pattern

### Requirement: In-memory view cache keyed by inputs

The `SourcesViewService` SHALL maintain an in-memory `ConcurrentHashMap` mapping project inputs (normalized project root path + scopes set) to their corresponding `SessionView` reference. Subsequent calls to `createOrGetView` with the same
inputs SHALL return the cached view without re-creating it.

#### Scenario: View cache hit

- **WHEN** `createOrGetView` is called with project inputs (root + scopes) that have an existing cached view
- **THEN** the cached `SessionView` is returned immediately

#### Scenario: View cache miss

- **WHEN** `createOrGetView` is called with project inputs that have no cached view
- **THEN** a new view is created, stored in the cache, and returned

### Requirement: Symlink/junction creation to CAS entries

For each dependency package in the analysis result, the system SHALL create a symbolic link (on *nix) or junction (on Windows) from the view's `sources/` subdirectory to the corresponding CAS entry. The path structure SHALL follow
`{viewDir}/sources/{ecosystem}/{namespace}/{name}/{version}`.

#### Scenario: Single ecosystem view

- **WHEN** all packages belong to a single ecosystem
- **THEN** symlinks are created under `{viewDir}/sources/{ecosystem}/{namespace}/{name}/{version}`

#### Scenario: Multi-ecosystem view

- **WHEN** packages belong to multiple ecosystems
- **THEN** symlinks are created under their respective ecosystem prefixes

### Requirement: Scope filtering

When the `scopes` parameter is provided to `createOrGetView`, the service SHALL filter dependencies by scope name using exact match (case-sensitive). Multiple scopes use OR logic — a dependency is included if its scope matches ANY of the
specified scopes. Unknown scope names are silently ignored.

#### Scenario: Scope filtering with exact match

- **WHEN** `createOrGetView` is called with `scopes = ["compileClasspath"]`
- **THEN** only dependencies with scope exactly equal to "compileClasspath" are included

#### Scenario: Multiple scopes with OR logic

- **WHEN** `createOrGetView` is called with `scopes = ["compileClasspath", "runtimeClasspath"]`
- **THEN** dependencies with scope "compileClasspath" OR "runtimeClasspath" are included

### Requirement: View manifest generation

The view service SHALL generate a `manifest.json` in each view directory containing a serialized `ViewManifest` with `sessionId` (UUID), `timestamp`, `projectRoot`, `scopes`, and `dependencies` list (each with `id`, `casKey`, `ecosystem`,
`namespace`, `name`, `version`).

**`casKey` Format:** The `casKey` is the content hash key used to store the dependency in CAS. It follows the format `{algorithm}:{hash}` where:

- `algorithm` is the hash algorithm used (e.g., `SHA256`)
- `hash` is the lowercase hex-encoded content hash of the dependency source directory
- Example: `SHA256:2c26b46b68ffc6dce2ec4174022e5c9a7e3e1c5c5d8c8f8a8b8c8d8e8f0a1b2c3d` (64 hex characters = 256 bits)

**Example `ViewDependency` in manifest:**

```json
{
  "id": "pkg:maven/org.apache.commons/commons-lang3@3.12.0",
  "casKey": "SHA256:abc123...",
  "ecosystem": "Maven",
  "namespace": "org.apache.commons",
  "name": "commons-lang3",
  "version": "3.12.0"
}
```

#### Scenario: Manifest created

- **WHEN** a view is created
- **THEN** a `manifest.json` file exists in the view root with session metadata

### Requirement: createOrGetView with fresh mode

The `createOrGetView` method SHALL accept a `fresh` parameter. When `fresh=true`, the service SHALL remove the in-memory cache entry for the given inputs and create a new view, but SHALL re-use existing CAS-stored downloads.

#### Scenario: Fresh analysis re-uses CAS downloads

- **WHEN** `createOrGetView` is called with `fresh=true`
- **THEN** the in-memory cache entry is invalidated, a new view is created, but existing CAS entries are not re-downloaded

### Requirement: createOrGetView with redownload mode

The `createOrGetView` method SHALL accept a `redownload` parameter. When `redownload=true`, the service SHALL re-use cached analysis results but SHALL invalidate existing CAS entries and re-download+process all referenced dependencies to
create new CAS entries.

**Important:** The CAS is immutable — `redownload` does NOT delete existing CAS entries. Instead, it acquires advisory locks on the CAS entries, downloads fresh sources to temporary directories, and atomically moves them to new CAS
locations (with different content hashes due to timestamp differences). The old CAS entries remain valid for other sessions.

#### Scenario: Redownload forces fresh downloads

- **WHEN** `createOrGetView` is called with `redownload=true`
- **THEN** cached analysis is used but dependency sources are re-downloaded to new CAS locations

### Requirement: fresh and redownload mode interaction

The `createOrGetView` method SHALL handle the `fresh` and `redownload` flags with the following behavior:

| fresh | redownload | Behavior                                                                                                                     |
|-------|------------|------------------------------------------------------------------------------------------------------------------------------|
| false | false      | Return cached view if available, otherwise create new view                                                                   |
| true  | false      | Invalidate in-memory cache, re-run ORT analysis, re-use existing CAS downloads                                               |
| false | true       | Re-use cached ORT analysis, invalidate CAS entries, re-download+process dependencies to new CAS locations                    |
| true  | true       | Invalidate both in-memory cache AND CAS entries, re-run full pipeline (equivalent to ignoring both flags and creating fresh) |

#### Scenario: Both fresh and redownload are true

- **WHEN** `createOrGetView` is called with `fresh=true` AND `redownload=true`
- **THEN** the in-memory cache is invalidated, CAS entries are invalidated, and the full pipeline runs fresh

#### Scenario: fresh=true but CAS entry doesn't exist

- **WHEN** `createOrGetView` is called with `fresh=true` for inputs that were previously cached but the corresponding CAS entries have been cleaned up
- **THEN** the ORT analysis result is re-used (from `DependencyCacheService`) and missing CAS entries are downloaded as needed

### Requirement: Concurrent access protection

The `SourcesViewService` SHALL protect against concurrent `createOrGetView` calls for the same project key. Only one view creation SHALL proceed at a time per project; concurrent callers SHALL wait and receive the same view.

#### Scenario: Concurrent calls for same project

- **WHEN** multiple threads call `createOrGetView` with the same project inputs simultaneously
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

- **WHEN** `createOrGetView` is called with `scopes = emptySet()`
- **THEN** all dependencies from the analyzer result are included in the view

#### Scenario: No dependencies in analyzer result

- **WHEN** `createOrGetView` is called with an analyzer result containing zero dependencies
- **THEN** a valid view is created with an empty `sources/` directory and a manifest with an empty `dependencies` list

#### Scenario: Scopes filter removes all dependencies

- **WHEN** `createOrGetView` is called with scopes that match no dependencies
- **THEN** a valid view is created with an empty `sources/` directory and a manifest with an empty `dependencies` list

#### Scenario: Unknown scope names

- **WHEN** `createOrGetView` is called with scope names that do not match any dependency scopes in the analyzer result
- **THEN** the unknown scope names are silently ignored (no error), and all dependencies are excluded (empty view)

#### Scenario: Multiple scopes (OR logic)

- **WHEN** `createOrGetView` is called with multiple scope names (e.g., `scopes = ["compileClasspath", "runtimeClasspath"]`)
- **THEN** a dependency is included if its scope matches ANY of the specified scopes

#### Scenario: Dependency in multiple matching scopes

- **WHEN** a dependency appears in multiple scopes and at least one matches the filter
- **THEN** the dependency is included once in the view

#### Scenario: CAS entry does not exist during symlink creation

- **WHEN** a CAS entry referenced in the analyzer result does not exist
- **THEN** the service SHALL log a warning and skip creating the symlink for that dependency (the dependency is excluded from the view manifest's dependencies list)
