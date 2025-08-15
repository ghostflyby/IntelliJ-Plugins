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

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.IntelliJPlatformTestingExtension
import org.jetbrains.intellij.platform.gradle.utils.asPath
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java // Java support
    alias(libs.plugins.kotlin) // Kotlin support
    alias(libs.plugins.intelliJPlatform) // IntelliJ Platform Gradle Plugin
    alias(libs.plugins.changelog) // Gradle Changelog Plugin
    alias(libs.plugins.qodana) // Gradle Qodana Plugin
    alias(libs.plugins.kover) // Gradle Kover Plugin}
}


val buildLogic = extensions.create<BuildLogicSettings>("buildLogic")

group = providers.gradleProperty("pluginGroup").get()

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("21")
    }
    explicitApi()
}

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val localPlatform = rootProject.file(".idea/intellijPlatform")
        if (localPlatform.exists()) {
            local(localPlatform.readText())
        } else {
            create(buildLogic.platformType, buildLogic.platformVersion)
        }

        testFramework(TestFrameworkType.Platform)
    }

    // Keep test dependencies locally versioned via version catalog
    testImplementation(libs.junit)
    testImplementation(libs.opentest4j)

}

intellijPlatform {
    pluginConfiguration {
        version = buildLogic.pluginVersion

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

    pluginVerification { ides { recommended() } }
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

kover { reports { total { xml { onCheck = true } } } }

tasks {
    withType<Test> {
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
            events("failed", "skipped")
        }
    }

    prepareSandbox {
        disabledPlugins.add("org.jetbrains.completion.full.line")
        this.sandboxSystemDirectory = rootProject.layout.buildDirectory.dir("idea-sandbox/system")
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
        argumentProviders.add(CommandLineArgumentProvider {
            val args = mutableListOf<String>()
            args.add("release")
            args.add("upload")
            args.add(ghReleaseTag.get())
            args.add(path.get().toString())
            args
        })
    }

    publishPlugin {
        finalizedBy(upload)
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

