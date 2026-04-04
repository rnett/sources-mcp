# Gap Analysis: sources-mcp vs. gradle-mcp (v1 Objective)

This document outlines the gaps between the current `sources-mcp` implementation and the source handling capabilities of `gradle-mcp`, specifically focusing on providing a unified "view" directory for dependency sources.

## Current State of sources-mcp

- **Dependency Analysis**: Uses OSS Review Toolkit (ORT) via `DependencyAnalyzer` to detect projects and packages (dependencies) for any supported ecosystem.
- **Source Downloading**: `DependencySourcesDownloader` uses ORT to download and extract sources into a Content-Addressable Storage (CAS).
- **Caching**: `DependencyCacheService` caches ORT analyzer results.
- **Environment**: `SourcesMcpEnvironment` manages basic directory structure (`cache/`, `cas/`, `locks/`, `analyzer/`).

## Capabilities of gradle-mcp

- **Unified View**: Creates a "session view" directory containing symlinks (or junctions) to extracted sources in the CAS.
- **Structured Paths**: Symlinks are organized by `group/artifact/version` (relative prefix), providing a clean, searchable tree.
- **Normalization**: Performs advanced normalization (e.g., handling KMP layouts like `commonMain`, `jvmMain`) to provide a consistent `v1/` source tree for each dependency.
- **Cleanup**: Automatically prunes old session views.
- **Tool Integration**: The `read_dependency_sources` tool returns the absolute path to this unified view.

## Identified Gaps

### 1. View Generation Service (`SourcesViewService`)

`sources-mcp` lacks a service to orchestrate the creation of a unified view.

- **Requirement**: A new service that takes an `AnalyzerResult`, ensures all dependencies are downloaded to the CAS, and creates a temporary directory with symlinks to these CAS entries.
- **Path Structure**: Must implement a stable mapping from ORT's `Package` identifiers to filesystem paths (e.g., `namespace/name/version`).

### 2. View Storage & Environment

`SourcesMcpEnvironment` does not yet define a location for temporary views.

- **Requirement**: Add a `viewsDir` (e.g., `cache/views/`) to `SourcesMcpEnvironment`.

### 3. Source Normalization

While `DependencySourcesDownloader` performs basic flattening, it lacks the sophisticated normalization found in `gradle-mcp`.

- **Requirement**: Expand `DependencySourcesDownloader` or add a `NormalizationService` to handle common source layouts (especially for KMP and multi-language packages) before they are symlinked into the view.

### 4. Cleanup Mechanism

There is currently no way to prune old downloaded sources or view directories.

- **Requirement**: Implement a background or periodic cleanup task to remove old view directories and potentially unreferenced CAS entries.

### 5. Integrated MCP Tool

No tool exists yet to provide the "Analyze -> Download -> Create View -> Return Path" flow.

- **Requirement**: Implement a tool (e.g., `read_sources`) that takes a `projectRoot`, performs the analysis, generates the view, and returns the path to the user.

## Design Decisions

### 1. Unified Directory Layout

- **Decision**: Use ecosystem-based prefixes (e.g., `npm/lodash/4.17.21/`) to prevent cross-manager collisions.
- **Optimization**: If all packages in a project come from a single ecosystem, the prefix can be omitted for brevity.
- **Control**: Allow users/tools to specify a target ecosystem in the tool call to filter the view.

### 2. Filesystem Strategy

- **Decision**: Follow the `gradle-mcp` strategy—use symbolic links on *nix systems and junctions on Windows.
- **Constraint**: Portability is not a concern; functional accessibility for local tools like `rg` and `tilth` is the priority.

### 3. Lifecycle Management

- **Decision**: View directory lifecycles are strictly tied to the MCP session.
- **Cleanup**: Views should be pruned when the session ends or through a time-based reaper if session tracking is unreliable.

### 4. Performance & Parallelization

- **Decision**: Parallelize dependency downloads to maximize throughput.
- **CAS**: Rely heavily on the CAS to avoid redundant downloads across sessions and projects.
- **Analysis**: Acknowledge that ORT analysis is the primary bottleneck and leverage caching where possible.

### 5. Normalization Approach

- **Decision**: Keep source normalization and post-processing as an "open target" for investigation.
- **Initial Step**: Implement a basic flattening strategy and expand with ecosystem-specific rules (like KMP) as needed.

## Implementation Requirements for v1 "View" Directory

1. **`SourcesMcpEnvironment` Update**: Add `viewsDir` property.
2. **`SourcesViewService` Implementation**:
    - Create a unique session ID and directory in `viewsDir`.
    - Iterate over `AnalyzerResult.packages` (parallelized).
    - For each package:
        - Download/Extract to CAS via `DependencySourcesDownloader`.
        - Create a symlink/junction from `{viewDir}/{ecosystem}/{namespace}/{name}/{version}` to the CAS directory.
    - Generate a `manifest.json` in the view directory for metadata.
3. **`DependencySourcesDownloader` Refinement**:
    - Ensure it returns a consistent "normalized" subdirectory within the CAS entry that is suitable for symlinking.
4. **Cleanup Task**:
    - Simple time-based pruning of the `viewsDir`.
5. **New MCP Tool**:
    - Name: `read_sources` (or similar).
    - Args: `projectRoot: String`, `ecosystem: String?`.
    - Returns: Path to the generated view directory.
