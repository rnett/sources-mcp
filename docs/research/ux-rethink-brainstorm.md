# UX Rethink: Brainstorming Document

> **Context:** Instead of building our own Lucene indexing/searching layer for dependency sources, we should "download sources → symlink view dir → let the agent use its existing tools." The agent (Junie, Claude, etc.) already has `rg`, `fd`, `ast-grep`, `tilth_search`, etc. — we just need to make the sources accessible on the filesystem.

Status: **Brainstorming Phase** — no decisions made yet.

---

## 1. Current State Recap

**What works:**
- `DependencyAnalyzer`: ORT-based project analysis → `AnalyzerResult` with full dependency graph
- `DependencySourcesDownloader`: Downloads + extracts dependency sources to CAS (Content-Addressable storage)
- `SourcesMcpEnvironment`: Manages `cache/`, `cas/`, `locks/`, `analyzer/` directory structure
- `FileLockManager`: Advisory locking for concurrent CAS access

**What's designed but not implemented (openspec):**
- `SourcesViewService` with `createOrGetView()` — creates symlink/junction views from CAS
- View path structure: `{viewsDir}/{uuid}/sources/{ecosystem}/{namespace}/{name}/{version}`
- `manifest.json` with metadata per view
- Time-based cleanup (24h default)
- Scope filtering

**What's NOT designed/implemented at all:**
- Lucene indexing (the original planned search layer)
- MCP tools for searching dependency sources
- Any MCP tools for setting up filesystem access
- A `read_dependency_sources` tool (mentioned in GAP_ANALYSIS but not designed)

---

## 2. Core Principle: "Don't Build Search, Build Access"

The fundamental insight: **agents already have powerful search tools.** Our job shifts from "provide search results" to "prepare the filesystem for the agent to search."

### What Agents Can Already Do

| Capability | Agent Tools |
|---|---|
| File discovery | `fd`, `find`, `tilth_files`, `glob` |
| Text search | `rg` (ripgrep), `grep`, `tilth_search`, `ast-grep` |
| Symbol search | `tilth_search` (symbol mode), language servers |
| Code reading | `cat`, `tilth_read`, IDE open |
| Cross-referencing | `tilth_search` (callers mode), `ast-grep` |

### What We Need to Provide

1. **Filesystem access** to dependency sources in a discoverable location
2. **Minimal metadata** so the agent knows what's available
3. **Lifecycle management** so views don't accumulate forever
4. **Clarity about "what is where"** so the agent can navigate without us

---

## 3. Option Families

### Option A: MCP Tools That Set Up Filesystem Access

**Concept:** The MCP server provides a tool (or small set of tools) that analyzes a project, downloads sources, creates a symlink view directory, and returns the path. The agent then uses its own tools on that path.

#### A1: Single "Setup" Tool

- Tool: `setup_dependency_sources(projectRoot, scopes?, fresh?)` → returns `{ path: "/path/to/view", manifest: {...} }`
- All discovery, searching, and reading is done by the agent using its native tools on the returned path

#### A2: Setup + Lightweight Helpers

- Same setup tool as A1, PLUS minimal helper tools:
  - `resolve_dependency_owner(path)` — "which dependency owns file X?"
  - `list_dependency_sources(projectRoot)` — list available deps without re-creating view
  - `get_dependency_metadata(projectRoot, depId)` — get manifest info for a specific dep

#### A3: Progressive Discovery

- Tool: `discover_dependency_sources(projectRoot)` → returns structured list of available dependencies, ecosystem, versions, etc.
- Agent picks what it needs, then calls `prepare_dependency_sources(projectRoot, deps?)` to symlink specific ones
- Allows the agent to be selective and avoid overwhelming the filesystem

#### Pros of Option A

- **Clean separation of concerns:** MCP server = setup/teardown plumbing; agent = search/read
- **Minimal new code:** Mostly wrapping existing `SourcesViewService` behind an MCP tool
- **No index maintenance:** No Lucene, no incremental updates, no search relevance tuning
- **Flexible:** Agent can use whatever search strategy it wants (rg for text, ast-grep for structure, etc.)
- **Composable:** Agent can combine dependency source search with project source search in one `rg` call

#### Cons of Option A

- **Agent must explicitly invoke the tool:** Adds a step to every session
- **Path management burden on agent:** Agent must remember the returned path and pass it to subsequent tool calls
- **View directory could be anywhere:** Not naturally discoverable by the agent without tool invocation
- **Session coupling:** If the session view dir path changes between runs, agent may get confused

#### Open Questions

- Should the view path be returned as absolute or relative to the project root?
- Should the tool cache the view in-memory (as currently designed) or always create fresh?
- How to handle the case where the agent calls the setup tool multiple times?

---

### Option B: Symlinking Into Project Root

**Concept:** Instead of a standalone view directory somewhere in the cache, symlink dependency sources directly into the project being analyzed. E.g., `.dependencies/` or `build/dependency-sources/`.

#### B1: `.dependencies/` in Project Root

- Tool: `setup_dependency_sources(projectRoot)` → creates `{projectRoot}/.dependencies/{ecosystem}/{namespace}/{name}/{version}`
- `.dependencies/` is added to `.gitignore` automatically
- Sources are literally "in the workspace" so agents naturally discover them

#### B2: `build/dependency-sources/` (Gradle-convention)

- Placed in `build/` which is already `.gitignore`-d
- Aligns with Gradle conventions
- Gets cleaned up with `./gradlew clean` naturally

#### B3: Configurable Target Directory

- Tool accepts a `targetDir` parameter or respects an environment variable
- Could be `.dependencies/`, `build/dependency-sources/`, or any user-specified path

#### B4: Hybrid — Both Standalone View + Project Symlink

- MCP tool creates the standalone view in `cache/views/` (as currently designed)
- AND optionally creates a symlink from `{projectRoot}/.dependencies/` → `{viewsDir}/{uuid}/sources/`
- Best of both worlds: session-managed lifecycle + project-relative discoverability

#### Pros of Option B

- **Immediately discoverable:** Agent tools (`fd`, `rg`, `tilth_files`) operating on the project root will find dependencies naturally
- **No "magic path" to remember:** Agent doesn't need to track a returned path
- **Works with existing agent workflows:** Agents already search from project root
- **Cleanup is natural:** `build/` gets cleaned with Gradle; `.dependencies/` is just a directory
- **Relative paths are stable:** Symlinks within the project don't change between sessions

#### Cons of Option B

- **Pollutes project directory:** Users may not want dependency sources in their workspace
- **`.gitignore` management:** Must ensure `.dependencies/` is gitignored; what if user has custom gitignore rules?
- **Cleanup responsibility:** If not in `build/`, cleanup is the agent's or user's responsibility
- **Multiple projects sharing one root?** Edge case: monorepo with multiple sub-projects
- **Path length:** `{projectRoot}/.dependencies/{ecosystem}/{namespace}/{name}/{version}` can get very long on Windows
- **Symlink nesting:** Symlinking from project to view dir (which is itself symlinks) creates nesting complexity

#### Open Questions

- Should `.dependencies/` be a symlink to the view dir (Option B4), or should the source symlinks go directly into `.dependencies/`?
- How to handle the case where the project root is a Git repo and the user has custom `.gitignore` rules?
- What happens when the user runs `git clean -fdx`? Should dependency sources survive that?
- Should we automatically add to `.gitignore` or warn the user?

---

### Option C: Hybrid Approaches

**Concept:** Combine Option A and Option B — provide both a standalone view dir AND the option to symlink into the project. Start with basic filesystem access and progressively add lightweight metadata/helper tools.

#### C1: Progressive Enhancement Path

**Phase 1 (Minimal Viable):**
- Tool: `read_dependency_sources(projectRoot)` → returns path to view dir + summary
- No symlinks into project; no helper tools
- Agent uses own tools on the returned path

**Phase 2 (Project Integration):**
- Add option to symlink into project: `read_dependency_sources(projectRoot, symlinkIntoProject=true)`
- Creates `{projectRoot}/.dependencies/` → links to view dir

**Phase 3 (Helper Tools):**
- `resolve_dependency_owner(path)` — reverse lookup
- `dependency_info(projectRoot, depId)` — metadata for one dep
- `list_dependency_views()` — list all active views

#### C2: Configuration-Driven

- Environment variable `SOURCES_MCP_SYMLINK_STRATEGY`:
  - `"view-dir"` (default) — standalone view in cache dir, return path
  - `"project"` — symlink into `{projectRoot}/.dependencies/`
  - `"both"` — do both
- Each tool call can override via parameter

#### C3: "Smart Default" Based on Context

- If the agent provides a `projectRoot`, symlink into project (the agent clearly wants to operate in that context)
- If the agent queries without a project root, provide a list of available views
- The server "infers" the right strategy from how the agent is calling it

#### Pros of Option C

- **Maximum flexibility:** Supports different agent workflows
- **Graceful evolution:** Can ship the minimal version first and add features
- **No lock-in:** Agents that prefer one approach can use it; others can use a different one
- **User-configurable:** Power users can set preferences via env vars

#### Cons of Option C

- **More code to maintain:** Every option adds complexity
- **Agent confusion:** Too many options may make the tool harder to use
- **Decision paralysis on our end:** Which strategy is the "recommended" one?

---

### Option D: Minimalist / "Just the Path"

**Concept:** A single tool that does everything and returns a path. That's it. No search tools, no indexing, no helper tools. The server is purely a plumbing layer.

#### D1: `getDependencySources(projectRoot, scopes?)`

- Analyzes project, downloads sources to CAS, creates symlink view, returns:
  ```
  {
    "path": "/home/user/.mcps/rnett-sources-mcp/cache/views/uuid/sources/",
    "manifest": { ... },
    "dependencyCount": 42
  }
  ```
- That's the entire public API surface

#### D2: Extremely Minimal — Environment-Based

- NO MCP tools at all
- On startup, the MCP server automatically creates a view for a configured project root
- The view path is exposed as an environment variable or written to a well-known file
- The agent just needs to know to look at `$SOURCES_MCP_VIEW_DIR` or `~/.mcps/sources-mcp/current-view`

#### D3: File-Based Contract

- Tool writes the view path to a file: `{projectRoot}/.sources-mcp-view`
- Agent reads that file to find the view directory
- No tool return value needed; just a file-based handshake

#### Pros of Option D

- **Dead simple to implement:** One tool, no complex logic
- **Dead simple to use:** Agent calls one thing, gets one path
- **Minimal surface area for bugs:** Very little code to test
- **Clear mental model:** "This gives you a path. Do what you want with it."

#### Cons of Option D

- **No discoverability:** Agent can't ask "what dependencies are available?" without creating the full view
- **No selectivity:** Always creates everything; can't ask for just one dependency
- **No metadata beyond the manifest:** Agent has to parse the manifest.json itself
- **No incremental updates:** Always creates/returns the full view
- **Tight coupling to the view directory structure:** If we change the structure, agents break

---

## 4. Cross-Cutting Design Considerations

### 4.1 View Directory Structure

**Current design:** `{ecosystem}/{namespace}/{name}/{version}` with ecosystem prefix always present.

**Alternatives to consider:**

| Structure | Example | Pros | Cons |
|---|---|---|---|
| Ecosystem-prefixed (current) | `Maven/org.apache.commons/commons-lang3/3.12.0` | Prevents cross-ecosystem collisions | Verbose; ecosystem names inconsistent (Maven vs Maven-Gradle) |
| Flat PURL-based | `pkg_maven_org.apache.commons_commons-lang3@3.12.0` | Simple, flat, easy to glob | Ugly; hard to navigate manually |
| Ecosystem-optional | `org.apache.commons/commons-lang3/3.12.0` (single ecosystem) | Clean for single-ecosystem projects | Ambiguous for multi-ecosystem; needs detection logic |
| PURL hierarchy | `maven/org.apache.commons/commons-lang3/3.12.0` | Standardized; PURL is well-known | PURL types differ from ORT ecosystem names; need mapping |
| Group-Artifact-Version (Gradle-style) | `com.google.guava/guava/33.0.0-jre` | Familiar to JVM devs | Doesn't generalize to NPM/PyPI; scoped packages confusing |

**Recommendation for filesystem-first UX:** Keep ecosystem-prefixed but consider allowing ecosystem-optional mode for single-ecosystem projects (as mentioned in GAP_ANALYSIS).

### 4.2 View Lifecycle

**Current design:** Time-based pruning (24h default), plus optional session-end cleanup.

**New considerations:**
- With Option B (project symlinks), the lifecycle is tied to the project, not the session
- Should manual cleanup be exposed as an MCP tool (e.g., `cleanup_dependency_sources(projectRoot)`)?
- Should we provide a `--ttl` parameter so the agent can say "I only need this for 30 minutes"?
- What about "pinning" views for long-running research sessions?

### 4.3 Metadata & Navigation

**What does the agent need to navigate effectively?**

| Metadata | Priority | Provided By |
|---|---|---|
| Path to sources root | **Critical** | Tool return value |
| List of available dependencies | **High** | `manifest.json` |
| Ecosystem for each dependency | **High** | Path structure + manifest |
| Which dependency owns file X | **Medium** | Reverse-lookup helper (if we build it) |
| CAS key for re-download | **Low** | manifest.json |
| Scope each dep belongs to | **Low** | manifest.json |

**Observation:** Most metadata is already in the path structure. `manifest.json` is a nice-to-have backup. The agent can derive ecosystem, namespace, name, and version from the path alone.

### 4.4 "Who Owns This File?" Problem

When the agent is searching with `rg` and finds a match in `sources/Maven/com/google/guava/guava/33.0.0/com/google/common/collect/ImmutableList.java`, it can infer the dependency from the path. But what if:

- The agent gets a file path from a search result without the full context?
- The agent is navigating and loses track of where it is?
- Two dependencies have overlapping file names?

**Solutions:**
1. **Path convention is enough** — structure makes ownership obvious
2. **Lightweight helper tool** — `resolve_dependency_owner("/path/to/file")` → returns dep info
3. **Per-dependency `.dep-info` file** — a small JSON file at each dependency root with metadata
4. **Do nothing** — agents can read the manifest.json

### 4.5 Project's Own Sources in the View

**Requirement:** Should the project's own sources be included in the view?

**Arguments for:**
- Unified search across project + dependencies
- Agent doesn't need to distinguish between "my code" and "their code"

**Arguments against:**
- Project sources are already accessible in the workspace
- Adds complexity (project source layout differs from dependency layout)
- Creates potential for confusion between project and dependency versions of same file

**Recommended approach:** Do NOT include project sources in the view. The agent already has direct access to project sources. The view is specifically for dependency sources. If the agent wants to search both, it can run `rg` on both the project root and the view path.

### 4.6 Source JARs vs. Pre-Extracted Sources

**Current behavior:** `DependencySourcesDownloader` always extracts archives. No raw JARs in CAS.

**Considerations:**
- Source JARs are always extracted — this is correct
- VCS checkouts are downloaded to a directory — no extraction needed
- The CAS always contains directories, never archives

**Recommendation:** No change needed. Always-extracted is the right model.

### 4.7 Windows Junction Limitations

Already addressed in the current design:
- Junctions via `mklink /J` (no admin required)
- Path length documented (MAX_PATH = 260 chars)
- Mitigation: UUID-only dir names, warn on long paths

**New consideration for Option B:** If symlinking into project root, the project root path is PART of the symlink source, which makes path length issues more likely. A project at `C:\Users\username\Documents\very-long-project-name\...` could easily exceed MAX_PATH when combined with `\.dependencies\Maven\org.apache.commons\commons-lang3\3.12.0`.

### 4.8 Scope Limiting / Avoiding Agent Overwhelm

**Problem:** A large project (e.g., Spring Boot app) could have 200+ dependencies. If we symlink all of them, the agent's tools might return overwhelming results.

**Solutions:**
1. **Scope filtering (already designed):** `scopes = ["compileClasspath"]` limits scope
2. **Dependency count in tool response:** Agent sees "42 dependencies available" and can decide
3. **Selective symlinking (Option A3):** Agent picks specific deps
4. **`.dependency-filter` file:** A gitignore-style file in the view root that the agent can populate to exclude deps
5. **Natural agent behavior:** Agents typically search with specific patterns, so 200 deps is manageable with good search queries

**Recommendation:** Start with scope filtering + dependency count. Add selective symlinking only if needed. Agents are good at filtering search results.

---

## 5. Recommendation Matrix

| Option | Implementation Effort | Agent UX Quality | Flexibility | Maintainability | Overall |
|---|---|---|---|---|---|
| **A1** (Single setup tool) | 🟢 Low | 🟡 Good | 🟡 Medium | 🟢 Good | ⭐⭐⭐⭐ |
| **A2** (Setup + helpers) | 🟡 Medium | 🟢 Great | 🟢 High | 🟡 Medium | ⭐⭐⭐⭐ |
| **A3** (Progressive discovery) | 🔴 High | 🟢 Great | 🟢 High | 🟡 Medium | ⭐⭐⭐ |
| **B1** (.dependencies/ in project) | 🟢 Low | 🟢 Great | 🟡 Medium | 🟡 Medium | ⭐⭐⭐⭐⭐ |
| **B2** (build/dependency-sources/) | 🟢 Low | 🟢 Great | 🟡 Medium | 🟢 Good | ⭐⭐⭐⭐ |
| **B4** (Hybrid view + project symlink) | 🟡 Medium | 🟢 Great | 🟢 High | 🟡 Medium | ⭐⭐⭐⭐⭐ |
| **C1** (Progressive enhancement) | 🔴 High | 🟢 Great | 🟢 High | 🔴 Challenging | ⭐⭐⭐ |
| **C2** (Configuration-driven) | 🟡 Medium | 🟢 Great | 🟢 High | 🟡 Medium | ⭐⭐⭐⭐ |
| **D1** (Single getDependencySources) | 🟢 Low | 🟡 Good | 🔴 Low | 🟢 Good | ⭐⭐⭐ |
| **D2** (Environment-based) | 🟢 Low | 🔴 Poor | 🔴 Low | 🟢 Good | ⭐⭐ |

---

## 6. Emerging Themes & Insights

### Theme 1: The Path IS the API

The directory structure IS the interface. If it's structured well (ecosystem/namespace/name/version), the agent doesn't need separate metadata tools. The path tells the agent everything it needs to know.

### Theme 2: Symlink into Project is the "Killer Feature"

Option B variants score highest because they remove a step from the agent's workflow. The agent doesn't need to "remember a returned path" or "call a special tool first" — the sources are just there, discoverable alongside the project.

### Theme 3: Start Minimal, Add Only What's Needed

The risk of Option C (progressive enhancement) is building things nobody needs. Better to start with the simplest thing (D1 or B1) and add helpers only when actual agent usage reveals pain points.

### Theme 4: Session vs. Project Lifecycle Tension

The current design ties views to server sessions. Option B ties views to projects. These are fundamentally different lifecycle models. We need to pick one as primary and make the other optional.

### Theme 5: Don't Solve Problems Agents Don't Have

Agents already know how to search, filter, and navigate. We shouldn't anticipate problems like "200 deps is too many" — let real usage inform us. Agents might handle it fine.

---

## 7. Top Contenders for Deeper Evaluation

### Contender 1: B4 — "View + Project Symlink" ⭐⭐⭐⭐⭐

**What:** MCP tool creates a standard view in cache, then symlinks `{projectRoot}/.dependencies/` → view's `sources/` dir. Returns both paths.

**Why it wins:**
- Project discoverability (B) + session-managed lifecycle (A)
- Clean separation: cache manages lifecycle; project symlink provides access
- Agent can use either path; both work
- `.dependencies/` is a single symlink; easy to clean up

**Risks:**
- Two levels of symlink indirection (more things to break)
- `.gitignore` management needed
- More implementation complexity

### Contender 2: B1 — "Just `.dependencies/`" ⭐⭐⭐⭐⭐

**What:** MCP tool creates symlinks directly in `{projectRoot}/.dependencies/{ecosystem}/{namespace}/{name}/{version}`. No separate view dir.

**Why it wins:**
- Simplest for the agent — no special tool invocation needed after setup
- Sources are "just there" in the project
- Natural cleanup: delete `.dependencies/`
- Low implementation effort

**Risks:**
- No session-managed lifecycle (views accumulate)
- Pollutes project directory
- `.gitignore` management
- Path length issues more likely

### Contender 3: A1 — "Single Setup Tool" ⭐⭐⭐⭐

**What:** MCP tool returns path to a managed view directory in cache.

**Why it wins:**
- Cleanest separation of concerns
- Session-managed lifecycle built-in
- No project pollution
- Easiest to implement

**Risks:**
- Agent must explicitly call the tool
- Agent must remember/track the returned path
- Less discoverable than project-relative approaches

---

## 8. Open Questions (for discussion)

1. **Should the project's own sources be included in the view?** Leaning: No.
2. **Should views be session-scoped or project-scoped?** Leaning: Session-scoped for lifecycle, project-scoped for access.
3. **Do we need a "resolve owner" helper tool?** Leaning: Not in v1 — the path structure is sufficient.
4. **Should we support selective dependency symlinking?** Leaning: Not in v1 — scope filtering + dependency count is enough.
5. **What happens to `manifest.json`?** Keep it — cheap to generate, useful for debugging and edge cases.
6. **Should the MCP tool be synchronous (block until view ready) or async (create in background, poll)?** Leaning: Synchronous with timeout. ORT analysis is already slow; the agent expects to wait.
7. **What's the naming convention for the MCP tool(s)?** `read_dependency_sources`? `setup_dependency_sources`? `get_dependency_sources`? Consistent with `search_dependency_sources` / `read_dependency_sources` from gradle-mcp.
8. **How to handle multiple MCP sessions against the same project?** Current concurrent-access protection (design §9) handles this within a session. Cross-session: each gets its own UUID view dir.

---

## 9. Implementation Path Ideas

### Path 1: "Ship B4 Immediately" (Bold)

1. Implement `SourcesViewService` as designed in openspec
2. Add single MCP tool: `read_dependency_sources(projectRoot, scopes?)` 
3. Tool creates view, optionally symlinks into `{projectRoot}/.dependencies/`
4. That's it — ship and iterate

### Path 2: "Start with A1, Add B Later" (Conservative)

1. Implement `SourcesViewService` as designed
2. Add `read_dependency_sources(projectRoot, scopes?)` → returns path only
3. Observe how agents use it
4. Add project symlinking (B4) if discoverability is a pain point

### Path 3: "Just D1 for Now" (Minimalist)

1. Skip `SourcesViewService` — just create symlinks programmatically in the tool handler
2. Add `get_dependency_sources(projectRoot, scopes?)` → returns path
3. Defer all lifecycle, cleanup, manifest to later
4. Fastest path to something working
