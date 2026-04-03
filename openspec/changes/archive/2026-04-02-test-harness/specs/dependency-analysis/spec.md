## MODIFIED Requirements

### Requirement: Dependency Analysis Execution

The `DependencyAnalyzer` SHALL run the OSS Review Toolkit (ORT) Analyzer against a provided project directory and return an `OrtResult` representing the dependency graph. The analyzer MUST filter out the `Unmanaged` package manager from the
available factories to ensure it only reports on explicitly supported ecosystems and accurately throws errors for empty or unsupported directories.

#### Scenario: Analyze Supported Project

- **WHEN** a valid project directory (e.g., Gradle, NPM) is analyzed
- **THEN** the analyzer returns an `OrtResult` containing the project metadata and dependency graph

#### Scenario: Analyze Unsupported or Empty Directory

- **WHEN** an empty directory or a directory without supported package manager manifests is analyzed
- **THEN** the analyzer throws an `IllegalArgumentException` indicating no supported projects were found

### Requirement: Caching Dependency Results

The `DependencyCacheService` SHALL cache the results of dependency analysis based on a SHA-256 hash of the managed files' contents to speed up subsequent runs on unchanged projects. It MUST only hash actual files, ignoring any directories
that might be erroneously included in the `ManagedFileInfo`.

#### Scenario: Cache Hit

- **WHEN** the same project directory is analyzed multiple times without changes
- **THEN** the analyzer retrieves the `OrtResult` from the cache instead of re-running ORT

#### Scenario: Cache Miss on File Modification

- **WHEN** a managed file (e.g., `pom.xml`) is modified
- **THEN** the analyzer re-runs ORT and updates the cache with the new result
