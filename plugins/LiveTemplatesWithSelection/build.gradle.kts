plugins {
    id("repo.intellij-plugin")
}

version = "1.0.0"

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
    }
}
