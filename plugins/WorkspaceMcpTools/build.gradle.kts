/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
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

plugins {
    id("repo.intellij-plugin")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

version = "1.0.4"


dependencies {
    api(libs.mcp.kotlin.sdk.server)
    implementation(libs.ktor.server.cio)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    ksp("org.jetbrains.kotlinx:kotlinx-schema-ksp:0.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-schema-annotations:0.5.0")
}

ksp {
    arg("kotlinx.schema.rootPackage", "dev.ghostflyby.mcp")
    arg("kotlinx.schema.withSchemaObject", "true")
    arg("kotlinx.schema.visibility", "internal")
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xannotation-default-target=param-property"))
}