plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "sources-mcp"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

val projectVersion = providers.fileContents(layout.rootDirectory.file("version.txt")).asText.get().trim()

gradle.beforeProject {
    group = "dev.rnett.sources-mcp"
    version = projectVersion
}