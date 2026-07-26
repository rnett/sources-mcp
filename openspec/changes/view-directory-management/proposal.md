## Why

The current `sources-mcp` implementation can analyze dependencies and download sources to a Content-Addressable Storage (CAS), but it lacks a unified "view" directory that presents these sources in a clean, searchable tree structure. This gap prevents agents and developers from efficiently browsing and searching dependency sources using standard tools like `rg` or `tilth`. The `gradle-mcp` project already provides this capability via session views with symlinks/junctions — `sources-mcp` needs the same feature to be a complete, multi-ecosystem source browsing tool.

After a UX rethink and lifecycle design analysis, we chose the **A1** architecture: views live externally in `cache/views/{uuid}/sources/`. No symlinks into the project directory. The MCP tool returns the path; the agent tracks it and uses its own tools (`rg`, `fd`, `tilth`) on the returned path. The tool call is the sync point for freshness.

## What Changes

- **New `SourcesViewService`**: Orchestrates the full pipeline with `sync(projectRoot, scopes?, fresh?, redownload?)` and `check(projectRoot)` methods. The service owns the entire pipeline internally — callers do not pass pre-computed analysis results. Views are keyed by UUID on disk (`{viewsDir}/{uuid}/`) with an in-memory `ConcurrentHashMap` mapping project inputs (normalized project root + scopes) to `SessionView` references. This mirrors the `gradle-mcp` approach.
- **New `ViewManifest` model**: Serializable manifest stored in each view directory containing `sessionId` (UUID), `timestamp`, `projectRoot`, `scopes`, `depHash` (SHA-256 of dependency declaration files for staleness detection), and `dependencies` list.
- **New `SessionView` model**: Represents a session view directory on disk with `sessionId`, `baseDir`, `sourcesDir`, and `manifest`.
- **`SourcesMcpEnvironment` Update**: Adds a `viewsDir` property (e.g., `cache/views/`) for storing temporary session views.
- **Cleanup Mechanism**: Implements time-based pruning of stale view directories (default 24 hours) with a background scheduler (default: every 60 minutes) and startup scan.
- **MCP Tools**: Two new tools — `sync_dependencies` (create/refresh views) and `check_dependencies` (lightweight staleness check). MCP tool implementation is IN scope for this change.
- **depHash Tracking**: Dependency-file hashes stored in the manifest enable cheap staleness detection without re-running ORT analysis.

## Capabilities

### New Capabilities

- `view-directory-creation`: Service to create unified session view directories with symlinks/junctions to CAS entries, organized by ecosystem and package coordinates. Views are keyed by UUID on disk with in-memory references by project inputs. `sync` method for full pipeline execution; `check` method for lightweight staleness detection.
- `view-cleanup`: Time-based pruning mechanism for stale session view directories with background scheduler.
- `sync-check-tools`: MCP tools `sync_dependencies` and `check_dependencies` exposing the view lifecycle to agents.

### Modified Capabilities

- `source-downloading`: The existing source downloading capability remains unchanged. Normalization is NOT integrated into the download pipeline at this time.

## Impact

- **Affected Code**: `SourcesMcpEnvironment`, new `SourcesViewService`, new `ViewManifest` and `SessionView` models, new `ViewCleanupService`, new MCP tool definitions.
- **Dependencies**: No new external dependencies; uses existing ORT libraries and Java NIO for symlinks/junctions.
- **Systems**: CAS structure remains unchanged; new `views/` directory added under the cache root.
- **APIs**: Two new MCP tools added: `sync_dependencies` and `check_dependencies`.
