# Source Normalization Strategy (Agent-Optimized)

This document details the normalization strategies for dependency sources across various ecosystems.

**Core Philosophy:** These source views are specifically designed for **manual searching and reading by AI agents and humans**. Therefore, the primary goal is **High Signal, Low Noise**. We must aggressively filter out duplicate
information, compiled artifacts, minified code, build system configurations, and extraneous repository metadata to conserve context windows and ensure search tools (like `rg` or `tilth`) return relevant, readable results focused strictly on
**actual source code implementation**.

## 1. Global Exclusion Rules

Regardless of the ecosystem or provenance (Artifact vs. VCS), the following must **always** be excluded from the `v1/` view:

* **Version Control**: `.git/`, `.svn/`, `.hg/`
* **IDE/Editor Configs**: `.idea/`, `.vscode/`, `*.iml`, `*.ipr`
* **CI/CD & Tooling**: `.github/`, `.gitlab-ci.yml`, `.circleci/`, `.husky/`
* **Build Configurations & Wrappers**: `gradle/`, `.mvn/`, `gradlew`, `gradlew.bat`, `mvnw`, `mvnw.cmd`, `.bsp/`, `.kotlin/`
* **Compiled Bytecode/Objects**: `*.class`, `*.o`, `*.obj`, `*.pyc`, `*.pyo`, `*.pyd`, `*.so`, `*.dll`, `*.dylib`
* **Minified & Obfuscated Code**: `*.min.js`, `*.min.css`
* **Debugging Artifacts**: `*.map` (Sourcemaps)
* **Media & Binaries**: Images (`.png`, `.jpg`, `.gif`, `.webp`), fonts (`.ttf`, `.woff`, `.woff2`), audio, and generic binary blobs.
* **Local Environments**: `node_modules/`, `venv/`, `.env/`, `.tox/`, `vendor/` (unless vendor is strictly required for analysis, but usually it's noise).
* **Test Coverage & Reports**: `coverage/`, `htmlcov/`, `test-results/`, `.pytest_cache/`, `.eslintcache`

## 2. The VCS "Extraneous File Blacklist" Rule

Git repositories often contain a massive amount of extraneous files that are not part of the core package logic. Rather than trying to predict every possible valid source extension (like `.graphql`, `.prisma`, `.sql`, `.proto`,
framework-specific templates), we employ a robust **blacklist approach**.

After monorepo isolation and applying global exclusions, we aggressively prune the following directories and files commonly found in repositories but rarely needed for dependency API exploration:

* **Documentation & Websites**: `docs/`, `site/`, `website/`, `gh-pages/`, `doc/`, `man/`
* **Examples & Demos**: `examples/`, `demo/`, `samples/`, `playground/`, `tutorial/`
* **Test Fixtures & Data**: `test-data/`, `__snapshots__/`, `fixtures/`, `mocks/`, `__pycache__/` (We keep actual test source files, but drop heavy data directories).
* **Internal Scripts & Tooling**: `scripts/`, `tools/`, `maintenance/`, `tasks/`, `bin/` (Only exclude `bin/` for ecosystems like Java/Python where it's not the primary entry point).
* **Design Assets**: `design/`, `assets/`, `images/`, `ui/`, `figma/`
* **Lockfiles & Massive Manifests**: `package-lock.json`, `yarn.lock`, `pnpm-lock.yaml`, `Cargo.lock`, `Gemfile.lock`, `poetry.lock`. (We keep the base manifests like `package.json` or `Cargo.toml`, but lockfiles are giant walls of noise
  that agents do not need).

## 3. The "Original Source Preference" Rule

When a package contains both the original authored source code and its transpiled/compiled output, we must **only keep the original source**. Searching both pollutes results and confuses agents.

* If `.ts` (TypeScript) files exist, exclude transpiled `.js` and `.d.ts` files in output directories (e.g., `dist/`, `lib/`).
* If `src/` exists and appears to be the authoring directory, aggressively filter common output directories (`build/`, `dist/`, `out/`, `lib/`).
* *Fallback*: If the artifact *only* contains transpiled `.js` (because the author did not publish the `src/` directory), we must keep it, but we still apply the minified/sourcemap exclusions.

---

## 4. Ecosystem-Specific Normalization

### A. NPM (JavaScript / TypeScript)

NPM packages are notorious for containing both source and transpiled outputs, and the layout differs heavily between published tarballs and git repositories.

* **Provenance: NPM Tarball (`.tgz`)**
    * **Action**: Strip the mandatory top-level `package/` directory.
    * **Filtering**: Check for the presence of `src/` or `tsconfig.json`. If present, aggressively exclude `dist/`, `lib/`, `build/`, `out/`.
    * **Keep**: Everything else after applying Global Exclusions.
* **Provenance: Git Repository**
    * **Action**: Apply the **VCS Extraneous File Blacklist**.
    * **Filtering**: Exclude `dist/`, `build/`, `out/`.
    * **Monorepo Handling**: If the package is part of a monorepo (e.g., Lerna/Yarn workspaces), we MUST use the `vcsProcessed.path` from ORT to isolate the specific package subdirectory. Do not symlink the entire monorepo root.

### B. Python (Pip / PyPI)

Python packages are distributed as Source Distributions (sdists) or Wheels.

* **Provenance: Source Distribution (`.tar.gz` / `.zip`)**
    * **Action**: Strip the top-level `<package_name>-<version>/` directory.
    * **Filtering**: Exclude `*.egg-info/`, `build/`, `dist/`.
    * **Layout**: Modern packages use a `src/<package_name>/` layout; others put `<package_name>/` at the root. Keep both structures, as they are idiomatic.
* **Provenance: Wheel (`.whl`)**
    * *Note*: Wheels are pre-compiled. They contain the *installed* layout.
    * **Filtering**: Exclude all `*.pyc` files. Exclude the `<package_name>.dist-info/` directory (metadata), keeping only the actual `.py` modules.
* **Provenance: Git Repository**
    * **Action**: Apply the **VCS Extraneous File Blacklist**.
    * **Filtering**: Exclude `build/`, `dist/`.

### C. JVM (Maven / Gradle)

* **Provenance: Sources JAR (`-sources.jar`)**
    * *Note*: This is the ideal scenario. Sources JARs generally *only* contain the raw source files in their correct package directories.
    * **Action**: Extract directly to `v1/`. The structure will be `v1/com/example/MyClass.java`. This is perfectly normalized for searching.
* **Provenance: Git Repository**
    * *Note*: A Git repo for a JVM project contains build scripts, resources, and multiple source sets.
    * **Action**: Extract the specific module using `vcsProcessed.path`. Apply the **VCS Extraneous File Blacklist**.
    * **Normalization**: Locate standard source roots like `src/main/java`, `src/main/kotlin`, `src/main/scala`, `src/main/clojure`, and `src/commonMain/kotlin` (for KMP).
    * **Flattening Strategy**: To match the clean layout of a Sources JAR, we should hoist the contents of these primary source sets to the root of `v1/`.
        * `src/main/kotlin/com/foo/Bar.kt` -> `v1/com/foo/Bar.kt`
    * **KMP Suffixing & De-duplication**: For platform-specific source sets in KMP (e.g., `src/jvmMain/kotlin`), apply the platform suffix during hoisting:
        * `src/jvmMain/kotlin/com/foo/Actuals.kt` -> `v1/com/foo/Actuals.jvm.kt`
        * **Crucial**: If a file in a platform-specific source set is identical to a file in `commonMain`, it MUST be discarded to prevent duplicates in the final view. Only `actual` implementations or target-specific extensions should
          remain.
    * **KMP Artifact Deduplication (Common vs Target Artifacts)**: When both a common artifact (e.g., `kotlinx-coroutines-core`) and a platform-specific artifact (e.g., `kotlinx-coroutines-core-jvm`) are resolved as dependencies:
        * **Common Artifact**: Contains the full source including `commonMain` and all shared source sets. Keep all sources as-is.
        * **Target Artifact**: Contains ONLY the platform-specific sources (e.g., `jvmMain`). The session view MUST link the target artifact to a "target" source directory containing ONLY the JVM-specific sources that are NOT already
          present in the common artifact.
        * **Content-Based Deduplication**: Compare file contents (not just names) between common and target artifacts. If a file in the target artifact is byte-for-byte identical to a file in the common artifact, it MUST be excluded from
          the target view.
        * **Result**: No source file SHALL appear twice in the search results or session view. The common artifact provides the base implementation, while the target artifact provides only the platform-specific `actual` implementations or
          extensions.
    * **KMP Metadata Configuration Filtering**: Gradle configurations ending with `DependenciesMetadata` (e.g., `commonMainDependenciesMetadata`, `jvmMainDependenciesMetadata`) SHALL NOT be used for resolving the primary dependency graph
      for sources. These configurations contain redundant metadata entries that would cause duplicate processing.
    * **Filtering**: Exclude `build/`, `out/`.

### D. Cargo (Rust)

* **Provenance: Crate Tarball (`.crate`)**
    * **Action**: Strip the top-level directory.
    * **Filtering**: Cargo crates are usually clean.
* **Provenance: Git Repository**
    * **Action**: Apply the **VCS Extraneous File Blacklist**.
    * **Filtering**: Exclude `target/` (build artifacts).

### E. Go

* **Provenance: Module Zip**
    * **Action**: Strip the top-level `<module>@<version>/` directory.
* **Provenance: Git Repository**
    * **Action**: Apply the **VCS Extraneous File Blacklist**.
    * **Filtering**: Exclude `bin/`, `obj/`.

---

## 5. Implementation Details (The Normalization Pipeline)

To achieve this, `DependencySourcesDownloader` (or a dedicated `NormalizationService`) must execute a pipeline *after* ORT downloads the raw source, but *before* the CAS lock is released and the view is generated:

1. **Extraction & Base Flattening**: Extract the archive. If the archive contains exactly one top-level directory (common for npm, pip sdists, github zips), move its contents up one level.
2. **Monorepo Isolation (VCS Only)**: If the provenance is VCS and `vcsProcessed.path` is set, discard everything outside of that specific subdirectory, making that subdirectory the new root.
3. **Global Pruning**: Apply a recursive deletion for all patterns defined in the "Global Exclusion Rules" (e.g., `.git/`, `node_modules/`, `gradlew`, `*.min.js`).
4. **Ecosystem Detection**: Inspect the root for manifest files to determine the ecosystem.
5. **VCS Blacklisting (VCS Only)**: If the provenance is VCS, apply the "VCS Extraneous File Blacklist", deleting directories like `docs/`, `examples/`, `scripts/`, and all lockfiles.
6. **Targeted Pruning**: Apply the ecosystem-specific "Original Source Preference" rules (e.g., deleting `dist/` if `.ts` files are found).
7. **Structural Reorganization (JVM/KMP Only)**: If a JVM project is detected from VCS, apply the directory hoisting and KMP suffixing logic to create a flat, package-based layout matching a Sources JAR.
8. **KMP Cross-Artifact Deduplication**: When multiple KMP artifacts are resolved (common + platform-specific), perform content-based deduplication across artifacts. Compare file hashes between common and target artifacts, excluding
   identical files from target views.
9. **Finalization**: Commit the resulting directory to the CAS as the normalized `v1/` representation.