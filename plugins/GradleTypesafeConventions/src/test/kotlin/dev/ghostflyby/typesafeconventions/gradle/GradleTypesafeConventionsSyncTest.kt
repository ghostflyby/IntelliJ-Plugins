/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.externalSystem.util.ExternalSystemActivityKey
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.platform.backend.observation.trackActivity
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.storage.entities
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.disposableFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.gradle.model.projectModel.GradleBuildEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.GradleVersionCatalogEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.versionCatalogs
import org.jetbrains.plugins.gradle.service.project.ProjectResolverContext
import org.jetbrains.plugins.gradle.service.project.open.linkAndSyncGradleProject
import org.jetbrains.plugins.gradle.service.syncAction.GradleSyncListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

@TestApplication
internal class GradleTypesafeConventionsSyncTest {

    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private val disposable by disposableFixture()

    @Test
    suspend fun `kotlin dsl typesafe conventions buildSrc project contributes version catalog model`() {
        val future = CompletableDeferred<Unit>()
        @Suppress("UnstableApiUsage")
        project.messageBus.connect(disposable).subscribe(
            GradleSyncListener.TOPIC,
            object : GradleSyncListener {
                override fun onModelFetchCompleted(context: ProjectResolverContext) {
                    future.complete(Unit)
                }
            },
        )

        val projectRoot = projectPathFixture.get()
        createKotlinDslGradleProjectWithBuildSrc(projectRoot)
        configureProjectJdk(projectRoot)

        project.trackActivity(ExternalSystemActivityKey) {
            linkAndSyncGradleProject(project, projectRoot.toString())
        }
        future.await()

        val buildSrcPath = projectRoot.resolve("buildSrc")
        val rootCatalog = findBuildCatalog(projectRoot, "libs")
        val buildSrcCatalog = findBuildCatalog(buildSrcPath, "libs")

        assertNotNull(
            rootCatalog,
            "Expected Gradle sync to create the root libs version catalog entity. ${workspaceModelState()}",
        )
        assertNotNull(
            buildSrcCatalog,
            "Expected Gradle sync to create the buildSrc libs version catalog entity. ${workspaceModelState()}",
        )
        assertEquals(
            withContext(Dispatchers.IO) {
                projectRoot.resolve("gradle/libs.versions.toml").toRealPath()
            },
            rootCatalog?.url?.url?.toRealPath(),
        )
        assertEquals(
            withContext(Dispatchers.IO) {
                projectRoot.resolve("gradle/libs.versions.toml").toRealPath()
            },
            buildSrcCatalog?.url?.url?.toRealPath(),
        )
    }

    @Suppress("UnstableApiUsage")
    private fun findBuildCatalog(
        buildPath: Path,
        @Suppress("SameParameterValue") name: String,
    ): GradleVersionCatalogEntity? {
        val realBuildPath = buildPath.toRealPath()
        return project.workspaceModel.currentSnapshot
            .entities<GradleVersionCatalogEntity>()
            .singleOrNull {
                it.name == name &&
                        it.build.url.url.toRealPath() == realBuildPath
            }
    }

    private suspend fun configureProjectJdk(projectRoot: Path) {
        val javaHome = System.getProperty("java.home")
        val sdk =
            JavaSdk.getInstance().createJdk("typesafe-conventions-test-jdk-${projectRoot.fileName}", javaHome, false)

        backgroundWriteAction {
            ProjectJdkTable.getInstance().addJdk(sdk, project)
            ProjectRootManager.getInstance(project).projectSdk = sdk
        }
    }

    @Suppress("UnstableApiUsage")
    private fun workspaceModelState(): String {
        val snapshot = project.workspaceModel.currentSnapshot
        val builds = snapshot.entities<GradleBuildEntity>()
            .joinToString(prefix = "builds=[", postfix = "]") { build ->
                val catalogs = build.versionCatalogs.joinToString(prefix = "[", postfix = "]") {
                    "${it.name}:${it.url.url}"
                }
                "${build.url.url}:$catalogs"
            }
        val catalogs = snapshot.entities<GradleVersionCatalogEntity>()
            .joinToString(prefix = "catalogs=[", postfix = "]") { "${it.name}:${it.url.url}" }
        return "$builds $catalogs"
    }

    private fun createKotlinDslGradleProjectWithBuildSrc(projectRoot: Path) {
        copyGradleWrapper(projectRoot)
        projectRoot.resolve("settings.gradle.kts").writeText(
            """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                
                dependencyResolutionManagement {
                    repositories {
                        mavenCentral()
                    }
                }

                rootProject.name = "typesafe-conventions-kts-test"
            """.trimIndent(),
        )
        projectRoot.resolve("build.gradle.kts").writeText(
            """
                plugins {
                    java
                }
                
                dependencies {
                    testImplementation(libs.junit.jupiter)
                }
            """.trimIndent(),
        )
        projectRoot.resolve("gradle").createDirectories()
        projectRoot.resolve("gradle/libs.versions.toml").writeText(
            """
                [versions]
                junit-jupiter = "6.1.1"
                
                [libraries]
                junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit-jupiter" }
            """.trimIndent(),
        )
        val buildSrc = projectRoot.resolve("buildSrc").createDirectories()
        buildSrc.resolve("settings.gradle.kts").writeText(
            """
                dependencyResolutionManagement {
                    @Suppress("UnstableApiUsage")
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                plugins {
                    id("dev.panuszewski.typesafe-conventions") version "0.11.1"
                }
            """.trimIndent(),
        )
        buildSrc.resolve("build.gradle.kts").writeText(
            """
                plugins {
                    `kotlin-dsl`
                }
            """.trimIndent(),
        )
    }

    private fun copyGradleWrapper(projectRoot: Path) {
        val repositoryRoot = findRepositoryRoot()
        Files.copy(
            repositoryRoot.resolve("gradlew"),
            projectRoot.resolve("gradlew"),
            StandardCopyOption.COPY_ATTRIBUTES,
        )
        Files.copy(
            repositoryRoot.resolve("gradlew.bat"),
            projectRoot.resolve("gradlew.bat"),
            StandardCopyOption.COPY_ATTRIBUTES,
        )

        val wrapperRoot = projectRoot.resolve("gradle/wrapper").createDirectories()
        Files.copy(
            repositoryRoot.resolve("gradle/wrapper/gradle-wrapper.jar"),
            wrapperRoot.resolve("gradle-wrapper.jar"),
            StandardCopyOption.COPY_ATTRIBUTES,
        )
        Files.copy(
            repositoryRoot.resolve("gradle/wrapper/gradle-wrapper.properties"),
            wrapperRoot.resolve("gradle-wrapper.properties"),
            StandardCopyOption.COPY_ATTRIBUTES,
        )
    }

    private fun findRepositoryRoot(): Path {
        return generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .firstOrNull {
                Files.exists(it.resolve("settings.gradle.kts")) &&
                        Files.exists(it.resolve("gradlew")) &&
                        Files.isDirectory(it.resolve("plugins"))
            }
            ?: error("Cannot locate IntelliJ-Plugins repository root from ${Path.of("").toAbsolutePath()}")
    }

    private fun String.toRealPath(): Path =
        Path.of(URI(this)).toRealPath()
}
