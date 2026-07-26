# Two-Tool Workflow: Dependency View Lifecycle

Dependency source views are managed through two MCP tools that a calling agent uses in sequence.

## The Workflow

```
check_dependencies(projectRoot)
    │
    ├── fresh: true ──> use existing viewPath with rg/fd/tilth
    │
    └── fresh: false or viewExists: false
            │
            └── sync_dependencies(projectRoot, scopes?, fresh?, redownload?)
                    │
                    └── returns viewPath ──> use with rg/fd/tilth
```

## Step-by-Step

### 1. `check_dependencies(projectRoot)` — Assess staleness

A lightweight, read-only check. It hashes the project's dependency declaration files (no download, no ORT analysis) and compares the hash against what was stored during the last `sync_dependencies`.

**Returns:**
- `viewExists: true` / `false` — whether a view directory was previously created.
- `fresh: true` / `false` — whether the stored dependency hash matches the current one. `true` means the dependency list hasn't changed and the existing view is still valid.
- `age` — how long ago the view was created (useful for "max-age" freshness policies).
- `depHash` — stored hash; `currentDepHash` — freshly computed hash; `dependencyCount` — for diagnostics.

### 2. `sync_dependencies(projectRoot, scopes?, fresh?, redownload?)` — Create or refresh

Called when `check_dependencies` reports stale or missing. This is the **freshness sync point** — after it returns, the view reflects the current project state.

**Parameters:**
- `projectRoot` (required) — absolute path to the project.
- `scopes` (optional) — filter dependencies by ORT scope name (OR logic). Omit for all.
- `fresh` (default `false`) — force ORT re-analysis for an up-to-date dependency list (reuses cached downloads).
- `redownload` (default `false`) — force re-download of all source jars/tarballs.

**Returns:**
- `viewPath` — absolute directory path with symlinks/junctions to every dependency's source tree.
- `sessionId`, `depHash`, `dependencyCount`, `scopes` — manifest metadata.

### 3. Use `viewPath` with your own tools

Once you have the `viewPath`, explore it with any file-search tool (e.g., `rg --follow`, `fd --follow`, `tilth_read`). Note: directories are symlink/junction trees, so tooling must follow them (`--follow` / `-L`).

**Important:** The tool call to `sync_dependencies` IS the sync point. If dependencies change between your `check` and `sync`, re-run from step 1.

## Example Agentic Pattern

```
# Lightweight check
check_dependencies(projectRoot="/home/user/my-project")
# → fresh: false, dependencyCount: 12

# Rebuild the view
sync_dependencies(projectRoot="/home/user/my-project", fresh=true)
# → viewPath: /home/user/.sources/views/abc123/sources

# Explore with shell tools
rg --follow "MyClass" /home/user/.sources/views/abc123/sources
```
