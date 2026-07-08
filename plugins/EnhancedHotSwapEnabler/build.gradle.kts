/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

plugins {
    id("repo.intellij-plugin")
}

version = "1.5.7"

val hotswapAgentDistribution = configurations.create("hotswapAgentDistribution") {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    implementation(project(":modules:intellij-shared"))

    hotswapAgentDistribution(libs.hotswap.agent)

    implementation(project(":plugins:EnhancedHotSwapEnabler:common"))
    implementation(project(":plugins:EnhancedHotSwapEnabler:gradle"))

    intellijPlatform {
        pluginComposedModule(project(":modules:intellij-shared"))
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
    }
}

tasks {
    prepareSandbox {
        from(hotswapAgentDistribution) {
            into("${project.name}/lib")
        }
    }
    prepareTestSandbox {
        from(hotswapAgentDistribution) {
            into("${project.name}/lib")
        }
    }
}
