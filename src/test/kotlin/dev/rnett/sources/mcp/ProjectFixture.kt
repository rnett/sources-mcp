package dev.rnett.sources.mcp

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ProjectFixture(val root: Path) {
    fun file(path: String, content: String) {
        val file = root.resolve(path)
        file.parent.createDirectories()
        file.writeText(content.trimIndent())
    }
}

fun testProject(root: Path, block: ProjectFixture.() -> Unit): ProjectFixture {
    return ProjectFixture(root).apply(block)
}

// --- NPM ---

fun ProjectFixture.npm(block: NpmBuilder.() -> Unit) {
    NpmBuilder(this).apply(block).build()
}

class NpmBuilder(private val fixture: ProjectFixture) {
    private val dependencies = mutableMapOf<String, String>()
    private var name = "test-project"

    fun name(name: String) {
        this.name = name
    }

    fun addDependency(name: String, version: String) {
        dependencies[name] = version
    }

    fun build() {
        val depsJson = dependencies.entries.joinToString(",\n        ") { (k, v) -> "\"$k\": \"$v\"" }
        fixture.file(
            "package.json", """
            {
              "name": "$name",
              "version": "1.0.0",
              "dependencies": {
                $depsJson
              }
            }
        """.trimIndent()
        )
        fixture.file("package-lock.json", "{}")
    }
}

// --- Gradle ---

fun ProjectFixture.gradle(block: GradleBuilder.() -> Unit) {
    GradleBuilder(this).apply(block).build()
}

class GradleBuilder(private val fixture: ProjectFixture) {
    private var buildScript: String = ""
    private var settings: String = "rootProject.name = \"test-project\""
    private val modules = mutableMapOf<String, String>()

    fun buildScript(content: String) {
        this.buildScript = content
    }

    fun settings(content: String) {
        this.settings = content
    }

    fun addModule(name: String, buildScript: String) {
        modules[name] = buildScript
    }

    fun build() {
        fixture.file("build.gradle", buildScript)
        val finalSettings = if (modules.isEmpty()) {
            settings
        } else {
            settings + "\n" + modules.keys.joinToString("\n") { "include(\":$it\")" }
        }
        fixture.file("settings.gradle", finalSettings)
        modules.forEach { (name, script) ->
            fixture.file("$name/build.gradle", script)
        }
    }
}

// --- Kotlin ---

fun ProjectFixture.kotlinJvm(block: KotlinJvmBuilder.() -> Unit) {
    KotlinJvmBuilder(this).apply(block).build()
}

class KotlinJvmBuilder(private val fixture: ProjectFixture) {
    private val dependencies = mutableListOf<String>()

    fun addDependency(dep: String) {
        dependencies.add(dep)
    }

    fun build() {
        fixture.gradle {
            buildScript(
                """
                plugins {
                    id 'org.jetbrains.kotlin.jvm' version '1.9.22'
                }
                repositories {
                    mavenCentral()
                }
                dependencies {
                    ${dependencies.joinToString("\n                    ") { "implementation '$it'" }}
                }
            """.trimIndent()
            )
        }
    }
}

fun ProjectFixture.kotlinMultiplatform(block: KmpBuilder.() -> Unit) {
    KmpBuilder(this).apply(block).build()
}

class KmpBuilder(private val fixture: ProjectFixture) {
    private var jvm = false
    private var js = false
    private var wasmWasi = false
    private val commonDependencies = mutableListOf<String>()

    fun jvm() {
        jvm = true
    }

    fun js() {
        js = true
    }

    fun wasmWasi() {
        wasmWasi = true
    }

    fun addCommonDependency(dep: String) {
        commonDependencies.add(dep)
    }

    fun build() {
        val targets = mutableListOf<String>()
        if (jvm) targets.add("jvm()")
        if (js) targets.add("js(IR) { browser() }")
        if (wasmWasi) targets.add("wasmWasi { nodejs() }")

        fixture.gradle {
            buildScript(
                """
                plugins {
                    id 'org.jetbrains.kotlin.multiplatform' version '1.9.22'
                }
                repositories {
                    mavenCentral()
                }
                kotlin {
                    ${targets.joinToString("\n                    ")}
                    
                    sourceSets {
                        commonMain {
                            dependencies {
                                ${commonDependencies.joinToString("\n                                ") { "implementation '$it'" }}
                            }
                        }
                    }
                }
            """.trimIndent()
            )
        }
    }
}

// --- Maven ---

fun ProjectFixture.maven(block: MavenBuilder.() -> Unit) {
    MavenBuilder(this).apply(block).build()
}

class MavenBuilder(private val fixture: ProjectFixture) {
    private var pomContent: String = ""

    fun pom(content: String) {
        this.pomContent = content
    }

    fun build() {
        fixture.file("pom.xml", pomContent)
    }
}

// --- Pip ---

fun ProjectFixture.pip(block: PipBuilder.() -> Unit) {
    PipBuilder(this).apply(block).build()
}

class PipBuilder(private val fixture: ProjectFixture) {
    private val requirements = mutableListOf<String>()

    fun addRequirement(req: String) {
        requirements.add(req)
    }

    fun build() {
        fixture.file("requirements.txt", requirements.joinToString("\n"))
    }
}

// --- Go ---

fun ProjectFixture.go(block: GoBuilder.() -> Unit) {
    GoBuilder(this).apply(block).build()
}

class GoBuilder(private val fixture: ProjectFixture) {
    private var moduleName = "example.com/test"
    private val requires = mutableListOf<String>()

    fun moduleName(name: String) {
        this.moduleName = name
    }

    fun require(req: String) {
        requires.add(req)
    }

    fun build() {
        val requiresStr = if (requires.isEmpty()) "" else "require (\n    ${requires.joinToString("\n    ")}\n)"
        fixture.file(
            "go.mod", """
            module $moduleName

            go 1.21

            $requiresStr
        """.trimIndent()
        )
    }
}

// --- Cargo ---

fun ProjectFixture.cargo(block: CargoBuilder.() -> Unit) {
    CargoBuilder(this).apply(block).build()
}

class CargoBuilder(private val fixture: ProjectFixture) {
    private var name = "test-project"
    private val dependencies = mutableMapOf<String, String>()

    fun name(name: String) {
        this.name = name
    }

    fun addDependency(name: String, version: String) {
        dependencies[name] = version
    }

    fun build() {
        val depsStr = dependencies.entries.joinToString("\n") { (k, v) -> "$k = \"$v\"" }
        fixture.file(
            "Cargo.toml", """
            [package]
            name = "$name"
            version = "0.1.0"
            edition = "2021"

            [dependencies]
            $depsStr
        """.trimIndent()
        )
    }
}
