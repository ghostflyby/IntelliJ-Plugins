rootProject.name = "IntelliJ Platform Plugin Template"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Monorepo: include plugin subprojects under `plugins/`
file("plugins").listFiles()?.filter { it.isDirectory }?.forEach { pluginDir ->
    include(":plugins:${pluginDir.name}")
}
