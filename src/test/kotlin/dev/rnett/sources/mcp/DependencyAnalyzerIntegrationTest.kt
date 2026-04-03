package dev.rnett.sources.mcp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DependencyAnalyzerIntegrationTest {

    @TempDir
    lateinit var tempDir: Path

    @TempDir
    lateinit var testHomeDir: Path

    private val testEnv by lazy { SourcesMcpEnvironment(testHomeDir) }
    private val testCacheService by lazy { DependencyCacheService(testEnv) }
    private val analyzer by lazy { DependencyAnalyzer(testCacheService) }

    @Test
    fun testGradleIntegration() {
        val project = testProject(tempDir.resolve("gradle-project")) {
            gradle {
                buildScript(
                    """
                    plugins {
                        id 'java'
                    }
                    repositories {
                        mavenCentral()
                    }
                    dependencies {
                        implementation 'org.slf4j:slf4j-api:1.7.32'
                    }
                """
                )
            }
        }

        val result = analyzer.analyzeDependencies(project.root)
        assertNotNull(result.analyzer?.result)
        val projects = result.getProjects()
        assertTrue(projects.any { it.id.type == "Gradle" }, "Should find Gradle project")
    }

    @Test
    fun testNpmIntegration() {
        val project = testProject(tempDir.resolve("npm-project")) {
            npm {
                name("test-npm")
                addDependency("lodash", "4.17.21")
            }
        }

        val result = analyzer.analyzeDependencies(project.root)
        assertNotNull(result.analyzer?.result)
        val projects = result.getProjects()
        assertTrue(projects.any { it.id.type == "NPM" }, "Should find NPM project")
    }

    @Test
    fun testMavenIntegration() {
        val project = testProject(tempDir.resolve("maven-project")) {
            maven {
                pom(
                    """
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>dev.rnett</groupId>
                        <artifactId>test-maven</artifactId>
                        <version>1.0.0</version>
                        <dependencies>
                            <dependency>
                                <groupId>org.apache.commons</groupId>
                                <artifactId>commons-lang3</artifactId>
                                <version>3.12.0</version>
                            </dependency>
                        </dependencies>
                    </project>
                """
                )
            }
        }

        val result = analyzer.analyzeDependencies(project.root)
        assertNotNull(result.analyzer?.result)
        val projects = result.getProjects()
        assertTrue(projects.any { it.id.type == "Maven" }, "Should find Maven project")
    }

    @Test
    fun testKotlinJvmIntegration() {
        val project = testProject(tempDir.resolve("kotlin-jvm-project")) {
            kotlinJvm {
                addDependency("org.slf4j:slf4j-api:1.7.32")
            }
        }

        val result = analyzer.analyzeDependencies(project.root)
        assertNotNull(result.analyzer?.result)
        val projects = result.getProjects()
        assertTrue(projects.any { it.id.type == "Gradle" }, "Should find Gradle project for Kotlin JVM")
    }

    @Test
    fun testKotlinMultiplatformIntegration() {
        val project = testProject(tempDir.resolve("kmp-project")) {
            kotlinMultiplatform {
                jvm()
                js()
                wasmWasi()
                addCommonDependency("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
            }
        }

        val result = analyzer.analyzeDependencies(project.root)
        assertNotNull(result.analyzer?.result)
        val projects = result.getProjects()
        // ORT might represent KMP as multiple projects or one project with multiple scopes depending on the manager
        assertTrue(projects.isNotEmpty(), "Should find projects for KMP")
        assertTrue(projects.any { it.id.type == "Gradle" }, "Should find Gradle project for KMP")
    }

    @Test
    fun testMultiModuleGradleIntegration() {
        val project = testProject(tempDir.resolve("multi-module-gradle")) {
            gradle {
                settings("rootProject.name = 'multi-module-root'")
                addModule(
                    "app", """
                    plugins { id 'java' }
                    dependencies { implementation 'org.slf4j:slf4j-api:1.7.32' }
                """
                )
                addModule(
                    "lib", """
                    plugins { id 'java' }
                    dependencies { implementation 'com.google.guava:guava:31.0.1-jre' }
                """
                )
            }
        }

        val result = analyzer.analyzeDependencies(project.root)
        assertNotNull(result.analyzer?.result)
        val projects = result.getProjects()
        assertTrue(projects.size >= 2, "Should find at least 2 projects in multi-module Gradle")
    }

    @Test
    fun testNpmWorkspacesIntegration() {
        val project = testProject(tempDir.resolve("npm-workspaces")) {
            file(
                "package.json", """
                {
                  "name": "root",
                  "private": true,
                  "workspaces": ["packages/*"]
                }
            """
            )
            file(
                "packages/pkg1/package.json", """
                {
                  "name": "pkg1",
                  "version": "1.0.0",
                  "dependencies": { "lodash": "4.17.21" }
                }
            """
            )
            file("packages/pkg1/package-lock.json", "{}")
            file("package-lock.json", "{}")
        }

        val result = analyzer.analyzeDependencies(project.root)
        assertNotNull(result.analyzer?.result)
        val projects = result.getProjects()
        assertTrue(projects.any { it.id.name == "pkg1" }, "Should find pkg1 in NPM workspaces")
    }

    @Test
    fun testMavenMultiModuleIntegration() {
        val project = testProject(tempDir.resolve("maven-multi-module")) {
            maven {
                pom(
                    """
                    <project>
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>dev.rnett</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                        <packaging>pom</packaging>
                        <modules>
                            <module>child</module>
                        </modules>
                    </project>
                """
                )
            }
            file(
                "child/pom.xml", """
                <project>
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>dev.rnett</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>org.slf4j</groupId>
                            <artifactId>slf4j-api</artifactId>
                            <version>1.7.32</version>
                        </dependency>
                    </dependencies>
                </project>
            """
            )
        }

        val result = analyzer.analyzeDependencies(project.root)
        assertNotNull(result.analyzer?.result)
        val projects = result.getProjects()
        assertTrue(projects.any { it.id.name == "child" }, "Should find child project in Maven multi-module")
    }

    @Test
    fun testGoModIntegration() {
        val project = testProject(tempDir.resolve("go-project")) {
            go {
                moduleName("github.com/rnett/test")
                require("github.com/pkg/errors v0.9.1")
            }
        }

        val result = analyzer.analyzeDependencies(project.root)
        assertNotNull(result.analyzer?.result)
        val projects = result.getProjects()
        assertTrue(projects.any { it.id.type == "GoMod" }, "Should find GoMod project")
    }

    @Test
    fun testCargoIntegration() {
        val project = testProject(tempDir.resolve("cargo-project")) {
            cargo {
                name("test-cargo")
                addDependency("serde", "1.0.152")
            }
        }

        val result = analyzer.analyzeDependencies(project.root)
        assertNotNull(result.analyzer?.result)
        val projects = result.getProjects()
        assertTrue(projects.any { it.id.type == "Cargo" }, "Should find Cargo project")
    }
}
