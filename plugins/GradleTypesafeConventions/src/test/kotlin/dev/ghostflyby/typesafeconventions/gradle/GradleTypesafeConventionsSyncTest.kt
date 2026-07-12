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
import com.intellij.openapi.project.Project
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
import org.jetbrains.plugins.gradle.model.projectModel.GradleBuildEntity
import org.jetbrains.plugins.gradle.model.projectModel.gradleModuleEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.GradleVersionCatalogEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.versionCatalogs
import org.jetbrains.plugins.gradle.service.project.CommonGradleProjectResolverExtension
import org.jetbrains.plugins.gradle.service.project.ProjectResolverContext
import org.jetbrains.plugins.gradle.service.project.open.linkAndSyncGradleProject
import org.jetbrains.plugins.gradle.service.syncAction.GradleSyncListener
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
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

internal data class ConventionBuildCase(
    val name: String,
    val buildPath: String,
    val scriptPath: String,
) {
    override fun toString(): String = name
}

internal data class VersionCatalogInConventionBuildCase(
    val versionCatalog: VersionCatalogCase,
    val conventionBuild: ConventionBuildCase,
) {
    override fun toString(): String = "${conventionBuild.name}: ${versionCatalog.catalogName}"
}

private class GradleTypesafeConventionsSyncedProject(
    private val project: Project,
    private val projectRoot: Path,
    private val createGradleProject: (Path) -> Unit,
) {
    private var projectJdk: Sdk? = null

    suspend fun setUp() {
        createGradleProject(projectRoot)
        val sdk = configureProjectJdk()
        projectJdk = sdk

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
        } catch (throwable: Throwable) {
            projectJdk = null
            cleanupProjectJdk(sdk)
            throw throwable
        }
    }

    suspend fun tearDown() {
        val sdk = projectJdk ?: return
        projectJdk = null
        cleanupProjectJdk(sdk)
    }

    suspend fun assertTypesafeConventionsConventionBuildContributesVersionCatalogModel(
        versionCatalog: VersionCatalogCase,
        conventionBuild: ConventionBuildCase,
    ) {
        val conventionBuildPath = projectRoot.resolve(conventionBuild.buildPath)
        val rootCatalog = findBuildCatalog(projectRoot, versionCatalog.catalogName)
        val conventionBuildCatalog = findBuildCatalog(conventionBuildPath, versionCatalog.catalogName)

        assertNotNull(
            rootCatalog,
            "Expected Gradle sync to create the root ${versionCatalog.catalogName} version catalog entity. " +
                    workspaceModelState(),
        )
        assertNotNull(
            conventionBuildCatalog,
            "Expected Gradle sync to create the ${conventionBuild.name} ${versionCatalog.catalogName} " +
                    "version catalog entity. " +
                    workspaceModelState(),
        )
        assertNotPluginOwnedEntitySource(rootCatalog)
        assertNotPluginOwnedEntitySource(conventionBuildCatalog)
        assertEquals(
            withContext(Dispatchers.IO) {
                projectRoot.resolve(versionCatalog.catalogPath).toRealPath()
            },
            rootCatalog?.url?.url?.toRealPath(),
        )
        assertEquals(
            withContext(Dispatchers.IO) {
                projectRoot.resolve(versionCatalog.catalogPath).toRealPath()
            },
            conventionBuildCatalog?.url?.url?.toRealPath(),
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

    private fun assertNotPluginOwnedEntitySource(entity: GradleVersionCatalogEntity?) {
        val entitySourceClassName = entity?.entitySource?.javaClass?.name
        assertFalse(
            entitySourceClassName?.startsWith("dev.ghostflyby.typesafeconventions.") == true,
            "Expected version catalog entity source to be owned by the Gradle platform, " +
                    "not by the dynamically unloadable plugin. entitySource=$entitySourceClassName",
        )
    }

    suspend fun assertConventionBuildCatalogAccessorGotoDeclarationResolvesToToml(
        scriptPath: Path,
        versionCatalog: VersionCatalogCase,
        referenceText: String,
        resolveTargets: (PsiElement, Int) -> Array<PsiElement>?,
    ) {
        val tomlPath = projectRoot.resolve(versionCatalog.catalogPath).realPath()
        val conventionBuildScript = requirePsiFile(scriptPath)

        val resolvedPaths = readAction {
            val (sourceElement, offset) = findElementAtText(
                conventionBuildScript,
                versionCatalog.expressionText,
                referenceText,
            )
            resolveTargets(sourceElement, offset)
                .orEmpty()
                .mapNotNull { it.containingFile?.virtualFile?.toNioPath() }
        }
        val realResolvedPaths = resolvedPaths.map { it.realPath() }

        assertTrue(
            tomlPath in realResolvedPaths,
            "Expected ${versionCatalog.expressionText} in convention plugin to resolve to TOML. " +
                    "resolvedPaths=$realResolvedPaths ${workspaceModelState()} ${moduleGradleState()}",
        )
    }

    suspend fun assertConventionBuildCatalogAccessorGotoDeclarationResolvesToTomlEntry(
        scriptPath: Path,
        versionCatalog: VersionCatalogCase,
        referenceText: String,
        expectedEntryText: String,
        resolveTargets: (PsiElement, Int) -> Array<PsiElement>?,
    ) {
        val tomlPath = projectRoot.resolve(versionCatalog.catalogPath).realPath()
        val conventionBuildScript = requirePsiFile(scriptPath)

        val resolvedTargets = readAction {
            val (sourceElement, offset) = findElementAtText(
                conventionBuildScript,
                versionCatalog.expressionText,
                referenceText,
            )
            resolveTargets(sourceElement, offset)
                .orEmpty()
                .mapNotNull { target ->
                    target.containingFile?.virtualFile?.toNioPath()?.let { path -> path to target.text }
                }
        }
        val realResolvedTargets = resolvedTargets.map { (path, text) -> path.realPath() to text }

        assertTrue(
            realResolvedTargets.any { (path, text) -> path == tomlPath && text.startsWith(expectedEntryText) },
            "Expected $referenceText in ${versionCatalog.expressionText} to resolve to TOML entry $expectedEntryText. " +
                    "resolvedTargets=$realResolvedTargets ${workspaceModelState()} ${moduleGradleState()}",
        )
    }

    @Suppress("CAST_NEVER_SUCCEEDS")
    fun resolveTargetsWithRegisteredGotoDeclarationHandlers(
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

    private suspend fun configureProjectJdk(): Sdk {
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
            .filter { it.name.contains("buildSrc") || it.name.contains("build-logic") }
            .joinToString(prefix = "modules=[", postfix = "]") { module ->
                val gradleProjectId = module.gradleModuleEntity?.gradleProjectId
                "${module.name}:$gradleProjectId"
            }
    }

    private fun String.toRealPath(): Path =
        Path.of(URI(this)).toRealPath()

    private suspend fun Path.realPath() = withContext(Dispatchers.IO) {
        toRealPath()
    }
}

@TestApplication
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class KotlinDslGradleTypesafeConventionsSyncTest {

    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private lateinit var syncedProject: GradleTypesafeConventionsSyncedProject

    @BeforeAll
    suspend fun setUp() {
        syncedProject = GradleTypesafeConventionsSyncedProject(
            project = project,
            projectRoot = projectPathFixture.get(),
            createGradleProject = ::createGradleProjectWithConventionBuilds,
        )
        syncedProject.setUp()
    }

    @AfterAll
    suspend fun tearDown() {
        syncedProject.tearDown()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionCatalogInConventionBuildCases")
    suspend fun `kotlin dsl convention build contributes version catalog model`(
        testCase: VersionCatalogInConventionBuildCase,
    ) {
        syncedProject.assertTypesafeConventionsConventionBuildContributesVersionCatalogModel(
            versionCatalog = testCase.versionCatalog,
            conventionBuild = testCase.conventionBuild,
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionCatalogInConventionBuildCases")
    suspend fun `kotlin dsl convention build catalog accessor goto resolves to toml library entry`(
        testCase: VersionCatalogInConventionBuildCase,
    ) {
        val projectRoot = projectPathFixture.get()
        syncedProject.assertConventionBuildCatalogAccessorGotoDeclarationResolvesToToml(
            scriptPath = projectRoot.resolve(testCase.conventionBuild.scriptPath),
            versionCatalog = testCase.versionCatalog,
            referenceText = "jupiter",
        ) { sourceElement, offset ->
            syncedProject.resolveTargetsWithRegisteredGotoDeclarationHandlers(sourceElement, offset)
        }
    }

    private fun createGradleProjectWithConventionBuilds(projectRoot: Path) {
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

                includeBuild("build-logic")

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
        writeKotlinDslConventionBuild(projectRoot.resolve("buildSrc"), rootProjectName = null)
        writeKotlinDslConventionBuild(projectRoot.resolve("build-logic"), rootProjectName = "build-logic")
    }

    fun versionCatalogInConventionBuildCases(): List<VersionCatalogInConventionBuildCase> =
        kotlinDslVersionCatalogInConventionBuildCases()
}

@TestApplication
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class GroovyDslGradleTypesafeConventionsSyncTest {

    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private lateinit var syncedProject: GradleTypesafeConventionsSyncedProject

    @BeforeAll
    suspend fun setUp() {
        syncedProject = GradleTypesafeConventionsSyncedProject(
            project = project,
            projectRoot = projectPathFixture.get(),
            createGradleProject = ::createGradleProjectWithConventionBuilds,
        )
        syncedProject.setUp()
    }

    @AfterAll
    suspend fun tearDown() {
        syncedProject.tearDown()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionCatalogInConventionBuildCases")
    suspend fun `groovy dsl convention build contributes version catalog model`(
        testCase: VersionCatalogInConventionBuildCase,
    ) {
        syncedProject.assertTypesafeConventionsConventionBuildContributesVersionCatalogModel(
            versionCatalog = testCase.versionCatalog,
            conventionBuild = testCase.conventionBuild,
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionCatalogInConventionBuildCases")
    suspend fun `groovy dsl convention build catalog root goto resolves to toml file`(
        testCase: VersionCatalogInConventionBuildCase,
    ) {
        val projectRoot = projectPathFixture.get()
        syncedProject.assertConventionBuildCatalogAccessorGotoDeclarationResolvesToToml(
            scriptPath = projectRoot.resolve(testCase.conventionBuild.scriptPath),
            versionCatalog = testCase.versionCatalog,
            referenceText = testCase.versionCatalog.catalogName,
        ) { sourceElement, offset ->
            syncedProject.resolveTargetsWithRegisteredGotoDeclarationHandlers(sourceElement, offset)
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionCatalogInConventionBuildCases")
    suspend fun `groovy dsl convention build catalog accessor goto resolves to toml library entry`(
        testCase: VersionCatalogInConventionBuildCase,
    ) {
        val projectRoot = projectPathFixture.get()
        syncedProject.assertConventionBuildCatalogAccessorGotoDeclarationResolvesToTomlEntry(
            scriptPath = projectRoot.resolve(testCase.conventionBuild.scriptPath),
            versionCatalog = testCase.versionCatalog,
            referenceText = "jupiter",
            expectedEntryText = "junit-jupiter",
        ) { sourceElement, offset ->
            syncedProject.resolveTargetsWithRegisteredGotoDeclarationHandlers(sourceElement, offset)
        }
    }

    private fun createGradleProjectWithConventionBuilds(projectRoot: Path) {
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

                includeBuild('build-logic')

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
        writeGroovyDslConventionBuild(projectRoot.resolve("buildSrc"), rootProjectName = null)
        writeGroovyDslConventionBuild(projectRoot.resolve("build-logic"), rootProjectName = "build-logic")
    }

    fun versionCatalogInConventionBuildCases(): List<VersionCatalogInConventionBuildCase> =
        groovyDslVersionCatalogInConventionBuildCases()
}

private fun kotlinDslVersionCatalogInConventionBuildCases(): List<VersionCatalogInConventionBuildCase> =
    versionCatalogInConventionBuildCases(
        buildSrcScriptPath = "buildSrc/src/main/kotlin/repo.intellij-lib.gradle.kts",
        buildLogicScriptPath = "build-logic/src/main/kotlin/repo.intellij-lib.gradle.kts",
    )

private fun groovyDslVersionCatalogInConventionBuildCases(): List<VersionCatalogInConventionBuildCase> =
    versionCatalogInConventionBuildCases(
        buildSrcScriptPath = "buildSrc/src/main/groovy/repo.intellij-lib.gradle",
        buildLogicScriptPath = "build-logic/src/main/groovy/repo.intellij-lib.gradle",
    )

private fun versionCatalogInConventionBuildCases(
    buildSrcScriptPath: String,
    buildLogicScriptPath: String,
): List<VersionCatalogInConventionBuildCase> =
    conventionBuildCases(buildSrcScriptPath, buildLogicScriptPath).flatMap { conventionBuild ->
        versionCatalogCasesForTypesafeConventions().map { versionCatalog ->
            VersionCatalogInConventionBuildCase(
                versionCatalog = versionCatalog,
                conventionBuild = conventionBuild,
            )
        }
    }

private fun conventionBuildCases(
    buildSrcScriptPath: String,
    buildLogicScriptPath: String,
): List<ConventionBuildCase> =
    listOf(
        ConventionBuildCase(
            name = "buildSrc",
            buildPath = "buildSrc",
            scriptPath = buildSrcScriptPath,
        ),
        ConventionBuildCase(
            name = "build-logic",
            buildPath = "build-logic",
            scriptPath = buildLogicScriptPath,
        ),
    )

private fun versionCatalogCasesForTypesafeConventions(): List<VersionCatalogCase> =
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

private fun writeKotlinDslConventionBuild(buildRoot: Path, rootProjectName: String?) {
    buildRoot.createDirectories()
    buildRoot.resolve("settings.gradle.kts").writeText(
        buildString {
            appendLine(
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
            if (rootProjectName != null) {
                appendLine()
                appendLine("""rootProject.name = "$rootProjectName"""")
            }
        },
    )
    buildRoot.resolve("build.gradle.kts").writeText(
        """
            plugins {
                `kotlin-dsl`
            }
        """.trimIndent(),
    )
    buildRoot.resolve("src/main/kotlin").createDirectories()
        .resolve("repo.intellij-lib.gradle.kts")
        .writeText(
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

private fun writeGroovyDslConventionBuild(buildRoot: Path, rootProjectName: String?) {
    buildRoot.createDirectories()
    buildRoot.resolve("settings.gradle").writeText(
        buildString {
            appendLine(
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
            if (rootProjectName != null) {
                appendLine()
                appendLine("rootProject.name = '$rootProjectName'")
            }
        },
    )
    buildRoot.resolve("build.gradle").writeText(
        """
            plugins {
                id 'groovy-gradle-plugin'
            }
        """.trimIndent(),
    )
    buildRoot.resolve("src/main/groovy").createDirectories()
        .resolve("repo.intellij-lib.gradle")
        .writeText(
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

private fun writeJupiterCatalog(projectRoot: Path, catalogPath: String) {
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
