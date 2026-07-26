# 🏛️ Resolution Plan — `reports/review-3.md`

**Target**: View Directory Lifecycle Management  
**Date**: 2026-05-09  
**Total findings**: 48 (3 Critical, 16 Major, 29 Minor)  
**Prior reports**: `review-1.md` (resolved), `review-2.md` (resolved)  

---

## Section 1: Cross-Reference & Recurrence Analysis

| Finding | Status | Prior Report | Notes |
|---------|--------|-------------|-------|
| #2 (forceRedownload double-check) | **REGRESSION** | Review 1 #2 | Resolution-1 added `forceRedownload` parameter but missed the lock-internal double-check. The original issue is incompletely fixed. |
| #3 (REDOWNLOAD_SOURCES evicts analysis cache) | **New** | — | Introduced during `SyncMode` migration |
| #17 (mainClass) | **ALREADY FIXED** | — | `fun main()` exists at SourcesMcpServer.kt:53. The `Kt` facade class resolves. |
| #33 (God Object) | **RECURRING** | Review 2 #8 | Deferred for bootstrap phase. Not addressed. |
| #38 (weakly consistent iteration) | **Known limitation** | Review 2 #20 | Same ConcurrentHashMap pattern; documented as acceptable. |

All other findings are **new** — introduced by the current code delta under review.

---

## Section 2: Evaluation & Classification

### INVALID / Already Fixed

| # | Finding | Verdict |
|---|---------|---------|
| 17 | `mainClass` may not resolve — no `main()` | **ALREADY FIXED**. `fun main()` exists at SourcesMcpServer.kt:53. The Kotlin compiler generates `SourcesMcpServerKt` with a static `main` method, matching the `mainClass` in `build.gradle.kts`. `java -jar` will work. |

### Valid — Confirmed Against Source Code

All remaining 47 findings are confirmed valid by reading the actual source files.

| Classification | Count | Findings |
|---------------|-------|----------|
| **VALID — Code fix needed** | 26 | #1, #2, #3, #5, #6, #7, #8, #9, #10, #11, #12, #13, #14, #15, #16, #18, #20, #21, #22, #23, #25, #26, #34, #35, #36, #37 |
| **VALID — Spec/Doc only** | 10 | #4, #40, #41, #42, #43, #44, #45, #46, #47, #48 |
| **VALID — Idiom/Cleanup** | 9 | #27, #28, #29, #30, #31, #32, #39, #19, #24 |
| **DEFER — Deferred per bootstrap** | 1 | #33 |
| **VALID — Accept limitation** | 1 | #38 |

No findings require user architectural decisions. All actionable items have clear technical fixes.

---

## Section 3: Spec/Doc Changes Required FIRST

Before any code changes, update:

1. **`openspec/changes/view-directory-management/specs/view-directory-creation/spec.md`**:
   - Fix `casKey` format to `{algorithm}-{hash}` (Finding #4)
   - Update `sync()` signature to use `SyncMode` enum (Finding #40)
   - Document suffix-matching for scope filtering (Finding #41)
   - Fix `redownload` CAS location claim (Finding #42)
   - Add error-handling scenarios for every `catch` block in `sync()` (Finding #43)
   - Add stale cache entry validation scenario (Finding #46)
   - Add `check()` exceptional-condition scenarios (Finding #47)

2. **`openspec/changes/view-directory-management/design.md`**:
   - Update Decision 14: remove `ViewServiceConfig` reference (Finding #45)

3. **`openspec/changes/view-directory-management/design.md`** or **`docs/design/view-lifecycle-management.md`**:
   - Align lifecycle diagrams — one should defer to the other (Finding #48)

4. **`openspec/changes/view-directory-management/specs/view-cleanup/spec.md`**:
   - Remove "configurable via environment" claim or implement it (Finding #44)

5. **`AGENTS.md`** — Add all 14 proposed invariants (#18–#31) from review-3

---

## Section 4: Phase-Ordered Execution Plan

---

### Phase 1: Critical Compilation Blockers

#### Finding #1 — Integration Tests Fail to Compile
- **Severity**: Critical
- **Rationale**: `cacheService` was removed from `SourcesViewService` constructor per Resolution Plan #2 Finding #21, but 4 integration test call sites still pass it. `compileIntegrationTestKotlin` fails; CI blocked.
- **Gotchas**:
  - 4 call sites across 2 files: `CheckDependenciesIntegrationTest.kt:28, :55` and `SourcesViewServiceIntegrationTest.kt:29, :106`
  - `DependencyCacheService(testEnv)` declaration still needed for `DependencyAnalyzer(cacheService)`
- **Action**: Remove `cacheService = cacheService,` from all 4 `SourcesViewService(...)` constructor invocations.
- **Verification**: `./gradlew compileIntegrationTestKotlin` succeeds. `./gradlew integrationTest` passes.

---

### Phase 2: Production Logic/Correctness Fixes

#### Finding #2 — `forceRedownload = true` Is Silently Ignored (REGRESSION)
- **Severity**: Critical
- **Rationale**: Lock-internal double-check unconditionally returns existing CAS directory. `REDOWNLOAD_SOURCES` and `FULL_REFRESH` modes are non-functional.
- **Gotcha**: The double-check is necessary for normal case correctness; must be aware of `forceRedownload`.
- **Action**: In `DependencySourcesDownloader.kt:50-54`, check `forceRedownload` inside lock and `deleteRecursively()` before `downloadAndProcess`.
- **Verification**: `./gradlew integrationTest` passes. Integration test verifies content is replaced.

#### Finding #3 — `testRedownloadModeReDownloads` Validates Wrong Behavior
- **Severity**: Critical
- **Rationale**: Cache eviction at SourcesViewService.kt:82-83 evicts for ALL non-CACHED modes, forcing re-analysis. Spec says `redownload=true` should reuse analysis.
- **Action**: Change eviction condition to `mode == SyncMode.REFRESH_ANALYSIS || mode == SyncMode.FULL_REFRESH`. Update test to assert 1 analysis call, 2 download calls.
- **Verification**: `testRedownloadModeReDownloads` and `testModeFullRefresh` both pass.

#### Finding #5 — `check()` Keys by `projectRoot` Only; Ignores Scopes
- **Severity**: Major
- **Rationale**: Multiple scoped views for same project produce non-deterministic `check()` results.
- **Action**: Add `scopes: Set<String> = emptySet()` to `check()` and `check_dependencies` MCP tool. O(1) lookup when scopes provided; deterministic timestamp-based fallback when not.
- **Verification**: Test with compile-scoped and runtime-scoped views; `check()` returns correct view.

#### Finding #13 — Failed Downloads Silently Dropped; Partial Views with Matching `depHash`
- **Severity**: Major
- **Rationale**: `depHash` matches even when downloads failed and dependencies are missing from view.
- **Action**: Add `failedDependencies: List<String>` to `ViewManifest`. Include in manifest; display in `check_dependencies` output.
- **Verification**: Test with 1 of 3 packages failing; manifest shows `failedDependencies`.

#### Finding #15 — `CancellationException` Swallowed by `catch (e: Exception)`
- **Severity**: Major
- **Rationale**: Coroutine cancellation swallowed; partial views committed before cancellation propagates.
- **Action**: Add `catch (e: CancellationException) { throw e }` before the broad catch.
- **Verification**: Test cancelling scope during download; `CancellationException` propagates, view cleaned up.

---

### Phase 3: Spec & Design Doc Updates

| Finding | Doc | Change |
|---------|-----|--------|
| #4 | `view-directory-creation/spec.md` | Fix `casKey` format to `{algorithm}-{hash}` |
| #40 | `design.md` Decision 3 + spec | `sync()` uses `SyncMode`, not booleans |
| #41 | `view-directory-creation/spec.md` | Document scope suffix-matching |
| #42 | `view-directory-creation/spec.md` | Remove "new CAS locations" claim |
| #43 | `view-directory-creation/spec.md` | Add error-handling scenarios |
| #44 | `view-cleanup/spec.md` | Remove "configurable via env" claim |
| #45 | `design.md` Decision 14 | Remove `ViewServiceConfig` reference |
| #46 | `view-directory-creation/spec.md` | Document stale-cache validation |
| #47 | `view-directory-creation/spec.md` | Document `check()` exceptional conditions |
| #48 | `docs/design/view-lifecycle-management.md` | Defer to canonical design.md diagram |

---

### Phase 4: Technical Debt & Improvements

| # | Finding | Action | Effort |
|---|---------|--------|--------|
| #6 | `findProjectManagedFiles` called twice | Return managed files from `analyzeDependencies()` | Medium |
| #7 | `syncMutexes` never evicted | Remove after `mutex.withLock` block | Trivial |
| #8 | `computeDepHash` creates throwaway Analyzer | Cache managed files with TTL | Medium |
| #9 | `runBlocking` in FileLockManager blocks thread | Wrap `downloadSources` in `withContext(Dispatchers.IO)` | Trivial |
| #10 | Unlimited coroutine objects | Chunked execution | Medium |
| #11 | `process.waitFor()` no timeout | 30s timeout + `destroyForcibly()` + `withContext(IO)` | Trivial |
| #12 | Junction target not verified on error exit | Resolve junction target after non-zero exit | Medium |
| #14 | 3 duplicate `SourcesMcpEnvironment` instances | Single `env` in `SourcesMcpServer` | Trivial |
| #16 | No graceful shutdown for MCP server | `server` field + `close()` + shutdown hook | Trivial |
| #18 | Duplicate try/catch in MCP handlers | Accept minor duplication (extraction logic is already DRY) | None |
| #19 | `testCheckReturnsNoViewWhenNoAnalysisCache` tests wrong scenario | Rename + add new test | Trivial |
| #20 | Vendored MCP SDK files in `commonMain/` | Delete entire directory | Trivial |
| #21 | `assertTrue(!x)` instead of `assertFalse(x)` | Replace 5 occurrences | Trivial |
| #22 | Error messages expose absolute paths | Use relative/dependency-level identifiers | Trivial |
| #23 | No MCP protocol-level test (Invariant #13) | Add `SyncCheckToolsIntegrationTest` | Medium |
| #24 | No dedicated test for `computeDepHash()` | Add 3+ test cases to `DependencyAnalyzerTest` | Medium |
| #25 | No parallelism limiter enforcement test (Invariant #14) | Add `AtomicInteger`-based concurrency test | Medium |
| #26 | Manifest deserialization safety guard untested (Invariant #17) | Add malicious manifest tests | Trivial |
| #27 | `NoOpNormalizationService` should be `object` | Change class → object | Trivial |
| #28 | Side-effecting constructor defaults | Add KDoc; resolved by Finding #14 | None |
| #29 | `SyncMode` in service file → `ViewModels.kt` | Move enum | Trivial |
| #30 | `IS_WINDOWS` → `isWindows` | Rename | Trivial |
| #31 | Inconsistent sentinel values | No change (different semantic purposes) | None |
| #32 | `normalize()` non-suspend inside mutex | Change interface to `suspend fun` | Trivial |
| #34 | Dead dev comment in `ViewCleanupService.kt` | Delete line 11 | Trivial |
| #35 | `readTimestamp()` reads full manifest | Extract only `timestamp` via `parseToJsonElement()` | Trivial |
| #36 | `findViewByScanning()` O(n) linear scan | Defer index; accept for now | Defer |
| #37 | Manifest deserialization no size validation | Add `MAX_MANIFEST_SIZE` + `maxDependencies` | Trivial |
| #38 | `check()` reads `viewCache` weakly consistent | Document as known limitation | None |
| #39 | Duplicate `createNpmProject` helper | Extract shared utility | Trivial |

---

### Phase 5: Deferred Items

#### Finding #33 — `SourcesViewService` Is a God Object
- **Status**: RECURRING (Review 2 #8), **DEFER**
- **Rationale**: 14 methods across 8 responsibilities — correctly identified but intentionally deferred for bootstrap phase.
- **Recommendation**: Schedule extraction for dedicated cleanup change post-bootstrap.

---

## Section 5: Proposed Invariants for `AGENTS.md`

| # | Rule | Summary |
|---|------|---------|
| 18 | Subprocess Timeout Rule | `waitFor()` MUST use timed variant with `destroyForcibly()` |
| 19 | No `runBlocking` in Shared Utility Code | Use `suspend` or document IO offload requirement |
| 20 | `async`-Per-Item Requires Bounded Chunking | Launch at most `parallelism` coroutines |
| 21 | Subprocess from Coroutines Requires `withContext(IO)` | Isolate blocking subprocess calls |
| 22 | Keyed-Mutex Registry Requires Cleanup | `ConcurrentHashMap<K, Mutex>` must have removal policy |
| 23 | Dual-Cache Coordination Rule | Paired caches must share eviction API |
| 24 | Error Message Sanitization Rule | No absolute paths in MCP-facing exceptions |
| 25 | Cancellation Propagation Rule | `catch (e: Exception)` must re-throw `CancellationException` |
| 26 | Integration Test Compilation Gate | Constructor changes must update all test call sites same commit |
| 27 | Spec-Signature Parity Rule | Doc must match code within same change |
| 28 | Configuration Transparency Rule | Spec's "configurable via env" needs implementation |
| 29 | Single-Scan Rule for View Creation | `findManagedFiles` called once per `sync()` |
| 30 | Cache Boundedness Rule | User-input-keyed caches must have max size/TTL |
| 31 | Vendor-Free Workspace Rule | No vendored external library copies outside Gradle |
| 32 | Scope Matching Transparency Rule | Deviations from "exact match" must be documented |

---

## Section 6: Execution Summary

| Phase | Items | Approximate Effort |
|-------|-------|--------------------|
| Phase 1: Compilation Blockers | 1 finding (#1) | 5 minutes |
| Phase 2: Logic/Correctness | 5 findings (#2, #3, #5, #13, #15) | 2-3 hours |
| Phase 3: Spec Updates | 10 findings (#4, #40-#48) | 1-2 hours |
| Phase 4: Tech Debt | 26 findings | 4-6 hours |
| Phase 5: Deferred | 1 finding (#33) | Tracked only |
| Invariants: AGENTS.md | 14 new rules | 30 minutes |
| **Total** | **47 actionable** | **~8-12 hours** |

---

*Plan generated by review-resolver. All findings independently verified against source code. Cross-referenced against review-1.md, review-2.md, resolution-1.md, resolution-2.md.*
