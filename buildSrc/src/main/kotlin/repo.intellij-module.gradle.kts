/*
 * Copyright (c) 2025 ghostflyby
 * SPDX-FileCopyrightText: 2025 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
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

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
        @Suppress("UnstableApiUsage")
        vendor = JvmVendorSpec.JETBRAINS
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("21")
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
        val relative = buildLogic.platformType.map { "build/intellij/${it.code}" }
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

