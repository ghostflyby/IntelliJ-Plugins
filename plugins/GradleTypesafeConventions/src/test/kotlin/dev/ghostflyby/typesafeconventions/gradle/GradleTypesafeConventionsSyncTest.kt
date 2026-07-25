/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
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
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.backend.observation.trackActivity
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.storage.entities
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlKeyValue
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

    suspend fun assertConventionBuildKotlinCatalogReferenceResolvesToTomlEntry(
        scriptPath: Path,
        versionCatalog: VersionCatalogCase,
        declarationPath: String,
    ) {
        val expectedEntry = requireTomlCatalogEntry(versionCatalog, declarationPath)
        val conventionBuildScript = requirePsiFile(scriptPath)

        val resolvedEntry = readAction {
            val expression = findKotlinCatalogExpression(conventionBuildScript, versionCatalog.expressionText)
            val reference = expression.references
                .filterIsInstance<TypesafeConventionsKotlinCatalogReference>()
                .singleOrNull()
                ?: error(
                    "Expected ${versionCatalog.expressionText} to expose exactly one " +
                            "TypesafeConventionsKotlinCatalogReference. " +
                            "references=${expression.references.map { it.javaClass.name }}",
                )
            reference.resolve()
        }

        assertSame(
            expectedEntry,
            resolvedEntry,
            "Expected ${versionCatalog.expressionText} to resolve to the exact TOML entry.",
        )
    }

    suspend fun assertKotlinCatalogFindUsagesAreIsolatedToTargetCatalog(
        versionCatalog: VersionCatalogCase,
        declarationPath: String,
        expectedExpressionText: String,
        expectedScriptPaths: List<Path>,
    ) {
        val expectedEntry = requireTomlCatalogEntry(versionCatalog, declarationPath)
        val keySegment = readAction {
            expectedEntry.key.segments.singleOrNull()
        } ?: error("Expected $declarationPath to use a single TOML key segment")
        val expectedPsiFiles = expectedScriptPaths.map { requirePsiFile(it) }
        readAction {
            expectedPsiFiles.forEach { file ->
                val expression = findKotlinCatalogExpression(file, expectedExpressionText)
                val reference = expression.references
                    .filterIsInstance<TypesafeConventionsKotlinCatalogReference>()
                    .singleOrNull()
                    ?: error("Expected $expectedExpressionText to expose a local reference in ${file.name}")
                assertSame(
                    expectedEntry,
                    reference.resolve(),
                    "Expected $expectedExpressionText to resolve to the searched catalog entry in ${file.name}",
                )
            }
        }
        val expectedFiles = expectedPsiFiles.map { it.virtualFile }
        val catalogBuildRoots = readAction {
            findTypesafeConventionsCatalogBuildRoots(expectedEntry.containingFile as TomlFile)
        }
        assertTrue(
            expectedFiles.all { file ->
                catalogBuildRoots.any { root -> VfsUtilCore.isAncestor(root, file, false) }
            },
            "Expected every convention Kotlin source to belong to a catalog Gradle build. " +
                    "files=${
                        expectedFiles.associate { file ->
                            file.path to catalogBuildRoots.any { root -> VfsUtilCore.isAncestor(root, file, false) }
                        }
                    }",
        )
        val references = readAction {
            ReferencesSearch.search(keySegment, GlobalSearchScope.projectScope(project)).findAll()
        }
        val resolvedReferences = readAction {
            references.mapNotNull { reference ->
                val expression = reference.element as? KtDotQualifiedExpression ?: return@mapNotNull null
                val path = expression.containingFile.virtualFile?.toNioPath() ?: return@mapNotNull null
                Triple(path, expression.text, reference.resolve())
            }
        }
        val actualUsages = resolvedReferences
            .filter { (_, _, resolved) -> resolved === expectedEntry }
            .map { (path, text, _) -> path.realPath() to text }
            .toSet()
        val expectedUsages = expectedScriptPaths
            .map { it.realPath() to expectedExpressionText }
            .toSet()

        assertEquals(
            expectedUsages,
            actualUsages,
            "Expected Find Usages for ${versionCatalog.catalogName}:$declarationPath to stay in the target catalog. " +
                    "references=${references.map { it.javaClass.name }}",
        )
        assertTrue(
            references.any { it is TypesafeConventionsKotlinCatalogUsageReference },
            "Expected the local references searcher to contribute a rename-capable usage reference.",
        )
    }

    suspend fun renameKotlinCatalogAliasAndAssertUsages(
        versionCatalog: VersionCatalogCase,
        oldDeclarationPath: String,
        newAliasName: String,
        newDeclarationPath: String,
        expectedScriptPaths: List<Path>,
    ) {
        val keySegment = requireTomlCatalogKeySegment(versionCatalog, oldDeclarationPath)
        @Suppress("UnstableApiUsage")
        writeIntentReadAction {
            RenameProcessor(project, keySegment, newAliasName, false, false).run()
        }

        val tomlFile = requirePsiFile(projectRoot.resolve(versionCatalog.catalogPath)) as TomlFile
        val scripts = expectedScriptPaths.map { requirePsiFile(it) }
        readAction {
            assertNull(findTypesafeConventionsCatalogEntry(tomlFile, oldDeclarationPath))
            assertEquals(
                newAliasName,
                findTypesafeConventionsCatalogEntry(tomlFile, newDeclarationPath)?.key?.text,
            )
            val oldExpression = "${versionCatalog.catalogName}.$oldDeclarationPath"
            val newExpression = "${versionCatalog.catalogName}.$newDeclarationPath"
            scripts.forEach { script ->
                assertFalse(
                    script.text.contains(oldExpression),
                    "Expected $oldExpression to be renamed in ${script.name}",
                )
                assertTrue(script.text.contains(newExpression), "Expected $newExpression in ${script.name}")
            }
        }
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

    private fun findKotlinCatalogExpression(file: PsiFile, expressionText: String): KtDotQualifiedExpression =
        PsiTreeUtil.findChildrenOfType(file, KtDotQualifiedExpression::class.java)
            .singleOrNull {
                it.text == expressionText && it.matchesTopmostTypesafeConventionsCatalogReferencePattern()
            }
            ?: error("Cannot find topmost Kotlin catalog expression $expressionText in ${file.virtualFile.url}")

    private suspend fun requireTomlCatalogEntry(
        versionCatalog: VersionCatalogCase,
        declarationPath: String,
    ): TomlKeyValue {
        val tomlFile = requirePsiFile(projectRoot.resolve(versionCatalog.catalogPath)) as? TomlFile
            ?: error("Expected ${versionCatalog.catalogPath} to be a TOML PSI file")
        return readAction {
            findTypesafeConventionsCatalogEntry(tomlFile, declarationPath)
        } ?: error("Cannot find $declarationPath in ${tomlFile.virtualFile.url}")
    }

    private suspend fun requireTomlCatalogKeySegment(
        versionCatalog: VersionCatalogCase,
        declarationPath: String,
    ): TomlKeySegment {
        val entry = requireTomlCatalogEntry(versionCatalog, declarationPath)
        return readAction {
            entry.key.segments.singleOrNull()
        } ?: error("Expected $declarationPath to use a single TOML key segment")
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

    @Suppress("UnstableApiUsage")
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
        syncedProject.assertConventionBuildCatalogAccessorGotoDeclarationResolvesToTomlEntry(
            scriptPath = projectRoot.resolve(testCase.conventionBuild.scriptPath),
            versionCatalog = testCase.versionCatalog,
            referenceText = "jupiter",
            expectedEntryText = "junit-jupiter",
        ) { sourceElement, offset ->
            syncedProject.resolveTargetsWithRegisteredGotoDeclarationHandlers(sourceElement, offset)
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionCatalogInConventionBuildCases")
    suspend fun `kotlin dsl catalog expression exposes local reference resolving to exact toml entry`(
        testCase: VersionCatalogInConventionBuildCase,
    ) {
        val projectRoot = projectPathFixture.get()
        syncedProject.assertConventionBuildKotlinCatalogReferenceResolvesToTomlEntry(
            scriptPath = projectRoot.resolve(testCase.conventionBuild.scriptPath),
            versionCatalog = testCase.versionCatalog,
            declarationPath = "junit.jupiter",
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("versionCatalogCases")
    suspend fun `kotlin dsl find usages distinguishes catalogs with the same alias`(
        versionCatalog: VersionCatalogCase,
    ) {
        val projectRoot = projectPathFixture.get()
        syncedProject.assertKotlinCatalogFindUsagesAreIsolatedToTargetCatalog(
            versionCatalog = versionCatalog,
            declarationPath = "usage.target",
            expectedExpressionText = "${versionCatalog.catalogName}.usage.target",
            expectedScriptPaths = kotlinDslConventionScriptPaths(projectRoot),
        )
    }

    @Test
    suspend fun `renaming toml alias updates all kotlin convention build usages`() {
        val projectRoot = projectPathFixture.get()
        syncedProject.renameKotlinCatalogAliasAndAssertUsages(
            versionCatalog = versionCatalogCasesForTypesafeConventions().single { it.catalogName == "libs" },
            oldDeclarationPath = "rename.target",
            newAliasName = "renamed-alias",
            newDeclarationPath = "renamed.alias",
            expectedScriptPaths = kotlinDslConventionScriptPaths(projectRoot),
        )
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
        writeJupiterCatalog(projectRoot, "gradle/libs.versions.toml", includeRenameAlias = true)
        writeJupiterCatalog(projectRoot, "gradle/customLibs.versions.toml")
        writeKotlinDslConventionBuild(projectRoot.resolve("buildSrc"), rootProjectName = null)
        writeKotlinDslConventionBuild(projectRoot.resolve("build-logic"), rootProjectName = "build-logic")
    }

    fun versionCatalogInConventionBuildCases(): List<VersionCatalogInConventionBuildCase> =
        kotlinDslVersionCatalogInConventionBuildCases()

    fun versionCatalogCases(): List<VersionCatalogCase> =
        versionCatalogCasesForTypesafeConventions()
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
    kotlinDslConventionBuildCases().flatMap { conventionBuild ->
        versionCatalogCasesForTypesafeConventions().map { versionCatalog ->
            VersionCatalogInConventionBuildCase(
                versionCatalog = versionCatalog,
                conventionBuild = conventionBuild,
            )
        }
    }

private fun kotlinDslConventionScriptPaths(projectRoot: Path): List<Path> =
    kotlinDslConventionBuildCases().map { projectRoot.resolve(it.scriptPath) }

private fun kotlinDslConventionBuildCases(): List<ConventionBuildCase> =
    listOf(
        ConventionBuildCase(
            name = "buildSrc precompiled plugin",
            buildPath = "buildSrc",
            scriptPath = "buildSrc/src/main/kotlin/repo.intellij-lib.gradle.kts",
        ),
        ConventionBuildCase(
            name = "buildSrc binary plugin",
            buildPath = "buildSrc",
            scriptPath = "buildSrc/src/main/kotlin/RepoConventionPlugin.kt",
        ),
        ConventionBuildCase(
            name = "build-logic precompiled plugin",
            buildPath = "build-logic",
            scriptPath = "build-logic/src/main/kotlin/repo.intellij-lib.gradle.kts",
        ),
        ConventionBuildCase(
            name = "build-logic binary plugin",
            buildPath = "build-logic",
            scriptPath = "build-logic/src/main/kotlin/RepoConventionPlugin.kt",
        ),
    )

private fun groovyDslVersionCatalogInConventionBuildCases(): List<VersionCatalogInConventionBuildCase> =
    listOf(
        ConventionBuildCase(
            name = "buildSrc",
            buildPath = "buildSrc",
            scriptPath = "buildSrc/src/main/groovy/repo.intellij-lib.gradle",
        ),
        ConventionBuildCase(
            name = "build-logic",
            buildPath = "build-logic",
            scriptPath = "build-logic/src/main/groovy/repo.intellij-lib.gradle",
        ),
    ).flatMap { conventionBuild ->
        versionCatalogCasesForTypesafeConventions().map { versionCatalog ->
            VersionCatalogInConventionBuildCase(
                versionCatalog = versionCatalog,
                conventionBuild = conventionBuild,
            )
        }
    }

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
    val sourceRoot = buildRoot.resolve("src/main/kotlin").createDirectories()
    sourceRoot.resolve("repo.intellij-lib.gradle.kts")
        .writeText(
            """
                plugins {
                    `java-library`
                }

                dependencies {
                    testImplementation(libs.junit.jupiter)
                    testImplementation(customLibs.junit.jupiter)
                    testImplementation(libs.usage.target)
                    testImplementation(customLibs.usage.target)
                    testImplementation(libs.rename.target)
                }
            """.trimIndent(),
        )
    sourceRoot.resolve("RepoConventionPlugin.kt").writeText(
        """
            import org.gradle.api.Plugin
            import org.gradle.api.Project

            class RepoConventionPlugin : Plugin<Project> {
                override fun apply(target: Project) {
                    with(target) {
                        dependencies.add("testImplementation", libs.junit.jupiter)
                        dependencies.add("testImplementation", customLibs.junit.jupiter)
                        dependencies.add("testImplementation", libs.usage.target)
                        dependencies.add("testImplementation", customLibs.usage.target)
                        dependencies.add("testImplementation", libs.rename.target)
                    }
                }
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

private fun writeJupiterCatalog(
    projectRoot: Path,
    catalogPath: String,
    includeRenameAlias: Boolean = false,
) {
    projectRoot.resolve(catalogPath).parent.createDirectories()
    projectRoot.resolve(catalogPath).writeText(
        buildString {
            appendLine(
                """
                    [versions]
                    junit-jupiter = "6.1.1"

                    [libraries]
                    junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit-jupiter" }
                    usage-target = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit-jupiter" }
                """.trimIndent(),
            )
            if (includeRenameAlias) {
                appendLine(
                    "rename-target = { module = \"org.junit.jupiter:junit-jupiter\", version.ref = \"junit-jupiter\" }",
                )
            }
        }.trimEnd(),
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
