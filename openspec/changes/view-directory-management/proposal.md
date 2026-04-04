## Why

The current `sources-mcp` implementation can analyze dependencies and download sources to a Content-Addressable Storage (CAS), but it lacks a unified "view" directory that presents these sources in a clean, searchable tree structure. This
gap prevents agents and developers from efficiently browsing and searching dependency sources using standard tools like `rg` or `tilth`. The `gradle-mcp` project already provides this capability via session views with symlinks/junctions —
`sources-mcp` needs the same feature to be a complete, multi-ecosystem source browsing tool.

## What Changes

- **New `SourcesViewService`**: Orchestrates the full pipeline with a single `createOrGetView(projectRoot, analyzerResult, scopes, fresh, redownload)` method. Views are keyed by UUID on disk (`{viewsDir}/{uuid}/`) with an in-memory
  `ConcurrentHashMap` mapping project inputs (normalized project root + scopes) to `SessionView` references. This mirrors the `gradle-mcp` approach.
- **New `ViewManifest` model**: Serializable manifest stored in each view directory containing `sessionId` (UUID), `timestamp`, `projectRoot`, `scopes`, and `dependencies` list.
- **New `SessionView` model**: Represents a session view directory on disk with `sessionId`, `baseDir`, `sourcesDir`, and `manifest`.
- **`SourcesMcpEnvironment` Update**: Adds a `viewsDir` property (e.g., `cache/views/`) for storing temporary session views.
- **Cleanup Mechanism**: Implements time-based pruning of stale view directories (default 24 hours).
- **No MCP Tool**: MCP tool implementation is out of scope for this change — service layer only.

## Capabilities

### New Capabilities

- `view-directory-creation`: Service to create unified session view directories with symlinks/junctions to CAS entries, organized by ecosystem and package coordinates. Views are keyed by UUID on disk with in-memory references by project
  inputs. Single `createOrGetView` entry point with `fresh` and `redownload` modes.
- `view-cleanup`: Time-based pruning mechanism for stale session view directories.

### Modified Capabilities

- `source-downloading`: The existing source downloading capability remains unchanged. Normalization is NOT integrated into the download pipeline at this time.

## Impact

- **Affected Code**: `SourcesMcpEnvironment`, new `SourcesViewService`, new `ViewManifest` and `SessionView` models, new `ViewCleanupService`.
- **Dependencies**: No new external dependencies; uses existing ORT libraries and Java NIO for symlinks/junctions.
- **Systems**: CAS structure remains unchanged; new `views/` directory added under the cache root.
- **APIs**: No new MCP tools added in this change (service layer only).

