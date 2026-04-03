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

## Build & Test Commands

- **Build All**: `./gradlew build`
- **Run Fast Tests**: `./gradlew test` (Targeted testing is preferred).
- **Run All Tests**: `./gradlew test integrationTest`
- **Quality Check**: `./gradlew check` (Linting + All tests)
- **[todo] Update Tools**: `./gradlew :updateToolsList` (Mandatory after metadata changes)
