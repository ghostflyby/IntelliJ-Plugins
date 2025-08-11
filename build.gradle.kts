allprojects {
    repositories { mavenCentral() }
}

tasks {
    // Keep wrapper version management at the root
    wrapper { gradleVersion = providers.gradleProperty("gradleVersion").get() }
}
