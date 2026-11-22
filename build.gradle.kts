/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.tasks.ClasspathNormalizer

plugins {
    java
}

allprojects {
    pluginManager.apply(JavaPlugin::class.java)
    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
            @Suppress("UnstableApiUsage")
            vendor = JvmVendorSpec.JETBRAINS
        }
    }
    repositories { mavenCentral() }

    // Downloads the dependencies the build references, completing the classpaths.
    // Consumed by .github/workflows/inspect-code.yml ("Materialize Gradle build state").
    val projectPath = path
    tasks.register("resolveDependencies") {
        group = "build setup"
        description =
            "Resolves the external module artifacts of every resolvable configuration of this project."
        inputs
            .files(
                configurations
                    .filter { it.isCanBeResolved }
                    .map { configuration ->
                        configuration.incoming.artifactView {
                            componentFilter { it is ModuleComponentIdentifier }
                        }.files
                    },
            )
            .withNormalizer(ClasspathNormalizer::class.java)
        doLast {
            logger.lifecycle("Resolved external dependencies of {}", projectPath)
        }
    }
}
repositories {
    mavenCentral()

    // Keep IntelliJ Maven repositories visible from the root build so the IDE can resolve sources
    // without turning the aggregator project itself into an IntelliJ Platform module.
    maven(url = "https://www.jetbrains.com/intellij-repository/releases")
    maven(url = "https://www.jetbrains.com/intellij-repository/snapshots")
    maven(url = "https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven(url = "https://plugins.jetbrains.com/maven")
}
