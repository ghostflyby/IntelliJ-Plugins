/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
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

import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("repo.intellij-plugin")
    kotlin("plugin.sam.with.receiver") version libs.versions.kotlin
}

version = "1.3.7"

buildLogic {
    pluginVersion = version.toString()
    platformType = IntelliJPlatformType.IntellijIdeaCommunity
    platformVersion = "2025.1"
    pluginSinceBuild = "251"
}


sourceSets {
    val common by creating
    val gradle by creating

    tasks.jar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(common.output, main.map { it.output }, gradle.output)
    }
}

kotlin.target.compilations {
    val main by getting
    val common by getting
    val gradle by getting
    main.associateWith(common)
    gradle.associateWith(common)
}

samWithReceiver {
    annotation("org.gradle.api.HasImplicitReceiver")
}

dependencies {
    val commonCompileOnly by configurations.getting
    val gradleCompileOnly by configurations.getting

    commonCompileOnly(kotlin("stdlib"))
    gradleCompileOnly(gradleKotlinDsl())
    gradleCompileOnly(gradleApi())

    intellijPlatform {
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
    }
}
tasks.prepareSandbox {
    val common by sourceSets.getting
    val commonFiles = common.output.asFileTree.map { it.toPath() }.toSet()
    exclude {
        it.file.toPath() in commonFiles
    }
    includeEmptyDirs = false
}

tasks.withType<KotlinCompile>().named { it == "compileCommonKotlin" || it == "compileGradleKotlin" }.configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

tasks.withType<JavaCompile>().named { it == "compileCommonJava" || it == "compileGradleJava" }.configureEach {
    targetCompatibility = "1.8"
    sourceCompatibility = "21"
}
