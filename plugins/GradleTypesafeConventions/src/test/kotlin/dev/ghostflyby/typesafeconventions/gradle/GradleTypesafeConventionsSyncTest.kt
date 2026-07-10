/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.externalSystem.util.ExternalSystemActivityKey
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.observation.trackActivity
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.storage.entities
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.parentOfType
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.idea.gradle.versionCatalog.toml.KotlinGradleTomlVersionCatalogGotoDeclarationHandler
import org.jetbrains.plugins.gradle.model.projectModel.GradleBuildEntity
import org.jetbrains.plugins.gradle.model.projectModel.gradleModuleEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.GradleVersionCatalogEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.versionCatalogs
import org.jetbrains.plugins.gradle.service.project.ProjectResolverContext
import org.jetbrains.plugins.gradle.service.project.open.linkAndSyncGradleProject
import org.jetbrains.plugins.gradle.service.syncAction.GradleSyncListener
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.toml.lang.psi.TomlKeyValue
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

    @BeforeEach
    suspend fun setUp() {
        val projectRoot = projectPathFixture.get()
        createKotlinDslGradleProjectWithBuildSrc(projectRoot)

        val modelFetchFuture = CompletableDeferred<Unit>()
        @Suppress("UnstableApiUsage")
        project.messageBus.connect(project).subscribe(
            GradleSyncListener.TOPIC,
            object : GradleSyncListener {
                override fun onModelFetchCompleted(context: ProjectResolverContext) {
                    modelFetchFuture.complete(Unit)
                }
            },
        )
        val importFuture = CompletableDeferred<Unit>()
        @Suppress("CAST_NEVER_SUCCEEDS")
        project.messageBus.connect(project).subscribe(
            ProjectDataImportListener.TOPIC as Topic<ProjectDataImportListener>,
            object : ProjectDataImportListener {
                override fun onFinalTasksFinished(projectPath: String?) {
                    importFuture.complete(Unit)
                }
            },
        )

        configureProjectJdk(projectRoot)

        project.trackActivity(ExternalSystemActivityKey) {
            linkAndSyncGradleProject(project, projectRoot.toString())
        }
        modelFetchFuture.await()
        importFuture.await()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
    }

    @Test
    suspend fun `kotlin dsl typesafe conventions buildSrc project contributes version catalog model`() {
        val projectRoot = projectPathFixture.get()
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

    @Test
    suspend fun `buildSrc catalog accessor goto declaration resolves to toml library entry`() {
        val projectRoot = projectPathFixture.get()
        val buildSrcScriptPath = projectRoot.resolve("buildSrc/src/main/kotlin/repo.intellij-lib.gradle.kts")
        val tomlPath = projectRoot.resolve("gradle/libs.versions.toml").realPath()
        val buildSrcScript = requirePsiFile(buildSrcScriptPath)

        val resolvedPaths = readAction {
            val (sourceElement, offset) = findElementAtText(buildSrcScript, "libs.junit.jupiter", "jupiter")
            KotlinGradleTomlVersionCatalogGotoDeclarationHandler()
                .getGotoDeclarationTargets(sourceElement, offset, null)
                .orEmpty()
                .mapNotNull { it.containingFile?.virtualFile?.toNioPath() }
        }
        val realResolvedPaths = resolvedPaths.map { it.realPath() }

        assertTrue(
            tomlPath in realResolvedPaths,
            "Expected libs.junit.jupiter in buildSrc convention plugin to resolve to TOML. " +
                    "resolvedPaths=$realResolvedPaths ${workspaceModelState()} ${moduleGradleState()}",
        )
    }

    private fun findElementAtText(
        file: PsiFile,
        text: String,
        referenceText: String,
    ): Pair<PsiElement, Int> {
        val textOffset = file.text.indexOf(text).takeIf { it >= 0 }
            ?: error("Cannot find $text in ${file.virtualFile.url}")
        val referenceOffset = text.lastIndexOf(referenceText).takeIf { it >= 0 }
            ?: error("Cannot find $referenceText in $text")
        val offset = textOffset + referenceOffset + referenceText.length / 2
        val sourceElement = file.findElementAt(offset)
            ?: error("Cannot find PSI element for $referenceText in ${file.virtualFile.url}")
        return sourceElement to offset
    }

    private fun findTomlKeyValue(
        file: PsiFile,
        key: String,
    ): PsiElement {
        val offset = file.text.indexOf("$key =").takeIf { it >= 0 }
            ?: error("Cannot find $key in ${file.virtualFile.url}")
        return file.findElementAt(offset)?.parentOfType<TomlKeyValue>()
            ?: error("Cannot find PSI element for $key in ${file.virtualFile.url}")
    }

    private suspend fun requirePsiFile(path: Path): PsiFile {
        val virtualFile = withContext(Dispatchers.IO) {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        } ?: error("Cannot find ${path.realPath()}")
        return readAction {
            PsiManager.getInstance(project).findFile(virtualFile)
        } ?: error("Cannot find PSI for ${virtualFile.url}")
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

    private fun moduleGradleState(): String {
        val snapshot = project.workspaceModel.currentSnapshot
        return snapshot.entities<ModuleEntity>()
            .filter { it.name.contains("buildSrc") }
            .joinToString(prefix = "modules=[", postfix = "]") { module ->
                val gradleProjectId = module.gradleModuleEntity?.gradleProjectId
                "${module.name}:$gradleProjectId"
            }
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
        val buildSrcSourceRoot = buildSrc.resolve("src/main/kotlin").createDirectories()
        buildSrcSourceRoot.resolve("repo.intellij-lib.gradle.kts").writeText(
            """
                plugins {
                    `java-library`
                }

                dependencies {
                    testImplementation(libs.junit.jupiter)
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
    private suspend fun Path.realPath() = withContext(Dispatchers.IO) {
        toRealPath()
    }
}
