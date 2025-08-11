plugins {
    id("repo.intellij-plugin")
}

version = "0.0.1"

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
