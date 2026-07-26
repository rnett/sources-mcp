# Objective
Research authoritative latest versions for every requested catalog dependency and the Gradle wrapper, identify compatibility risks, and provide an implementation-ready matrix. This worker is research-only and does not modify build files or source code.

# Global State
- Repository: `C:\Users\rnett\Projects\Personal\sources-mcp`
- Research date: 2026-07-26.
- Stable releases are selected except the Gradle Tooling API, for which the catalog explicitly allows unstable releases.
- Direct repository metadata verified all 29 catalog keys; Gradle services verified the wrapper.

# Research Plan
- [x] Inventory catalog versions, coordinates, plugins, wrapper, repositories, and JVM constraints.
- [x] Verify latest versions using Maven Central, Plugin Portal, Google Maven, and Gradle metadata.
- [x] Verify Gradle wrapper latest stable release and Tooling API latest publication.
- [x] Identify compatibility and coordinated-upgrade risks against local usages.
- [x] Adversarially audit prerelease filtering and evidence gaps.
- [x] Produce an implementation-ready matrix and verification recommendations.

# Known Unknowns
- MCP SDK `0.14.0` is a Maven Central release and Git tag, but GitHub Releases tops out at `0.13.0`; release notes are absent.
- The non-catalog Foojay resolver plugin `1.0.0` in `settings.gradle.kts` was not in the users enumerated scope.

# Under-Researched Areas
- Runtime compatibility remains empirical and must be established by `test` then `build` after catalog edits.
- Tavily was quota-blocked and Gemini was authentication-blocked; direct authoritative metadata and release APIs supplied evidence instead.

# Sources
1. `gradle/libs.versions.toml`
2. `gradle/wrapper/gradle-wrapper.properties`
3. `build.gradle.kts`
4. `settings.gradle.kts`
5. Maven Central metadata under `https://repo.maven.apache.org/maven2/`.
6. Gradle Plugin Portal metadata under `https://plugins.gradle.org/m2/`.
7. Google Maven metadata under `https://dl.google.com/dl/android/maven2/`.
8. Gradle version services: `https://services.gradle.org/versions/current` and `/versions/all`.
9. ORT releases: `https://github.com/oss-review-toolkit/ort/releases`.
10. MCP Kotlin SDK tags/releases: `https://github.com/modelcontextprotocol/kotlin-sdk/tags` and `/releases`.
11. Tavily search/extract attempts: all blocked by account plan usage limit; no evidence returned.
12. Gemini grounded-search attempt: installed client authentication tier unsupported; no evidence returned.

# Report

## Local Dependency Surface

### Research Question(s)
What exact version keys and local constraints affect upgrades?

### Reasoning (Reason-in-Documents)
The catalog maps all 29 requested keys to concrete Maven modules or Gradle plugin IDs. The wrapper and Tooling API are both currently `9.4.1`; the build uses JVM toolchain 21 and daemon JVM 25. ORT core and every package-manager plugin share one `ort` key. MCP and ORT APIs are directly imported in production Kotlin. Compose and AGP have no source imports and exist as update-detection / generated test-fixture versions.

### Results
The requested version surface is complete. The Foojay resolver plugin is outside the version catalog and was not enumerated, so it is noted but not included.

### Dead Ends
None.

### Hanging Threads
None.

## Authoritative Version Matrix

### Research Question(s)
What is the latest allowed release of each dependency as of 2026-07-26?

### Reasoning (Reason-in-Documents)
Maven, Plugin Portal, and Google Maven metadata expose publication version lists and `lastUpdated` fields. Prereleases labeled alpha, beta, RC, milestone, EAP, preview, dev, or snapshot were excluded. Kotlin metadata currently points at `2.4.20-Beta2`, SLF4J at `2.1.0-alpha1`, Compose at `1.12.0-beta02`, and AGP at `9.4.0-alpha06`; the stable versions below are therefore deliberately lower. Gradle Tooling API is the sole exception and selects repository release `9.7.0-rc-1`; the wrapper uses Gradle services current stable `9.6.1`.

### Results
| Key | Current | Target | Evidence |
|---|---:|---:|---|
| kotlin | 2.3.20 | 2.4.10 | [metadata](https://repo.maven.apache.org/maven2/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml) |
| ktor | 3.3.3 | 3.5.1 | [metadata](https://repo.maven.apache.org/maven2/io/ktor/ktor-server-netty/maven-metadata.xml) |
| logback | 1.5.32 | 1.6.0 | [metadata](https://repo.maven.apache.org/maven2/ch/qos/logback/logback-classic/maven-metadata.xml) |
| caffeine | 3.2.3 | 3.2.4 | [metadata](https://repo.maven.apache.org/maven2/com/github/ben-manes/caffeine/caffeine/maven-metadata.xml) |
| commonsIo | 2.21.0 | 2.22.0 | [metadata](https://repo.maven.apache.org/maven2/commons-io/commons-io/maven-metadata.xml) |
| schemaKenerator | 2.6.0 | 2.7.2 | [metadata](https://repo.maven.apache.org/maven2/io/github/smiley4/schema-kenerator-core/maven-metadata.xml) |
| kotlinxSerializationJson | 1.10.0 | 1.11.0 | [metadata](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-serialization-json/maven-metadata.xml) |
| flexmark | 0.64.8 | 0.64.8 | [metadata](https://repo.maven.apache.org/maven2/com/vladsch/flexmark/flexmark-html2md-converter/maven-metadata.xml) |
| coroutines | 1.10.2 | 1.11.0 | [metadata](https://repo.maven.apache.org/maven2/org/jetbrains/kotlinx/kotlinx-coroutines-core/maven-metadata.xml) |
| mcpSdk | 0.7.2 | 0.14.0 | [metadata](https://repo.maven.apache.org/maven2/io/modelcontextprotocol/kotlin-sdk/maven-metadata.xml) |
| gradleToolingApi | 9.4.1 | 9.7.0-rc-1 | [metadata](https://repo.gradle.org/gradle/libs-releases/org/gradle/gradle-tooling-api/maven-metadata.xml) |
| buildconfig | 6.0.9 | 6.0.10 | [metadata](https://plugins.gradle.org/m2/com/github/gmazzo/buildconfig/com.github.gmazzo.buildconfig.gradle.plugin/maven-metadata.xml) |
| vanniktechPublish | 0.36.0 | 0.37.0 | [metadata](https://plugins.gradle.org/m2/com/vanniktech/maven/publish/base/com.vanniktech.maven.publish.base.gradle.plugin/maven-metadata.xml) |
| jsoup | 1.22.1 | 1.22.2 | [metadata](https://repo.maven.apache.org/maven2/org/jsoup/jsoup/maven-metadata.xml) |
| koin | 4.2.0 | 4.2.2 | [metadata](https://repo.maven.apache.org/maven2/io/insert-koin/koin-core-jvm/maven-metadata.xml) |
| mockk | 1.14.9 | 1.14.11 | [metadata](https://repo.maven.apache.org/maven2/io/mockk/mockk/maven-metadata.xml) |
| slf4j | 2.0.17 | 2.0.18 | [metadata](https://repo.maven.apache.org/maven2/org/slf4j/slf4j-api/maven-metadata.xml) |
| guava | 33.5.0-jre | 33.6.0-jre | [metadata](https://repo.maven.apache.org/maven2/com/google/guava/guava/maven-metadata.xml) |
| junit-jupiter | 6.0.3 | 6.1.2 | [metadata](https://repo.maven.apache.org/maven2/org/junit/jupiter/junit-jupiter/maven-metadata.xml) |
| jetbrains-compose | 1.10.3 | 1.11.1 | [metadata](https://repo.maven.apache.org/maven2/org/jetbrains/compose/ui/ui-graphics/maven-metadata.xml) |
| agp9 | 9.1.0 | 9.3.1 | [metadata](https://dl.google.com/dl/android/maven2/com/android/tools/build/gradle/maven-metadata.xml) |
| androidxCompose | 1.10.6 | 1.11.4 | [metadata](https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui/maven-metadata.xml) |
| androidxActivityCompose | 1.13.0 | 1.13.0 | [metadata](https://dl.google.com/dl/android/maven2/androidx/activity/activity-compose/maven-metadata.xml) |
| lucene | 10.4.0 | 10.5.0 | [metadata](https://repo.maven.apache.org/maven2/org/apache/lucene/lucene-core/maven-metadata.xml) |
| shadow | 9.4.1 | 9.6.1 | [metadata](https://plugins.gradle.org/m2/com/gradleup/shadow/com.gradleup.shadow.gradle.plugin/maven-metadata.xml) |
| treesitter | 0.26.6 | 0.26.6 | [metadata](https://repo.maven.apache.org/maven2/io/github/bonede/tree-sitter/maven-metadata.xml) |
| treesitter-java | 0.23.5 | 0.23.5 | [metadata](https://repo.maven.apache.org/maven2/io/github/bonede/tree-sitter-java/maven-metadata.xml) |
| treesitter-kotlin | 0.3.8.1 | 0.3.8.1 | [metadata](https://repo.maven.apache.org/maven2/io/github/bonede/tree-sitter-kotlin/maven-metadata.xml) |
| ort | 83.0.1 | 91.1.0 | [metadata](https://repo.maven.apache.org/maven2/org/ossreviewtoolkit/model/maven-metadata.xml) |
| Gradle wrapper | 9.4.1 | 9.6.1 | [Gradle current](https://services.gradle.org/versions/current) |

### Dead Ends
Tavily and Gemini could not return evidence; direct authoritative endpoints succeeded.

### Hanging Threads
MCP `0.14.0` lacks a GitHub Release page, but Maven Central marks it as release and GitHub has tag `0.14.0` at commit `fc7916f`.

## Compatibility and Upgrade Risk

### Research Question(s)
Which upgrades require coordinated changes or may fail without source modifications?

### Reasoning (Reason-in-Documents)
Local search confirms MCP SDK APIs in `SourcesMcpServer.kt` and `SyncCheckTools.kt`, and ORT APIs across analyzer, downloader, cache, and model paths. ORT `91.0.0` release notes explicitly list breaking changes; `91.1.0` is a stable follow-up. Compose and AGP have no source imports. MCP `0.14.0` aggregates core/client/server `0.14.0` and is published against Kotlin stdlib `2.3.21`, so Maven publication alone does not prove source compatibility with this project.

### Results
- Highest risk: MCP SDK `0.7.2 -> 0.14.0` and ORT `83.0.1 -> 91.1.0` cross many release boundaries and touch directly imported APIs.
- Upgrade Kotlin `2.4.10`, serialization `1.11.0`, and coroutines `1.11.0` in one verification pass because the build uses Kotlin scripting/compiler artifacts and experimental flags.
- Keep wrapper on stable `9.6.1`; only Tooling API has the explicit unstable exception for `9.7.0-rc-1`.
- Build plugins may affect configuration before compilation; failures from Shadow or Vanniktech must be isolated from source incompatibilities.
- JUnit 6 and Logback 1.6 modern Java requirements are satisfied by JVM toolchain 21.

### Dead Ends
Metadata cannot prove runtime or source compatibility.

### Hanging Threads
If tests/build fail, the issue forbids source changes; report the incompatible latest version rather than silently holding it back.

## Verification Strategy

### Research Question(s)
What sequence proves the update is viable?

### Reasoning (Reason-in-Documents)
Project guidance requires `test` before `build`; `build` includes wider checks and integration tests through `check`.

### Results
An implementation agent should edit only `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties`, run `test`, then `build`, and stage those intended files per local policy. No source changes are permitted.

### Dead Ends
No builds were run because this researcher did not alter dependency files.

### Hanging Threads
Actual compilation and test results remain pending implementation.
