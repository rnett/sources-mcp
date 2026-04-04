## ADDED Requirements

### Requirement: View service integration with source downloading

The `SourcesViewService` relies on the existing `source-downloading` capability to obtain CAS paths for dependencies. This spec documents the integration requirement for the view service.

**Integration Contract:**

- The source downloading service SHALL return an absolute path to the CAS entry directory (not just success/failure)
- The returned path SHALL be suitable for direct symlinking/junction creation in view generation
- The CAS entry directory SHALL be a directory (not a file) to support junction creation on Windows
- The method signature SHALL be: `DependencySourcesDownloader.downloadSources(pkg: Package, provenance: Provenance): Path`
- If download fails mid-process, the service SHALL throw an exception; no partial state is left in CAS

#### Scenario: CAS path returned for symlinking

- **WHEN** `DependencySourcesDownloader` completes downloading a dependency
- **THEN** the returned path points to the CAS entry directory suitable for symlinking

#### Scenario: CAS entry is always a directory

- **WHEN** a dependency is downloaded and extracted to CAS
- **THEN** the CAS entry path points to a directory (extracted sources are always directories; archives are extracted, not stored as-is)

#### Scenario: Download failure

- **WHEN** `DependencySourcesDownloader` fails to download a dependency
- **THEN** it throws an exception and leaves no partial state in CAS
