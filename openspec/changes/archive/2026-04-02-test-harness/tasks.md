## 1. ORT Plugin Configuration

- [ ] 1.1 Remove the legacy `gradle` package manager plugin and its dependencies from `libs.versions.toml` and `build.gradle.kts`.
- [ ] 1.2 Add `gradle-inspector`, `gradle-model`, and all other supported package manager plugins (NPM, Maven, Go, Cargo, etc.) to a new version catalog bundle.
- [ ] 1.3 Update `build.gradle.kts` to expose the new plugin bundle via `testFixturesApi` so integration tests can use them.

## 2. Analyzer Logic Updates

- [ ] 2.1 Update `DependencyAnalyzer.kt` to explicitly filter out the `Unmanaged` package manager from `PackageManagerFactory.ALL.values` during `findManagedFiles()`.
- [ ] 2.2 Update `DependencyCacheService.kt` to add a `file.isFile` check before attempting to read and hash bytes in `calculateCacheKey()`.

## 3. Test Harness Implementation

- [ ] 3.1 Create `ProjectFixture.kt` with a fluent DSL (`testProject`) for scaffolding temporary test directories.
- [ ] 3.2 Implement basic package manager builders in `ProjectFixture`: `npm`, `maven`, `cargo`, `go`, `pip`.
- [ ] 3.3 Implement `gradle` builder with support for `buildScript`, `settings`, and multi-module setup via `addModule()`.
- [ ] 3.4 Implement specialized Kotlin helpers in `ProjectFixture`: `kotlinJvm` and `kotlinMultiplatform` (with JVM, JS, and Wasm targets).

## 4. Integration Tests

- [ ] 4.1 Update `DependencyAnalyzerTest.testAnalyzeEmptyDirectory()` to expect `IllegalArgumentException` instead of `FileNotFoundException`.
- [ ] 4.2 Create `DependencyAnalyzerIntegrationTest.kt` with tests for basic projects: Gradle, NPM, Maven, GoMod, and Cargo.
- [ ] 4.3 Add advanced integration tests to `DependencyAnalyzerIntegrationTest.kt` covering Kotlin JVM, Kotlin Multiplatform, Gradle multi-module, NPM workspaces, and Maven multi-module setups.
