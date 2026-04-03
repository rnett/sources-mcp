## Why

We need a robust and safe mechanism to download and extract dependency sources using the OSS Review Toolkit (ORT) to make them available for further analysis and indexing. Implementing a Content-Addressable Storage (CAS) with appropriate
locking will ensure safe, concurrent, and idempotent source downloads, adopting the proven approach used in `gradle-mcp`.

## What Changes

- Create a new service (e.g., `DependencySourcesDownloader` or `OrtSourcesDownloader`) similar in structure to `DependencyAnalyzer`.
- Implement a Content-Addressable Storage (CAS) mechanism for storing downloaded and extracted sources safely.
- Add necessary locking mechanisms to support concurrent downloads without race conditions.
- Note: Post-processing and indexing of the downloaded sources are explicitly out of scope for this initial implementation.

## Capabilities

### New Capabilities

- `source-downloading`: The ability to safely download and extract dependency sources via ORT into a local Content-Addressable Storage (CAS) with locking.

### Modified Capabilities

## Impact

- Introduces a new CAS directory structure for storing extracted source artifacts.
- Adds a new ORT-based downloader service to the project.
- No expected negative impact on existing components like `DependencyAnalyzer`.
