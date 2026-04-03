## Context

ORT supports a vast array of package managers, but our `DependencyAnalyzer` only had a few simple tests. We need robust, ecosystem-specific integration tests to prevent regressions when bumping ORT versions or modifying cache mechanisms.
Testing these tools requires generating real project files (like `build.gradle`, `package.json`, etc.) on the fly.

## Goals / Non-Goals

**Goals:**

- Provide a reusable test harness (`ProjectFixture`) to scaffold temporary projects for various package managers.
- Validate that `DependencyAnalyzer` can successfully extract dependencies from Gradle (including multi-module, JVM, KMP), NPM (including workspaces), Maven, Cargo, and GoMod.
- Ensure the `gradle-inspector` plugin is used in place of the deprecated legacy `gradle` plugin.
- Prevent false positives by correctly ignoring "Unmanaged" empty directories.

**Non-Goals:**

- Providing 100% test coverage for every single ORT plugin (only the most common/supported ones are targeted).
- Implementing new package managers in ORT.

## Decisions

- **Use of `gradle-inspector`:** ORT's `gradle` plugin is legacy; `gradle-inspector` is the modern approach and leverages the Gradle Tooling API correctly. We removed `gradle-plugin` and legacy `gradle` to prevent classpath conflicts.
- **`ProjectFixture` DSL:** A fluent Kotlin DSL was chosen to make writing tests highly readable and concise, hiding the boilerplate of file creation.
- **Skipping "Unmanaged" Projects:** ORT's "Unmanaged" package manager claims any directory, which breaks tests for empty directories or unsupported projects. We explicitly filter it out of `PackageManagerFactory.ALL.values`.
- **Cache File Validation:** Added `.isFile` checks before hashing files in `DependencyCacheService` to prevent `FileNotFoundException` (Access Denied on Windows) when ORT returns directories as managed files.

## Risks / Trade-offs

- **Risk:** Integration tests may be slow because ORT downloads dependencies.
    - *Mitigation:* We use simple projects with few dependencies to keep test execution time under ~20s.
- **Risk:** Mixing ORT plugin versions (e.g., v13 and v83) causes `NoClassDefFoundError`.
    - *Mitigation:* Pin all ORT plugins to a single version using a Gradle version catalog bundle.
