## ADDED Requirements

### Requirement: Test Harness Fixture

The system SHALL provide a `ProjectFixture` DSL that allows tests to scaffold temporary project directories with required manifest files (e.g., `build.gradle`, `package.json`, `pom.xml`, `Cargo.toml`, `go.mod`).

#### Scenario: Setup Kotlin JVM Project

- **WHEN** a test requests a Kotlin JVM project via `kotlinJvm { addDependency(...) }`
- **THEN** the fixture creates a `build.gradle` with the `org.jetbrains.kotlin.jvm` plugin and the specified dependencies

#### Scenario: Setup Kotlin Multiplatform Project

- **WHEN** a test requests a KMP project with specific targets (e.g., `jvm()`, `js()`, `wasmWasi()`)
- **THEN** the fixture configures a `build.gradle` applying `org.jetbrains.kotlin.multiplatform` and correctly structures the `commonMain` dependencies

#### Scenario: Setup Multi-Module Gradle Project

- **WHEN** a test adds submodules via `addModule(name, script)`
- **THEN** the fixture creates a root `settings.gradle` including the modules and writes the respective `build.gradle` files in subdirectories

#### Scenario: Setup NPM Workspaces

- **WHEN** a test configures NPM workspaces
- **THEN** the fixture creates the root `package.json` and sub-package `package.json` files, ensuring that `package-lock.json` is generated for each to satisfy ORT's detection requirements
