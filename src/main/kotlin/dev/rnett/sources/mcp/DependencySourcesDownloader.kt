package dev.rnett.sources.mcp

import org.ossreviewtoolkit.downloader.Downloader
import org.ossreviewtoolkit.model.Hash
import org.ossreviewtoolkit.model.Package
import org.ossreviewtoolkit.model.Provenance
import org.ossreviewtoolkit.model.config.DownloaderConfiguration
import org.ossreviewtoolkit.model.utils.toPurl
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists

/**
 * Service to download and extract dependency sources using ORT.
 * Implements a Content-Addressable Storage (CAS) with advisory locking to ensure
 * robust concurrent access.
 */
class DependencySourcesDownloader(
    private val env: SourcesMcpEnvironment = SourcesMcpEnvironment.fromEnv()
) {
    private val logger = LoggerFactory.getLogger(DependencySourcesDownloader::class.java)

    // ORT Downloader configuration can be customized later if needed.
    private val downloader = Downloader(DownloaderConfiguration())

    /**
     * Downloads and extracts sources for the given [pkg] into the CAS.
     * Returns the [Path] to the extracted sources in the CAS.
     *
     * The CAS key is based on the artifact hash available on the [pkg].
     */
    fun downloadSources(pkg: Package): Path {
        val casKey = getCasKey(pkg)
        val targetDir = env.casDir.resolve(casKey)

        // 1. Fast path: check if already in CAS
        if (targetDir.exists()) {
            return targetDir
        }

        // 2. Acquisition of the advisory lock based on the CAS key
        val lockFile = env.locksDir.resolve("$casKey.lock")
        return FileLockManager.withLock(lockFile) {
            // 3. Double-check after acquiring the lock
            if (targetDir.exists()) {
                return@withLock targetDir
            }

            downloadAndProcess(pkg, targetDir, casKey)
        }
    }

    private fun downloadAndProcess(pkg: Package, targetDir: Path, casKey: String): Path {
        val tempDir = createTempDirectory(env.casDir, "${casKey}_tmp_${UUID.randomUUID()}")
        try {
            // 4. Download
            performDownload(pkg, tempDir)

            // 5. Post-process
            postProcess(tempDir)

            // 6. Atomically move to the final CAS location
            FileUtils.atomicMoveIfAbsent(tempDir, targetDir)
            return targetDir
        } catch (e: Exception) {
            logger.error("Failed to download sources for ${pkg.id.toCoordinates()}", e)
            if (tempDir.exists()) {
                tempDir.toFile().deleteRecursively()
            }
            throw e
        }
    }

    private fun performDownload(pkg: Package, tempDir: Path): Provenance {
        logger.info("Downloading sources for ${pkg.id.toCoordinates()} to $tempDir")
        return downloader.download(pkg, tempDir.toFile())
    }

    private fun getCasKey(pkg: Package): String {
        val hash = when {
            pkg.sourceArtifact.hash != Hash.NONE -> pkg.sourceArtifact.hash
            pkg.binaryArtifact.hash != Hash.NONE -> pkg.binaryArtifact.hash
            else -> null
        }

        if (hash != null) {
            // Use the algorithm as a prefix to ensure uniqueness and follow instructions
            return "${hash.algorithm}-${hash.value}"
        }

        // Fallback for VCS: Use type, sanitized URL, and revision.
        if (pkg.vcsProcessed.revision.isNotBlank()) {
            val sanitizedUrl = pkg.vcsProcessed.url.replace(Regex("[^a-zA-Z0-9]"), "_")
            return "vcs-${pkg.vcsProcessed.type}-${sanitizedUrl}-${pkg.vcsProcessed.revision}"
        }

        // Absolute fallback: sanitized PURL (not a hash of the PURL)
        return "purl-" + pkg.id.toPurl().replace(Regex("[^a-zA-Z0-9]"), "_")
    }

    private fun postProcess(dir: Path) {
        // Flatten the directory structure if it contains only one subdirectory (common for NPM/GitHub tarballs)
        flattenSubdirectory(dir)
    }

    private fun flattenSubdirectory(dir: Path) {
        val files = dir.toFile().listFiles() ?: return
        if (files.size == 1 && files[0].isDirectory) {
            val subDir = files[0]
            logger.info("Flattening subdirectory: ${subDir.name}")
            val subFiles = subDir.listFiles() ?: return
            for (file in subFiles) {
                val target = dir.resolve(file.name)
                java.nio.file.Files.move(file.toPath(), target)
            }
            subDir.delete()
        }
    }
}
