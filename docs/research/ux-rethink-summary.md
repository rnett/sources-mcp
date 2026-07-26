# UX Rethink: Summary

> **TL;DR:** Instead of building a Lucene search layer, make dependency sources available on the filesystem and let the agent use its own tools (`rg`, `fd`, `ast-grep`, `tilth_search`). The key question: **where on the filesystem?**

---

## The Core Insight

Agents already have world-class search tools. Our job is **plumbing, not search**. Download sources → create symlinks → get out of the way.

---

## Top 4 Recommended Approaches

### 1. ⭐ B4: View + Project Symlink (Hybrid)

**What:** MCP server creates a managed view dir in cache (`cache/views/{uuid}/sources/`), then symlinks `{projectRoot}/.dependencies/` → view's `sources/` dir.

**Why it wins:**
- Agent discovers deps naturally in the project (via `fd`, `rg`, `tilth_files` from project root)
- Server still manages lifecycle (time-based cleanup, manifest.json)
- Clean separation: cache manages; project provides access
- `.dependencies/` is a single symlink — easy to create and delete

**Trade-offs:**
- Two levels of symlink indirection (project → view → CAS entries)
- `.gitignore` management needed
- Slightly more implementation effort than B1

**Best for:** Projects where the agent frequently searches both project and dependency code.

---

### 2. ⭐ B1: Direct `.dependencies/` in Project

**What:** MCP tool creates symlinks directly in `{projectRoot}/.dependencies/{ecosystem}/{namespace}/{name}/{version}`. No separate view directory.

**Why it wins:**
- Simplest experience: deps are "just there" in the project
- No special paths to track
- Agents discover deps without any tool invocation (after first setup)
- Lowest implementation effort

**Trade-offs:**
- No session-managed lifecycle — views accumulate until manually deleted
- "Pollutes" project directory (though it's in `.dependencies/`)
- Path length issues more likely on Windows
- `.gitignore` management required

**Best for:** Quick prototyping, simple workflows, single-project usage.

---

### 3. ⭐ A1: Single Setup Tool (View Dir in Cache)

**What:** MCP tool analyzes project, downloads sources, creates symlink view in cache, returns the path. Agent uses its own tools on the returned path.

**Why it wins:**
- Cleanest separation of concerns
- Session-managed lifecycle built-in (time-based pruning)
- No project directory pollution
- Easiest to implement, lowest risk

**Trade-offs:**
- Agent must explicitly call the tool every session
- Agent must remember/track the returned path
- View path is not naturally discoverable

**Best for:** Conservative first ship, multi-project setups, server-oriented usage.

---

### 4. ⭐ C2: Configuration-Driven (Flexible)

**What:** Environment variable `SOURCES_MCP_SYMLINK_STRATEGY` controls behavior:
- `"view-dir"` → standalone cache view (like A1)
- `"project"` → symlink into project (like B1/B4)
- `"both"` → do both

**Why it wins:**
- Maximum flexibility for different agent workflows
- User-configurable without code changes
- Can evolve without breaking existing users

**Trade-offs:**
- More code paths to maintain
- Agent may need to detect which mode is active
- Decision burden on us: what's the default?

**Best for:** Production deployment where different users have different needs.

---

## Key Design Decisions (Recommended Defaults)

| Decision | Recommendation |
|---|---|
| Include project sources in view? | **No** — project sources already in workspace |
| View lifecycle | **Session-scoped** — time-based pruning (24h) + manual cleanup tool |
| Metadata format | **`manifest.json`** — cheap, self-describing, already designed |
| Dependency resolution | **Always extract** source archives — no raw JARs |
| Scope limiting | **Scope filtering** + dependency count in response |
| Helper tools ("resolve owner", etc.) | **Not in v1** — path structure is sufficient |
| MCP tool naming | **`read_dependency_sources`** — consistent with gradle-mcp naming |

---

## Quick Decision Guide

| If you want... | Choose... |
|---|---|
| Fastest path to something working | **B1** (`.dependencies/` directly) |
| Most robust, lowest risk | **A1** (view dir in cache) |
| Best agent experience | **B4** (view + project symlink) |
| Maximum flexibility | **C2** (configuration-driven) |
| Absolute minimal code | **D1** (just return a path, no helpers) |

---

## Recommended Path Forward

**Ship B4** (view + project symlink) as the primary approach:

1. Implement `SourcesViewService` from the existing openspec design
2. Add `read_dependency_sources(projectRoot, scopes?, symlinkIntoProject?)` MCP tool
3. Default `symlinkIntoProject = true`
4. Return both `viewPath` and `projectPath` in the response
5. Keep `manifest.json` and time-based cleanup as designed

This gives the best of both worlds: managed lifecycle + natural agent discoverability.

See the full brainstorm at [`ux-rethink-brainstorm.md`](ux-rethink-brainstorm.md).
