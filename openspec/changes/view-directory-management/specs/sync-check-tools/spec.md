## ADDED Requirements

### Requirement: sync_dependencies MCP tool

The system SHALL provide an MCP tool `sync_dependencies` that creates or retrieves a dependency source view for a project.

**Tool Signature:**

```
sync_dependencies(
  projectRoot: String,       # Absolute path to the project root directory
  scopes?: String[],         # Scope names to filter dependencies (OR logic). Empty/omitted = all dependencies.
  fresh?: Boolean,           # Re-run ORT analysis, re-use CAS downloads. Default: false.
  redownload?: Boolean       # Re-download sources to new CAS entries. Default: false.
) → {
  viewPath: String,          # Absolute path to the view's sources/ directory
  sessionId: String,         # UUID identifying this view
  createdAt: String,         # ISO-8601 timestamp of view creation
  depHash: String,           # SHA-256 of dependency declaration files
  dependencyCount: Int,      # Number of dependencies in the view
  scopes: String[]           # Scopes used for this view (empty = all)
}
```

#### Scenario: Initial sync

- **WHEN** `sync_dependencies` is called for the first time on a project
- **THEN** the server analyzes the project, downloads dependency sources, creates a view directory, and returns the view path and metadata

#### Scenario: Cached sync

- **WHEN** `sync_dependencies` is called for a project that already has an in-memory cached view
- **THEN** returns the cached view immediately without re-analysis or re-download

#### Scenario: Sync with fresh=true

- **WHEN** `sync_dependencies` is called with `fresh=true`
- **THEN** the in-memory cache is invalidated, ORT analysis is re-run, but existing CAS downloads are re-used

#### Scenario: Sync with scopes filter

- **WHEN** `sync_dependencies` is called with `scopes = ["compileClasspath"]`
- **THEN** only dependencies in the specified scopes are included in the view

#### Scenario: Sync returns structured response

- **WHEN** `sync_dependencies` completes successfully
- **THEN** the response contains `viewPath`, `sessionId`, `createdAt`, `depHash`, `dependencyCount`, and `scopes`

### Requirement: check_dependencies MCP tool

The system SHALL provide an MCP tool `check_dependencies` that performs a lightweight staleness check without downloads or analysis.

**Tool Signature:**

```
check_dependencies(
  projectRoot: String,        # Absolute path to the project root directory
  scopes?: String[]           # Optional: scope names to filter view lookup. When provided, only views matching these scopes are checked. Default: select most recent view.
) → {
  viewExists: Boolean,       # Whether a view directory exists on disk for this project
  fresh: Boolean,            # Whether the stored depHash matches current dependency files
  age: String?,              # ISO-8601 duration since view creation (null if no view)
  depHash: String?,          # Stored hash from manifest (null if no view/manifest)
  currentDepHash: String,    # Freshly computed hash of current dependency files
  dependencyCount: Int?,     # Number of dependencies in the stored manifest (null if no manifest)
  scopes: String[]           # Scopes of the view that was checked
}
```

#### Scenario: check with existing fresh view

- **WHEN** `check_dependencies` is called and a view exists with matching depHash
- **THEN** returns `{ viewExists: true, fresh: true, age: <duration>, depHash: <hash>, currentDepHash: <same>, dependencyCount: <n> }`

#### Scenario: check with stale view

- **WHEN** `check_dependencies` is called and a view exists but depHash differs
- **THEN** returns `{ viewExists: true, fresh: false, age: <duration>, depHash: <stored>, currentDepHash: <different>, dependencyCount: <n> }`

#### Scenario: check with no view

- **WHEN** `check_dependencies` is called and no view exists for this project
- **THEN** returns `{ viewExists: false, fresh: false, age: null, depHash: null, currentDepHash: <hash>, dependencyCount: null }`

#### Scenario: check with scopes filter

- **WHEN** `check_dependencies` is called with `scopes = ["compileClasspath"]`
- **THEN** only views matching those scopes are considered; other scoped views for the same project are ignored

#### Scenario: check is cheap

- **WHEN** `check_dependencies` is called
- **THEN** no downloads are initiated and no ORT analysis is performed (only manifest reading and dep-file hashing)

### Requirement: Tool response format

Both tools SHALL return structured Markdown suitable for LLM consumption. The response SHALL include:

- A brief human-readable summary (1-2 lines)
- The structured data as formatted key-value pairs
- For `sync_dependencies`: the `viewPath` prominently for easy extraction

#### Scenario: sync_dependencies response format

- **WHEN** `sync_dependencies` completes
- **THEN** the response includes a line like `viewPath: C:\cache\views\{uuid}\sources` that the agent can extract
