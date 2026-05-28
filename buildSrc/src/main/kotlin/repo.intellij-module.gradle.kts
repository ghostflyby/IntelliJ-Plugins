/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    java // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intellij.module) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin}
}


val buildLogic = extensions.create<BuildLogicSettings>("buildLogic")

group = providers.gradleProperty("pluginGroup").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("21")
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
    explicitApi()
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
        enabled = true
    }
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

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

    // Keep test dependencies locally versioned via version catalog
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)
    intellijPlatform {
        testFramework(TestFrameworkType.JUnit5)
        testFramework(TestFrameworkType.Platform)
    }

}

kover { reports { total { xml { onCheck = true } } } }

tasks {
    withType<Test> {
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
            events("failed", "skipped")
        }
    }
    processResources {
        from(rootProject.file("LICENSE"))
    }

    check {
        finalizedBy(checkLegacyAbi)
    }

}
