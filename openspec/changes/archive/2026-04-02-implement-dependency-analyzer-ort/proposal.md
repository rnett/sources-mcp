## Why

`DependencyAnalyzer` is currently only a skeleton without an implementation. To provide source access across multiple ecosystems (Gradle, NPM, etc.), we need a robust dependency analysis capability. OSS Review Toolkit (ORT) provides this
functionality and is already included as a dependency in the project.

## What Changes

- Implement `DependencyAnalyzer.analyzeDependencies(projectRoot: Path)` using ORT's analyzer components.
- Configure ORT to handle common package managers (Gradle, NPM, etc.) out-of-the-box.
- Support returning a structured dependency report (either as an in-memory `OrtResult` model or a `Path` to an existing result file).
- Avoid unnecessary disk writes; only return a `Path` if the result already exists on disk.

## Capabilities

### New Capabilities

- `dependency-analysis`: The ability to analyze a project root and produce a structured dependency report using ORT.

### Modified Capabilities

<!-- None -->

## Impact

- `dev.rnett.sources.mcp.DependencyAnalyzer` will be implemented.
- The project will begin utilizing the `ort.analyzer` and `ort.model` dependencies.
