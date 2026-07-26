package dev.rnett.sources.mcp

import java.nio.file.Path

/**
 * Post-processes a dependency sources directory after all source artifacts have been
 * downloaded and linked. Implementations may flatten nested archive structures
 * (e.g., removing redundant `package/` subdirectories from NPM/GitHub tarballs),
 * fix file encodings, or apply project-specific layout transformations.
 *
 * @param sourcesDir the directory containing symlinks/junctions to CAS entries
 * @return the normalized directory path (may be [sourcesDir] if unchanged,
 *         or a new directory path if the structure was rewritten)
 */
interface NormalizationService {
    suspend fun normalize(sourcesDir: Path): Path
}

object NoOpNormalizationService : NormalizationService {
    override suspend fun normalize(sourcesDir: Path): Path = sourcesDir
}
