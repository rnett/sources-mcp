package dev.rnett.sources.mcp

import kotlinx.serialization.Serializable
import java.nio.file.Path
import java.time.Duration

@Serializable
data class ViewManifest(
    val sessionId: String,
    val timestamp: String,
    val projectRoot: String,
    val scopes: Set<String>,
    val depHash: String,
    val dependencies: List<ViewDependency>,
    val failedDependencies: List<String> = emptyList(),
)

@Serializable
data class ViewDependency(
    val id: String,
    val casKey: String,
    val ecosystem: String,
    val namespace: String,
    val name: String,
    val version: String,
)

data class SessionView(
    val sessionId: String,
    val baseDir: Path,
    val sourcesDir: Path,
    val manifest: ViewManifest,
)

data class ViewCacheKey(
    val normalizedProjectRoot: Path,
    val scopes: Set<String>,
) {

    companion object {
        fun create(projectRoot: Path, scopes: Set<String>): ViewCacheKey {
            val normalized = runCatching { projectRoot.toAbsolutePath().normalize().toRealPath() }
                .getOrDefault(projectRoot.toAbsolutePath().normalize())
            return ViewCacheKey(normalized, scopes)
        }
    }
}

data class StalenessCheck(
    val viewExists: Boolean,
    val fresh: Boolean,
    val age: Duration?,
    val depHash: String?,
    val currentDepHash: String,
    val dependencyCount: Int?,
    val error: String? = null,
    val failedDependencies: List<String> = emptyList(),
)
