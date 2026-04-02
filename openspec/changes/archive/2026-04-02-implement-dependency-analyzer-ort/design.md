## Context

The `DependencyAnalyzer` is a critical component for identifying project dependencies across various ecosystems. We are using the [OSS Review Toolkit (ORT)](https://github.com/oss-review-toolkit/ort) library components, which provide
high-level APIs for running analyzers on project directories. Currently, the implementation is a skeleton.

## Goals / Non-Goals

**Goals:**

- Provide a robust implementation of `analyzeDependencies(projectRoot: Path)` using ORT.
- Successfully analyze dependencies for Gradle and NPM projects.
- Generate an `OrtResult` and serialize it to a file for subsequent processing.

**Non-Goals:**

- Handling source code downloading (handled by `ort-downloader` in a separate component).
- Complex dependency caching (currently, each analysis is standalone).
- Managing external tool installations (e.g., `npm`, `gradle`).

## Decisions

- **Use ORT Analyzer Directly**: We will use ORT's `Analyzer` class to perform the scans. This is the recommended way to use ORT as a library.
- **Return In-Memory Model by Default**: The `analyzeDependencies` function will primarily return an `OrtResult` object. This avoids unnecessary serialization to disk and keeps the results in memory for immediate use.
- **Support Returning Existing Result Path**: If an ORT result already exists on disk (e.g., from a previous manual run), the analyzer can return a `Path` to that file instead of re-performing the analysis.
- **Polymorphic Result Type**: Introduce a `DependencyAnalysisResult` (or similar) sealed class to encapsulate either the `OrtResult` model or the `Path` to an on-disk result.
- **Default Analyzer Configuration**: For the initial implementation, we will use default configurations for the `Analyzer` to ensure broad compatibility without complex setup.

## Risks / Trade-offs

- **External Tool Dependencies**: ORT requires ecosystem-specific tools (e.g., `npm`, `gradle`) to be present in the `PATH`. [Risk] → Analysis fails if tools are missing. [Mitigation] → Catch analysis failures and provide clear error
  messages.
- **Performance**: Analysis can be slow for large projects. [Risk] → Timeouts or resource exhaustion. [Mitigation] → Ensure sufficient heap space (already configured in `build.gradle.kts`).
