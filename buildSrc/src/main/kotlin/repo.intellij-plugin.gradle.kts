/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformTestingExtension
import org.jetbrains.intellij.platform.gradle.models.kotlinStdlib
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.jetbrains.intellij.platform.gradle.utils.asPath

plugins {
    id("repo.intellij-module")
    alias(libs.plugins.intellij.platform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
}

val buildLogic: BuildLogicSettings = extensions.getByType()

intellijPlatform {
    pluginConfiguration {
        version = providers.provider { project.version.toString() }

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog
        changeNotes = version.map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion { sinceBuild = buildLogic.pluginSinceBuild }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = this@intellijPlatform.pluginConfiguration.version
            .map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        this.verificationReportsFormats = listOf(
            VerifyPluginTask.VerificationReportsFormats.MARKDOWN,
            VerifyPluginTask.VerificationReportsFormats.PLAIN,
        )
        failureLevel = VerifyPluginTask.FailureLevel.ALL - setOf(VerifyPluginTask.FailureLevel.EXPERIMENTAL_API_USAGES)
        ides.recommended()
    }

}

changelog {
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = project.name + "-v"
}

sourceSets.forEach {
    listOf(
        it.apiConfigurationName,
        it.implementationConfigurationName,
        it.compileOnlyApiConfigurationName,
        it.compileOnlyConfigurationName,
        it.runtimeOnlyConfigurationName,
        it.runtimeClasspathConfigurationName,
    ).forEach { config ->
        configurations.findByName(config)?.apply {
            (kotlinStdlib).forEach { coordinates ->
                exclude(coordinates.groupId, coordinates.artifactId)
            }
            listOf("kotlin-reflect").forEach { dep ->
                exclude("org.jetbrains.kotlin", dep)
            }
            listOf(
                "kotlinx-coroutines-core",
                "kotlinx-coroutines-slf4j",
                "kotlinx-collections-immutable",
                "kotlinx-serialization-core",
                "kotlinx-serialization-json",
                "kotlinx-serialization-json-io",
                "kotlinx-io-core",
                "kotlinx-io-bytestring",

                ).forEach { dep ->
                exclude(group = "org.jetbrains.kotlinx", module = dep)
            }
            exclude(group = "org.slf4j", module = "slf4j-api")
        }
    }
}


tasks {
    tasks.withType<PrepareSandboxTask> {
        disabledPlugins.add("org.jetbrains.completion.full.line")
    }
    publishPlugin { dependsOn(patchChangelog) }

    patchPluginXml {
        vendorName = "ghostflyby"
        vendorEmail = "ghostflyby+intellij@outlook.com"
    }

    processResources {
        from(rootProject.file("LICENSE"))
    }

    val isGitHubActions = providers.environmentVariable("GITHUB_ACTIONS").map { it.toBoolean() }
    val ghReleaseTag = providers.environmentVariable("GH_RELEASE_TAG")
    val path =
        buildPlugin.flatMap { it.archiveFile }.map { it.asPath }

    val upload = tasks.register<Exec>("uploadReleaseAssets") {
        inputs.property("ghReleaseTag", ghReleaseTag)
        inputs.property("isGitHubActions", isGitHubActions)
        inputs.file(path)
        group = "publishing"
        description = "Uploads release assets to GitHub for the specified tag."
        onlyIf { isGitHubActions.get() && ghReleaseTag.isPresent }
        commandLine("gh")
        argumentProviders.add {
            buildList {
                add("release")
                add("upload")
                add(ghReleaseTag.get())
                add(path.get().toString())
            }
        }
    }

    publishPlugin {
        finalizedBy(upload)
    }

    verifyPlugin {
        systemProperty(
            "plugin.verifier.home.dir",
            layout.buildDirectory.dir("plugin-verifier-home").get().asFile.absolutePath
        )
    }

}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests")<IntelliJPlatformTestingExtension.RunIdeParameters> {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                    )
                }
            }
            plugins { robotServerPlugin() }
        }
    }
}
