/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.externalSystem.util.ExternalSystemActivityKey
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.backend.observation.trackActivity
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.storage.entities
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
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
import org.jetbrains.plugins.gradle.service.project.CommonGradleProjectResolverExtension
import org.jetbrains.plugins.gradle.service.project.ProjectResolverContext
import org.jetbrains.plugins.gradle.service.project.open.linkAndSyncGradleProject
import org.jetbrains.plugins.gradle.service.syncAction.GradleSyncListener
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

internal data class VersionCatalogCase(
    val catalogName: String,
    val catalogPath: String,
) {
    val expressionText: String
        get() = "$catalogName.junit.jupiter"

    override fun toString(): String = catalogName
}

@TestApplication
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal abstract class AbstractGradleTypesafeConventionsSyncTest {

    protected val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private var gradleProjectConfigured = false
    private var currentProjectJdk: Sdk? = null

    @BeforeEach
    suspend fun setUp() {
        val projectRoot = projectPathFixture.get()
        if (!gradleProjectConfigured) {
            createGradleProjectWithBuildSrc(projectRoot)
        }

        val sdk = configureProjectJdk(projectRoot)
        currentProjectJdk = sdk
        if (gradleProjectConfigured) {
            return
        }

        try {
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

            Registry.get(CommonGradleProjectResolverExtension.GRADLE_VERSION_CATALOGS_DYNAMIC_SUPPORT)
                .setValue(true, project)

            project.trackActivity(ExternalSystemActivityKey) {
                linkAndSyncGradleProject(project, projectRoot.toString())
            }
            modelFetchFuture.await()
            importFuture.await()
            IndexingTestUtil.waitUntilIndexesAreReady(project)
            gradleProjectConfigured = true
        } catch (throwable: Throwable) {
            currentProjectJdk = null
            cleanupProjectJdk(sdk)
            throw throwable
        }
    }

    @AfterEach
    suspend fun tearDownProjectJdk() {
        val sdk = currentProjectJdk ?: return
        currentProjectJdk = null
        cleanupProjectJdk(sdk)
    }

    protected abstract fun createGradleProjectWithBuildSrc(projectRoot: Path)

    protected suspend fun assertTypesafeConventionsBuildSrcProjectContributesVersionCatalogModel(
        catalogName: String = "libs",
        catalogPath: String = "gradle/libs.versions.toml",
    ) {
        val projectRoot = projectPathFixture.get()
        val buildSrcPath = projectRoot.resolve("buildSrc")
        val rootCatalog = findBuildCatalog(projectRoot, catalogName)
        val buildSrcCatalog = findBuildCatalog(buildSrcPath, catalogName)

        assertNotNull(
            rootCatalog,
            "Expected Gradle sync to create the root $catalogName version catalog entity. ${workspaceModelState()}",
        )
        assertNotNull(
            buildSrcCatalog,
            "Expected Gradle sync to create the buildSrc $catalogName version catalog entity. ${workspaceModelState()}",
        )
        assertEquals(
            withContext(Dispatchers.IO) {
                projectRoot.resolve(catalogPath).toRealPath()
            },
            rootCatalog?.url?.url?.toRealPath(),
        )
        assertEquals(
            withContext(Dispatchers.IO) {
                projectRoot.resolve(catalogPath).toRealPath()
            },
            buildSrcCatalog?.url?.url?.toRealPath(),
        )
    }

    @Suppress("UnstableApiUsage")
    private fun findBuildCatalog(
        buildPath: Path,
        name: String,
    ): GradleVersionCatalogEntity? {
        val realBuildPath = buildPath.toRealPath()
        return project.workspaceModel.currentSnapshot
            .entities<GradleVersionCatalogEntity>()
            .singleOrNull {
                it.name == name &&
                        it.build.url.url.toRealPath() == realBuildPath
            }
    }

    protected suspend fun assertBuildSrcCatalogAccessorGotoDeclarationResolvesToToml(
        scriptPath: Path,
        catalogPath: String = "gradle/libs.versions.toml",
        expressionText: String,
        referenceText: String,
        resolveTargets: (PsiElement, Int) -> Array<PsiElement>?,
    ) {
        val projectRoot = projectPathFixture.get()
        val tomlPath = projectRoot.resolve(catalogPath).realPath()
        val buildSrcScript = requirePsiFile(scriptPath)

        val resolvedPaths = readAction {
            val (sourceElement, offset) = findElementAtText(buildSrcScript, expressionText, referenceText)
            resolveTargets(sourceElement, offset)
                .orEmpty()
                .mapNotNull { it.containingFile?.virtualFile?.toNioPath() }
        }
        val realResolvedPaths = resolvedPaths.map { it.realPath() }

        assertTrue(
            tomlPath in realResolvedPaths,
            "Expected $expressionText in buildSrc convention plugin to resolve to TOML. " +
                    "resolvedPaths=$realResolvedPaths ${workspaceModelState()} ${moduleGradleState()}",
        )
    }

    @Suppress("CAST_NEVER_SUCCEEDS")
    protected fun resolveTargetsWithRegisteredGotoDeclarationHandlers(
        sourceElement: PsiElement,
        offset: Int,
    ): Array<PsiElement>? =
        (GotoDeclarationHandler.EP_NAME as ExtensionPointName<GotoDeclarationHandler>)
            .extensionList
            .flatMap { handler ->
                handler.getGotoDeclarationTargets(sourceElement, offset, null).orEmpty().asIterable()
            }
            .toTypedArray()
            .takeIf { it.isNotEmpty() }

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

    private suspend fun requirePsiFile(path: Path): PsiFile {
        val virtualFile = withContext(Dispatchers.IO) {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        } ?: error("Cannot find ${path.realPath()}")
        return readAction {
            PsiManager.getInstance(project).findFile(virtualFile)
        } ?: error("Cannot find PSI for ${virtualFile.url}")
    }

    private suspend fun configureProjectJdk(projectRoot: Path): Sdk {
        val javaHome = System.getProperty("java.home")
        val sdk =
            JavaSdk.getInstance().createJdk("typesafe-conventions-test-jdk-${projectRoot.fileName}", javaHome, false)

        backgroundWriteAction {
            ProjectJdkTable.getInstance().addJdk(sdk, project)
            ProjectRootManager.getInstance(project).projectSdk = sdk
        }
        return sdk
    }

    private suspend fun cleanupProjectJdk(sdk: Sdk) {
        backgroundWriteAction {
            if (ProjectRootManager.getInstance(project).projectSdk == sdk) {
                ProjectRootManager.getInstance(project).projectSdk = null
            }
            ProjectJdkTable.getInstance().removeJdk(sdk)
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

    protected fun copyGradleWrapper(projectRoot: Path) {
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

    protected fun writeJupiterCatalog(projectRoot: Path, catalogPath: String) {
        projectRoot.resolve(catalogPath).parent.createDirectories()
        projectRoot.resolve(catalogPath).writeText(
            """
                [versions]
                junit-jupiter = "6.1.1"

                [libraries]
                junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit-jupiter" }
            """.trimIndent(),
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

    protected suspend fun Path.realPath() = withContext(Dispatchers.IO) {
        toRealPath()
    }

    protected fun versionCatalogCases(): List<VersionCatalogCase> =
        listOf(
            VersionCatalogCase(
                catalogName = "libs",
                catalogPath = "gradle/libs.versions.toml",
            ),
            VersionCatalogCase(
                catalogName = "customLibs",
                catalogPath = "gradle/customLibs.versions.toml",
            ),
        )
}

@TestApplication
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class KotlinDslGradleTypesafeConventionsSyncTest : AbstractGradleTypesafeConventionsSyncTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionCatalogCases")
    suspend fun `kotlin dsl buildSrc catalog accessor contributes model and resolves to toml library entry`(
        versionCatalog: VersionCatalogCase,
    ) {
        assertTypesafeConventionsBuildSrcProjectContributesVersionCatalogModel(
            catalogName = versionCatalog.catalogName,
            catalogPath = versionCatalog.catalogPath,
        )

        val projectRoot = projectPathFixture.get()
        assertBuildSrcCatalogAccessorGotoDeclarationResolvesToToml(
            scriptPath = projectRoot.resolve("buildSrc/src/main/kotlin/repo.intellij-lib.gradle.kts"),
            catalogPath = versionCatalog.catalogPath,
            expressionText = versionCatalog.expressionText,
            referenceText = "jupiter",
        ) { sourceElement, offset ->
            KotlinGradleTomlVersionCatalogGotoDeclarationHandler()
                .getGotoDeclarationTargets(sourceElement, offset, null)
        }
    }

    override fun createGradleProjectWithBuildSrc(projectRoot: Path) {
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
                    versionCatalogs {
                        create("customLibs") {
                            from(files("gradle/customLibs.versions.toml"))
                        }
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
        writeJupiterCatalog(projectRoot, "gradle/libs.versions.toml")
        writeJupiterCatalog(projectRoot, "gradle/customLibs.versions.toml")
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
                    testImplementation(customLibs.junit.jupiter)
                }
            """.trimIndent(),
        )
    }
}

@TestApplication
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class GroovyDslGradleTypesafeConventionsSyncTest : AbstractGradleTypesafeConventionsSyncTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionCatalogCases")
    suspend fun `groovy dsl buildSrc catalog accessor contributes model and resolves to toml file`(
        versionCatalog: VersionCatalogCase,
    ) {
        assertTypesafeConventionsBuildSrcProjectContributesVersionCatalogModel(
            catalogName = versionCatalog.catalogName,
            catalogPath = versionCatalog.catalogPath,
        )

        val projectRoot = projectPathFixture.get()
        assertBuildSrcCatalogAccessorGotoDeclarationResolvesToToml(
            scriptPath = projectRoot.resolve("buildSrc/src/main/groovy/repo.intellij-lib.gradle"),
            catalogPath = versionCatalog.catalogPath,
            expressionText = versionCatalog.expressionText,
            referenceText = versionCatalog.catalogName,
        ) { sourceElement, offset ->
            resolveTargetsWithRegisteredGotoDeclarationHandlers(sourceElement, offset)
        }
    }

    override fun createGradleProjectWithBuildSrc(projectRoot: Path) {
        copyGradleWrapper(projectRoot)
        projectRoot.resolve("settings.gradle").writeText(
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
                    versionCatalogs {
                        create('customLibs') {
                            from(files('gradle/customLibs.versions.toml'))
                        }
                    }
                }

                rootProject.name = 'typesafe-conventions-groovy-test'
            """.trimIndent(),
        )
        projectRoot.resolve("build.gradle").writeText(
            """
                plugins {
                    id 'java'
                }
                
                dependencies {
                    testImplementation libs.junit.jupiter
                }
            """.trimIndent(),
        )
        writeJupiterCatalog(projectRoot, "gradle/libs.versions.toml")
        writeJupiterCatalog(projectRoot, "gradle/customLibs.versions.toml")
        val buildSrc = projectRoot.resolve("buildSrc").createDirectories()
        buildSrc.resolve("settings.gradle").writeText(
            """
                pluginManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
                plugins {
                    id 'dev.panuszewski.typesafe-conventions' version '0.11.1'
                }
                dependencyResolutionManagement {
                    repositories {
                        gradlePluginPortal()
                        mavenCentral()
                    }
                }
            """.trimIndent(),
        )
        buildSrc.resolve("build.gradle").writeText(
            """
                plugins {
                    id 'groovy-gradle-plugin'
                }
            """.trimIndent(),
        )
        val buildSrcSourceRoot = buildSrc.resolve("src/main/groovy").createDirectories()
        buildSrcSourceRoot.resolve("repo.intellij-lib.gradle").writeText(
            """
                plugins {
                    id 'java-library'
                }

                dependencies {
                    testImplementation libs.junit.jupiter
                    testImplementation customLibs.junit.jupiter
                }
            """.trimIndent(),
        )
    }
}
