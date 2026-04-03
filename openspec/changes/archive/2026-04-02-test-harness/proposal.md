## Why

We lack thorough integration test coverage for `DependencyAnalyzer` across the various ecosystems supported by ORT (OSS Review Toolkit). Adding a reusable test harness with DSL helpers makes it easy to set up test projects (like Gradle,
Maven, NPM, Cargo, Go) and provides robust verification of dependency extraction logic, ensuring no regressions.

## What Changes

- Add ORT package manager plugins (e.g. `gradle-inspector`, `node-package-manager`, `maven-package-manager`, etc.) to the project configuration.
- Implement a `ProjectFixture` test harness with a fluent Kotlin DSL for easily defining temporary projects with `package.json`, `build.gradle`, `pom.xml`, etc.
- Introduce integration tests for `DependencyAnalyzer` covering Kotlin JVM, Kotlin Multiplatform, Gradle Multi-module, NPM Workspaces, Maven Multi-module, GoMod, and Cargo.
- Correct the ORT plugin usage by adopting `gradle-inspector` instead of the legacy `gradle` plugin.
- Ensure the `DependencyAnalyzer` accurately skips unmanaged directories to prevent false positives.

## Capabilities

### New Capabilities

- `test-harness`: Reusable infrastructure and fluent DSL for setting up and verifying temporary projects across various package management ecosystems.

### Modified Capabilities

- `dependency-analysis`: The underlying mechanism is enhanced to support all modern ORT package managers, specifically moving to `gradle-inspector` and gracefully handling empty directories.

## Impact

- **Code:** `DependencyAnalyzer`, `DependencyCacheService`, `ProjectFixture`.
- **Dependencies:** Extensive updates to `libs.versions.toml` and `build.gradle.kts` to pull in the full suite of ORT package manager plugins.
- **Testing:** Drastically increases coverage and reliability of our dependency analysis capabilities.
