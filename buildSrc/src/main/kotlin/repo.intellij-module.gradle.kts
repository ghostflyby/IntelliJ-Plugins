/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformExtension
import org.jetbrains.intellij.platform.gradle.extensions.excludeKotlinStdlib
import org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("repo.module")
    alias(libs.plugins.intellij.platform.module)
    alias(libs.plugins.kover)
}

kotlin {
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation()
}

intellijPlatform {
    pluginVerification {
        ides.current()
    }
}

repositories {
    intellijPlatform { defaultRepositories() }
}

val buildLogic = extensions.create<BuildLogicSettings>("buildLogic")

afterEvaluate {
    dependencies.intellijPlatform {
        val relative = buildLogic.platformType.map { ".idea/intellij/${it.code}" }
        val localPlatform = rootProject.file(relative.get())
        if (localPlatform.exists()) {
            local(localPlatform.readText())
        } else {
            create(buildLogic.platformType, buildLogic.platformVersion) {
                useCache = true
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher) {
        excludeKotlinStdlib()
    }
    testImplementation(libs.opentest4j)
    intellijPlatform {
        testFramework(TestFrameworkType.JUnit5)
        testFramework(TestFrameworkType.Platform)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("java.awt.headless", true)
}

// IJPG keys every project's sandbox by the leaf `project.name` only
// (`sandboxContainer/<project.name>/<productCode>-<version>`), and the module plugin registers
// `prepareSandbox` for content-module subprojects too — so identically named leaves under
// different plugins (e.g. two `frontend` subprojects) would share one sandbox directory and
// cross-contaminate IDE state and test outputs. Non-root projects get a path-derived,
// repo-wide-unique sandbox container and project name instead; plugin roots keep their leaf
// names (already unique) so existing sandboxes do not move.
if (project != rootProject) {
    val dottedPath = project.path.removePrefix(":").replace(':', '.')
    // Typed `configure` because the `intellijPlatform` accessor is not generated for the module
    // plugin in precompiled scripts; the extension itself comes from the base plugin it applies.
    configure<IntelliJPlatformExtension> {
        sandboxContainer = rootProject.layout.projectDirectory.dir(".intellijPlatform/sandbox").dir(dottedPath)
        projectName = dottedPath
    }

    // Plugin roots reach this convention through `repo.intellij-plugin` (which applies it before
    // the full platform plugin), so they cannot be excluded up front; restore their plain names
    // as soon as that plugin shows up — existing root sandboxes and archive naming stay put.
    pluginManager.withPlugin("repo.intellij-plugin") {
        configure<IntelliJPlatformExtension> {
            sandboxContainer = rootProject.layout.projectDirectory.dir(".intellijPlatform/sandbox")
            projectName = project.name
        }
    }

    // The platform resolves content-module jars as lib/modules/<module-id>.jar
    // (PluginDescriptorLoader.loadPluginSubDescriptors), and the sandbox ships the module's
    // composed jar, so when a subproject declares exactly one module descriptor at its
    // src/main/resources root the composed jar is named after that module id automatically.
    val moduleDescriptor = layout.projectDirectory.dir("src/main/resources").asFile
        .listFiles { file -> file.isFile && file.extension.equals("xml", ignoreCase = true) }
        ?.singleOrNull()
    if (moduleDescriptor != null) {
        tasks.named<ComposedJarTask>("composedJar") {
            archiveFileName = moduleDescriptor.nameWithoutExtension + ".jar"
        }
    }
}
