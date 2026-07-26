package dev.rnett.sources.mcp

import org.ossreviewtoolkit.analyzer.Analyzer
import org.ossreviewtoolkit.model.AnalyzerResult
import org.ossreviewtoolkit.model.readValue
import org.ossreviewtoolkit.model.writeValue
import java.security.MessageDigest
import kotlin.io.path.exists

class DependencyCacheService(
    private val env: SourcesMcpEnvironment = SourcesMcpEnvironment.fromEnv()
) {
    private val cacheDir get() = env.analyzerCacheDir

    fun getCachedResult(managedFiles: Analyzer.ManagedFileInfo): AnalyzerResult? {
        val cacheKey = calculateCacheKey(managedFiles)
        val cachedFile = cacheDir.resolve("$cacheKey.yml")
        return FileLockManager.withLock(lockFileFor(cacheKey)) {
            if (cachedFile.exists()) cachedFile.toFile().readValue<AnalyzerResult>() else null
        }
    }

    fun saveResult(managedFiles: Analyzer.ManagedFileInfo, ortResult: AnalyzerResult): AnalyzerResult {
        val cacheKey = calculateCacheKey(managedFiles)
        val cachedFile = cacheDir.resolve("$cacheKey.yml")
        return FileLockManager.withLock(lockFileFor(cacheKey)) {
            cachedFile.toFile().writeValue(ortResult)
            ortResult
        }
    }

    private fun lockFileFor(cacheKey: String) = env.locksDir.resolve("analyzer-$cacheKey.lock")

    private fun calculateCacheKey(managedFiles: Analyzer.ManagedFileInfo): String =
        Companion.calculateCacheKey(managedFiles)

    companion object {
        fun calculateCacheKey(managedFiles: Analyzer.ManagedFileInfo): String {
            val digest = MessageDigest.getInstance("SHA-256")
            managedFiles.managedFiles.entries
                .sortedBy { it.key.descriptor.id }
                .forEach { (manager, files) ->
                    digest.update(manager.descriptor.id.toByteArray())
                    files.sortedBy { it.absolutePath }.forEach { file ->
                        if (file.isFile) {
                            digest.update(file.absolutePath.toByteArray())
                            digest.update(file.readBytes())
                        }
                    }
                }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
