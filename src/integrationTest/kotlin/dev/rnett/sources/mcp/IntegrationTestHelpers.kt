package dev.rnett.sources.mcp

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

fun createNpmProject(dir: Path, name: String, dependency: Pair<String, String>) {
    dir.createDirectories()
    val (depName, depVersion) = dependency
    dir.resolve("package.json").writeText(
        """
        {
          "name": "$name",
          "version": "1.0.0",
          "dependencies": {
            "$depName": "$depVersion"
          }
        }
        """.trimIndent()
    )
    dir.resolve("package-lock.json").writeText(
        """
        {
          "name": "$name",
          "version": "1.0.0",
          "lockfileVersion": 2,
          "requires": true,
          "packages": {
            "": {
              "name": "$name",
              "version": "1.0.0",
              "dependencies": {
                "$depName": "$depVersion"
              }
            },
            "node_modules/$depName": {
              "version": "$depVersion",
              "resolved": "https://registry.npmjs.org/$depName/-/$depName-$depVersion.tgz"
            }
          },
          "dependencies": {
            "$depName": {
              "version": "$depVersion",
              "resolved": "https://registry.npmjs.org/$depName/-/$depName-$depVersion.tgz"
            }
          }
        }
        """.trimIndent()
    )
}

fun createGradleProject(dir: Path, name: String, dependency: String) {
    dir.createDirectories()
    dir.resolve("settings.gradle").writeText("rootProject.name = '$name'")
    dir.resolve("build.gradle").writeText(
        """
            plugins {
                id 'java'
            }
            repositories {
                mavenCentral()
            }
            dependencies {
                implementation '$dependency'
            }
            """.trimIndent()
    )
}
