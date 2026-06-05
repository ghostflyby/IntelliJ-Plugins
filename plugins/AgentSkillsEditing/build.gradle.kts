plugins {
    id("repo.intellij-plugin")
}

version = "0.2.0"

dependencies.intellijPlatform {
    bundledPlugin("com.intellij.modules.json")
    bundledPlugin("org.intellij.plugins.markdown")
    bundledPlugin("org.jetbrains.plugins.yaml")
}
