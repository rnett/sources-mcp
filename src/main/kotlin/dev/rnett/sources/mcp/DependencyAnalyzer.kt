package dev.rnett.sources.mcp

import org.ossreviewtoolkit.analyzer.Analyzer
import org.ossreviewtoolkit.analyzer.PackageManagerFactory
import org.ossreviewtoolkit.model.AnalyzerResult
import org.ossreviewtoolkit.model.config.AnalyzerConfiguration
import org.ossreviewtoolkit.model.config.RepositoryConfiguration
import org.ossreviewtoolkit.model.utils.convertToDependencyGraph
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class DependencyAnalyzer(
    private val cacheService: DependencyCacheService = DependencyCacheService()
) {
    /**
     * Takes a project root (any ecosystem) and returns an ORT dependency analysis result.
     */
    fun analyzeDependencies(
        projectRoot: Path,
        packageManagerOptions: Map<String, Map<String, String>> = emptyMap(),
        force: Boolean = false
    ): AnalyzerResult {
        require(projectRoot.exists()) { "Project root does not exist: $projectRoot" }
        require(projectRoot.isDirectory()) { "Project root is not a directory: $projectRoot" }

        var analyzerConfig = AnalyzerConfiguration()
        packageManagerOptions.forEach { (name, options) ->
            options.forEach { (key, value) ->
                analyzerConfig = analyzerConfig.withPackageManagerOption(name, key, value)
            }
        }

        val managedFiles = findProjectManagedFiles(projectRoot)

        if (managedFiles.managedFiles.isEmpty()) {
            throw IllegalArgumentException("No supported projects found in $projectRoot")
        }

        if (!force) {
            val cachedResult = cacheService.getCachedResult(managedFiles)
            if (cachedResult != null) {
                return cachedResult
            }
        }

        val analyzer = Analyzer(analyzerConfig)
        val ortResult = analyzer.analyze(managedFiles).analyzer!!.result.convertToDependencyGraph()

        val issues = ortResult?.issues.orEmpty()
        if (issues.isNotEmpty()) {
            val errorDetails = issues.entries.joinToString("\n") { (id, projectIssues) ->
                "Project ${id.toCoordinates()}:\n  ${projectIssues.joinToString("\n  ") { it.message }}"
            }
            throw RuntimeException("Dependency analysis failed with the following issues:\n$errorDetails")
        }

        return cacheService.saveResult(managedFiles, ortResult)
    }

    fun computeDepHash(projectRoot: Path): String {
        val managedFiles = findProjectManagedFiles(projectRoot)
        return DependencyCacheService.calculateCacheKey(managedFiles)
    }

    private fun findProjectManagedFiles(projectRoot: Path) = Analyzer(AnalyzerConfiguration()).findManagedFiles(
        absoluteProjectPath = projectRoot.toFile(), // ORT API requires java.io.File
        packageManagers = PackageManagerFactory.ALL.values.filter { it.descriptor.id != "Unmanaged" },
        repositoryConfiguration = RepositoryConfiguration()
    )
}