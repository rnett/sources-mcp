package dev.rnett.sources.mcp

import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories

data class SourcesMcpEnvironment(val workingDir: Path) {
    val cacheDir: Path = workingDir.resolve("cache")
    val analyzerCacheDir: Path = cacheDir.resolve("analyzer")
    val casDir: Path = cacheDir.resolve("cas")
    val locksDir: Path = cacheDir.resolve("locks")
    val viewsDir: Path = cacheDir.resolve("views")
    // Intentional: constructor creates directories via createDirectories() for bootstrapping simplicity.
    init {
        analyzerCacheDir.createDirectories()
        casDir.createDirectories()
        locksDir.createDirectories()
        viewsDir.createDirectories()
    }

    companion object {
        fun fromEnv(): SourcesMcpEnvironment {
            val workingDir = System.getenv("SOURCES_MCP_WORKING_DIR")
                ?: "${System.getProperty("user.home")}/.mcps/rnett-sources-mcp"
            return SourcesMcpEnvironment(Path(workingDir).absolute().normalize())
        }
    }
}
