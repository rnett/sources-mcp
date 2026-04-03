## 1. File Utilities and Locking (Mirrored from `gradle-mcp`)

- [x] 1.1 Implement `FileLockManager` with `withLock` supporting `tryLock`, 60s timeout, and 500ms delay.
- [x] 1.2 Handle transient Windows `IOException` ("access is denied") and `OverlappingFileLockException` in `FileLockManager`.
- [x] 1.3 Implement `FileUtils.atomicMoveIfAbsent` supporting `StandardCopyOption.ATOMIC_MOVE`.
- [x] 1.4 Handle `FileAlreadyExistsException` (CAS collision) in `atomicMoveIfAbsent` by deleting the temporary source.
- [x] 1.5 Handle `NoSuchFileException` and general exceptions in `atomicMoveIfAbsent` with appropriate logging/fallbacks.

## 2. ORT Downloader Integration

- [x] 2.1 Create `DependencySourcesDownloader` service class.
- [x] 2.2 Implement logic to generate unique hashes/keys for dependencies using PURLs.
- [x] 2.3 Integrate ORT `Downloader` to fetch source code into a temporary working directory (using PURL-based temp names).
- [x] 2.4 Implement logic to extract downloaded source artifacts (handled by ORT `Downloader`).

## 3. Implementation of the Storage Flow

- [x] 3.1 Combine `FileLockManager` and `FileUtils.atomicMoveIfAbsent` in `DependencySourcesDownloader`.
- [x] 3.2 Implement the full flow:
    - `if (target.exists()) return target`
    - `FileLockManager.withLock { ... }`
    - `if (target.exists()) return target` (Double-check after lock)
    - Download & Extract to `target.tmp.<uuid>`
    - `FileUtils.atomicMoveIfAbsent(tmp, target)`
- [x] 3.3 Ensure the service returns the final CAS `Path`.

## 4. Testing and Verification

- [x] 4.1 Write unit tests for `FileLockManager` to verify timeout and concurrent acquisition.
- [x] 4.2 Write unit tests for `FileUtils.atomicMoveIfAbsent` covering successful move and CAS collision (delete-on-collision).
- [x] 4.3 Write integration tests for `DependencySourcesDownloader` using a sample dependency (e.g., a small NPM or Gradle project).
- [x] 4.4 Write stress tests with multiple concurrent download requests to the same dependency to verify lock behavior.
