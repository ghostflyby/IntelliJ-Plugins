/*
 * Copyright (c) 2025 ghostflyby <ghostflyby+intellij@outlook.com>
 *
 * This program is free software; you can redistribute it and/or
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

plugins {
    id("repo.intellij-plugin")
    kotlin("plugin.sam.with.receiver") version libs.versions.kotlin
}

version = "1.1.0"

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

