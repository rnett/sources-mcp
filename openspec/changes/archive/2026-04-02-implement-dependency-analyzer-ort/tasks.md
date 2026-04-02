## 1. Setup & Initial Testing

- [x] 1.1 Create `DependencyAnalyzerTest.kt` in `src/test/kotlin`
- [x] 1.2 Add a test case to verify the current skeleton fails (reproduction)
- [x] 1.3 Ensure ORT dependencies are correctly resolved in the test environment

## 2. Core Implementation

- [x] 2.1 Use ORT `OrtResult` directly as return type (removed `DependencyAnalysisResult`)
- [x] 2.2 Initialize ORT `Analyzer` with `AnalyzerConfiguration` in `DependencyAnalyzer`
- [x] 2.3 Implement `analyzeDependencies(projectRoot: Path)` to run the ORT analyzer
- [x] 2.4 Implement central caching by managed file hashes in `~/.mcps/rnett-sources-mcp/cache/analyzer` (extracted to `DependencyCacheService` and `SourcesMcpEnvironment`)
- [x] 2.5 Ensure the returned `OrtResult` is either from memory or loaded from cache

## 3. Verification & Refinement

- [x] 3.1 Verify successful analysis of a sample Gradle project
- [x] 3.2 Verify successful analysis of a sample NPM project
- [x] 3.3 Implement error handling and informative exceptions for failed scans
- [x] 3.4 Ensure the `DependencyAnalysisResult` correctly encapsulates either the model or path
