# Sources MCP Project Memory

This repository contains a Model Context Protocol (MCP) server written in Kotlin, providing access and searching for dependency sources.

WE ARE BOOTSTRAPPING THE PROJECT - it's not complete, doesn't do everything described here yet, and does not care about backwards compatability.
Not-yet-supported items are marked with `[todo]`.

## Primacy Zone: Fundamental Mandates

1. **Security & Secrets**: NEVER log, print, or commit API keys, secrets, or .env contents.
2. **Source Control**: ALWAYS add changes to Git for persistence, but NEVER create commits or push unless explicitly instructed.
3. **Tool Preference**: ALWAYS prefer Gradle MCP tools (`gradle`, `inspect_build`, etc.) over raw shell execution of `./gradlew`.
4. **Verification**: NO CHANGE OR WORK ITEM IS COMPLETE WITHOUT ENSURING THAT THE RELEVENT TESTS PASS, and that there is sufficient test coverage. Use `test` for most changes; `check` or `integrationTest` for wider impacts or features that
   need full integration tests (e.g. the repl).
5. **[todo] Tool Metadata**: After modifying tool descriptions or structure in Kotlin source code, MUST run `./gradlew :updateToolsList` to sync auto-generated documentation (`docs/tools/*.md`) and LLM metadata.
6. **Agent Documentation**: Behavior and features MUST be documented in tool descriptions AND skills to be considered "existing".

---

## WHAT (Architecture & Terminology)

- **[todo] MCP Server**: Main entry point using MCP Kotlin SDK.
- **[todo] Skills**: Specialized agentic workflows in `./src/main/skills/`.
- **[todo] CAS (Content-Addressable Storage)**: Immutable global cache for dependency sources and indices (keyed by content hash).
- **[todo] Session View**: Ephemeral, project-level directory containing junctions to CAS entries and a `manifest.json`.
- **ORT for dependency access**: We are using the [OSS Review Toolkit](https://github.com/oss-review-toolkit/ort) to detect and download dependencies. It supports many ecosystems. We use its components as libraries, rather than using the
  CLI directly.
  - **Package Managers**: Prefer `gradle-inspector` over the legacy `gradle` package manager. ALWAYS filter out the `Unmanaged` package manager from `PackageManagerFactory.ALL` to ensure reliable detection of real projects.

---

## WHY (Architectural Rationale)

- *[todo] *Immutable CAS Model**: Replaced in-place index mutation and complex locking with an immutable generational model to eliminate deadlocks and 60s timeouts on Windows.
- **[todo] Virtual Searching**: Leverages Lucene `MultiReader` to search across multiple dependency indices without physical merging.
- **[todo] Isolated State**: Each tool call operates on a unique session view, ensuring stability during concurrent updates.
- **ORT Mandates**:
  - **Plugin Versions**: Coordinate core and plugin versions carefully (e.g., avoid mixing v13 and v83) to prevent `NoClassDefFoundError` due to breaking core changes.
  - **Managed Files**: ALWAYS check `file.isFile` before reading bytes from `ManagedFileInfo` paths to avoid `FileNotFoundException` (Access Denied) on Windows when a directory is returned instead of a file.
  - **Directory Flattening**: ALWAYS implement a directory flattening step after downloading and extracting sources with ORT (especially for NPM and GitHub tarballs) to ensure a consistent CAS structure (e.g., removing redundant `package/`
    subdirectories).
- **Testing**: Use **Power Assert** for rich failure messages (avoid overly nested assertions to prevent compiler crashes). Reuse class-level test resources for speed. When asserting on the output of an MCP tool that passes through a
  service layer, verify the final re-rendered format (e.g., `Project: :path`) rather than the raw task output format (e.g., `PROJECT: :path`) to prevent false negatives caused by formatting layers.

## Testing & Dependency Management Mandates

- **NPM Workspaces and ORT Detection**: When testing NPM workspaces with ORT, ensure each sub-package has a `package-lock.json` (even if empty `{}`) to guarantee they are correctly identified as individual projects.
- **KMP Target Configuration for ORT**: For Kotlin Multiplatform dependency resolution testing with ORT, configure the `kotlin { ... }` block with explicit targets such as `jvm()`, `js(IR) { browser() }`, and `wasmWasi { nodejs() }`.
- **Automated Gradle Multi-Module Setup**: The standard approach for testing multi-module resolution in ORT involves a root `settings.gradle` file with `include(":module")` statements and corresponding subdirectories, each containing its
  own `build.gradle`.
- **NPM and ORT Testing**: When testing NPM projects with ORT, always provide a valid `package-lock.json` or configure `legacyInstall = true` in `AnalyzerConfiguration` to use `npm install` instead of `npm ci` (which requires a synchronized
  lockfile).
- **Cargo and ORT Testing**: Cargo project detection and resolution in ORT requires both a `Cargo.lock` file and a valid target (e.g., `src/lib.rs` or `src/main.rs`) to prevent `cargo metadata` failures.
  readability when dealing with complex build configurations.

- **Mocking & Future-Proofing**: When refactoring service interfaces mocked in many tests, prioritize using a **data class for parameters** (e.g., `DependencyRequestOptions`). This avoids "boolean blindness" and allows adding new
  configuration flags with defaults without breaking existing test call sites.
- **MCP Design**: Return structured Markdown for LLM reasoning. Use Tooling API for stability. MCP tool descriptions must be self-sufficient enough for standalone use, while skills remain the primary "agentic" interface.
- **Ambiguity Reporting**: Use "exact match -> unique prefix match -> ambiguous prefix match" flow for lookup tools.

---

### General Mandates
- **Research First**: ALWAYS research correct implementation/API usage (especially Kotlin Scripting) before attempting code changes.
- **Rubber Ducking**: Document findings and "think out loud" when stuck.

---

## View Service Invariants (Resolution Plan Review #2)

These invariants were established during the review of the View Directory Lifecycle Management system:

13. **Server Bootstrap Rule**: Any new MCP tool class (like `SyncCheckTools`) MUST be accompanied by either (a) a server bootstrap that wires it, or (b) an integration test that invokes it through the MCP protocol. No "tool exists but nobody calls `registerAll`."

14. **Concurrency Limiter Verification**: Any method performing batch I/O with a user-visible `parallelism` parameter MUST verify that the limit is actually enforced (Semaphore or `flatMapMerge`). A test must verify the limit is honored — `chunked(N)` alone is insufficient.

15. **`createDirectories()` Try-Scope Rule**: All `createDirectories()` calls that are part of multi-step setup (where earlier steps must be cleaned up if later steps fail) MUST be inside the corresponding `try-catch-finally` block, not in variable initializers or `.also{}`.

16. **Scope-Keyed Indexes Rule**: Any cross-process index that keys by project root MUST also include scope/session parameters when multiple scoped views can coexist. The index schema must match the in-memory cache key schema.

17. **Manifest Deserialization Guard**: Any data deserialized from `manifest.json` or similar on-disk artifacts that controls filesystem operations (`Path.of()`, `Files.list()` etc.) MUST be validated for safety before use. Specifically: reject UNC paths, enforce reasonable length limits, validate that strings conform to expected formats (absolute local paths only).

18. **Subprocess Timeout Rule**: Any `ProcessBuilder` invocation that calls `waitFor()` MUST use the timed variant (`waitFor(timeout, unit)`) with `destroyForcibly()` on timeout. Blocking indefinitely on a subprocess is a denial-of-service vector within the coroutine pool.

19. **No `runBlocking` in Shared Utility Code**: Any utility function that may be called from a coroutine context MUST NOT use `runBlocking`. Either make it `suspend` or explicitly document that callers must offload to `Dispatchers.IO`.

20. **`async`-Per-Item Bounded Chunking Rule**: When processing variable-length collections with `async`, the number of concurrently launched coroutines MUST be bounded by a configurable limit (not just the active concurrency). Use `chunked(limit)` or `flatMapMerge(limit)`.

21. **Subprocess from Coroutines Requires `withContext(Dispatchers.IO)`**: Any `ProcessBuilder` or `Runtime.exec()` call made from a coroutine context MUST be wrapped in `withContext(Dispatchers.IO)` to prevent thread blocking.

22. **Keyed-Mutex Registry Cleanup Rule**: Any `ConcurrentHashMap<K, Mutex>` used to serialize per-key operations MUST have an explicit removal policy — either after the critical section completes or via a scheduled cleanup.

23. **Dual-Cache Coordination Rule**: When a class maintains two in-memory caches that are conceptually paired (e.g., `viewCache` and `syncMutexes`), eviction operations MUST update both caches atomically. They should share a single eviction API or have mirrored removal logic.

24. **Error Message Sanitization Rule**: Exception messages that propagate to MCP clients MUST NOT contain absolute filesystem paths. Use dependency identifiers (`pkg.id.toCoordinates()`) or session-relative paths. Full paths may appear in server-side log output only.

25. **Cancellation Propagation Rule**: Any `catch (e: Exception)` block inside a coroutine MUST explicitly re-throw `CancellationException` before catching other exceptions. Swallowing cancellation breaks structured concurrency and can leave partial state on disk.

26. **Integration Test Compilation Gate**: Any change that modifies a service constructor signature MUST update all test call sites (unit + integration) in the same commit. A `compileIntegrationTestKotlin` check in CI should catch this automatically.

27. **Spec-Signature Parity Rule**: When a documented method signature in a spec changes during implementation (e.g., parameter types, added enums, renamed methods), the spec MUST be updated within the same change. Spec drift between design docs and code is a blocking finding.

28. **Configuration Transparency Rule**: When a spec says a value is "configurable via environment variable," the implementation MUST contain code that reads that environment variable. Otherwise, the spec MUST say "configurable via constructor parameter" with the actual mechanism documented.

29. **Single-Scan Rule for View Creation**: All operations within a single `sync()` call that require ORT's `findManagedFiles()` (file discovery, hash computation, analysis) MUST share a single invocation of `findManagedFiles()`. No code path within a single `sync()` may call `findManagedFiles()` more than once.

30. **Cache Boundedness Rule**: Any in-memory cache (especially `ConcurrentHashMap`-based caches) keyed by user-provided inputs (project roots, scopes, etc.) MUST have a bounded maximum size or TTL-based eviction. Unbounded growth in a long-running server process is a memory leak. For local-dev-only caches, document the assumption explicitly if unbounded is intentional.

31. **Vendor-Free Workspace Rule**: No vendored/duplicated source files of external libraries may exist in the repository outside of Gradle-managed dependencies. Leftover prototyping artifacts must be cleaned up before merging to the main branch.

32. **Scope Matching Transparency Rule**: Any deviation from "exact match" in scope filtering (e.g., suffix matching, case-insensitive matching, wildcard matching) MUST be explicitly documented in both the design decision and the behavioral spec. Undocumented matching rules are specification bugs.

## Build & Test Commands

- **Build All**: `./gradlew build`
- **Run Fast Tests**: `./gradlew test` (Targeted testing is preferred).
- **Run All Tests**: `./gradlew test integrationTest`
- **Quality Check**: `./gradlew check` (Linting + All tests)
- **[todo] Update Tools**: `./gradlew :updateToolsList` (Mandatory after metadata changes)
