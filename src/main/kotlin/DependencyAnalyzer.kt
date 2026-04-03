package dev.rnett.sources.mcp

import org.ossreviewtoolkit.analyzer.Analyzer
import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.model.OrtResult
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class DependencyAnalyzer(
    private val cacheService: DependencyCacheService = DependencyCacheService()
) {
    /**
     * Takes a project root (any ecosystem) and returns an ORT dependency analysis result.
     */
    fun analyzeDependencies(projectRoot: Path): OrtResult {
        require(projectRoot.exists()) { "Project root does not exist: $projectRoot" }
        require(projectRoot.isDirectory()) { "Project root is not a directory: $projectRoot" }

        val analyzerConfig = AnalyzerConfiguration()
        val analyzer = Analyzer(analyzerConfig)

        val managedFiles = analyzer.findManagedFiles(
            absoluteProjectPath = projectRoot.toFile(),
            packageManagers = PackageManagerFactory.ALL.values.filter { it.descriptor.id != "Unmanaged" },
            repositoryConfiguration = RepositoryConfiguration()
        )

        if (managedFiles.managedFiles.isEmpty()) {
            throw IllegalArgumentException("No supported projects found in $projectRoot")
        }

        val cachedResult = cacheService.getCachedResult(managedFiles)
        if (cachedResult != null) {
            return cachedResult
        }

        val ortResult = analyzer.analyze(managedFiles)
        return cacheService.saveResult(managedFiles, ortResult)
    }
}