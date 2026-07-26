package dev.rnett.sources.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewModelsTest {

    private val json = Json

    @Test
    fun testViewManifestSerializationRoundTrip() {
        val dependencies = listOf(
            ViewDependency(
                id = "org.jetbrains.kotlin:kotlin-stdlib:1.9.0",
                casKey = "abc123def456",
                ecosystem = "Maven",
                namespace = "org.jetbrains.kotlin",
                name = "kotlin-stdlib",
                version = "1.9.0",
            ),
            ViewDependency(
                id = "com.google.guava:guava:32.1.3-jre",
                casKey = "xyz789ghi012",
                ecosystem = "Maven",
                namespace = "com.google.guava",
                name = "guava",
                version = "32.1.3-jre",
            ),
        )

        val manifest = ViewManifest(
            sessionId = "test-session-001",
            timestamp = "2026-05-07T10:30:00Z",
            projectRoot = "/home/user/projects/test-project",
            scopes = setOf("compile", "runtime"),
            depHash = "sha256:a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2",
            dependencies = dependencies,
        )

        val serialized = json.encodeToString(manifest)
        val deserialized = json.decodeFromString<ViewManifest>(serialized)

        assertEquals(manifest.sessionId, deserialized.sessionId)
        assertEquals(manifest.timestamp, deserialized.timestamp)
        assertEquals(manifest.projectRoot, deserialized.projectRoot)
        assertEquals(manifest.scopes, deserialized.scopes)
        assertEquals(manifest.depHash, deserialized.depHash)
        assertEquals(manifest.dependencies, deserialized.dependencies)
    }

    // --- ViewCacheKey tests ---

    @Test
    fun testViewCacheKeyCreateNormalizesPaths() {
        val relPath = Path.of("foo/bar")
        val key = ViewCacheKey.create(relPath, emptySet())
        assertTrue(key.normalizedProjectRoot.isAbsolute)
    }

    @Test
    fun testViewCacheKeyEquality() {
        val root = Path.of(System.getProperty("user.dir"))
        val key1 = ViewCacheKey.create(root, setOf("compile"))
        val key2 = ViewCacheKey.create(root, setOf("compile"))
        assertEquals(key1, key2)
    }

    @Test
    fun testViewCacheKeyDifferentScopesNotEqual() {
        val root = Path.of(System.getProperty("user.dir"))
        val key1 = ViewCacheKey.create(root, setOf("compile"))
        val key2 = ViewCacheKey.create(root, setOf("test"))
        assertTrue(key1 != key2)
    }

    @Test
    fun testViewCacheKeyHashCodeConsistentWithEquals() {
        val root = Path.of(System.getProperty("user.dir"))
        val key1 = ViewCacheKey.create(root, setOf("compile", "runtime"))
        val key2 = ViewCacheKey.create(root, setOf("compile", "runtime"))
        assertEquals(key1.hashCode(), key2.hashCode())
    }
}
