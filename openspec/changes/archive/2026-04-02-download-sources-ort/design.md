## Context

The `sources-mcp` project aims to provide access to dependency sources. Currently, `DependencyAnalyzer` uses the OSS Review Toolkit (ORT) to resolve dependencies. We now need a mechanism to securely and efficiently download and extract the
actual source code for these dependencies. Because multiple tools or concurrent sessions might request the same dependency sources simultaneously, we need a concurrency-safe storage model. Adopting the Content-Addressable Storage (CAS) and
locking architecture from `gradle-mcp` will solve these concurrency issues.

## Goals / Non-Goals

**Goals:**

- Implement a robust service to download and extract dependency sources using ORT components.
- Implement a Content-Addressable Storage (CAS) directory structure where extracted sources are stored immutably.
- Implement an advisory locking mechanism to prevent race conditions during concurrent downloads of the same dependency.

**Non-Goals:**

- Post-processing, indexing, or parsing of the downloaded sources.
- Advanced garbage collection of the CAS cache (can be added later).
- Session views (symlinking to CAS) are out of scope for this specific downloader component, which focuses only on getting data into the CAS.

## Decisions

1. **Content-Addressable Storage (CAS):**
    - **Rationale:** To avoid re-entrant locks and Windows file handle contention, extracted sources will be stored in an immutable CAS directory (`~/.mcp/sources-mcp/cas/<hash>/` or project-local `.cache/cas/<hash>/`). The hash will be
      derived from the dependency's unique identifier (like its PURL). Once written, this directory is never modified.
2. **Advisory Locking for Downloads (Pattern from `gradle-mcp`):**
    - **Rationale:** Use a file-based advisory lock (`.locks/cas/<hash>.lock`) with a 60-second timeout and 500ms polling delay.
    - **Implementation:** Use `FileChannel.tryLock()` within a `while` loop that handles `OverlappingFileLockException` and transient Windows `IOException` (e.g., "access is denied").
3. **Atomic Move with Collision Handling (Pattern from `gradle-mcp`):**
    - **Rationale:** To prevent corrupted states and handle concurrent writes from different processes.
    - **Strategy:**
        - Extract to a temporary directory (`<hash>.tmp.<uuid>`).
        - Use `Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)` to move to the final CAS path.
        - **CAS Collision:** If the target already exists (another process finished first), discard the temporary directory and return success (idempotency).
        - **Windows Robustness:** Handle `AtomicMoveNotSupportedException` with a standard move fallback.
4. **ORT Downloader Integration:**
    - **Rationale:** ORT already has robust logic for downloading sources (e.g., from VCS or source artifacts). We will leverage its existing APIs rather than rolling our own HTTP/VCS clients.

## Risks / Trade-offs

- **[Risk] Stale Locks:** A process might crash while holding an advisory lock.
    - **Mitigation:** Use `FileChannel` locks which are automatically released by the OS when the process or thread dies. For long-term stale lock files on disk, they can be ignored or deleted if older than 1 hour (as per
      `concurrency-cas-architecture.md`).
- **[Risk] Windows Atomic Move Failures:** Moving directories on Windows can fail if files are open.
    - **Mitigation:** Follow `FileUtils.atomicMoveIfAbsent` pattern which includes `deleteRecursively` for temporary directories on collision and retries/fallbacks for common exceptions.
