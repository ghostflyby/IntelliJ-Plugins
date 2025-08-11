plugins {
    id("repo.intellij-plugin")
}

version = "1.0.0"

dependencies {
    // Keep test dependencies locally versioned via version catalog
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)
}

intellijPlatform {
    pluginConfiguration {
        version = project.version.toString()
    }
}
