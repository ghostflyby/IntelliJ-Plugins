/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.externalSystem.util.ExternalSystemActivityKey
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.platform.backend.observation.trackActivity
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.storage.entities
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchRequestCollector
import com.intellij.psi.search.SearchSession
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
import kotlinx.coroutines.withTimeout
import org.gradle.tooling.model.BuildIdentifier
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.plugins.gradle.model.GradleLightBuild
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
import java.lang.reflect.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration.Companion.minutes

internal data class VersionCatalogCase(
    val catalogName: String,
    val catalogPath: String,
) {
    val expressionText: String
        get() = "$catalogName.junit.jupiter"

    override fun toString(): String = catalogName
}

private val GRADLE_SYNC_TIMEOUT = 2.minutes

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

internal data class CatalogAccessorCase(
    val name: String,
    val declarationPath: String,
    val referenceText: String,
    val expectedEntryText: String,
) {
    override fun toString(): String = name
}

internal data class CatalogAccessorInConventionBuildCase(
    val catalog: VersionCatalogCase,
    val conventionBuild: ConventionBuildCase,
    val accessor: CatalogAccessorCase,
) {
    override fun toString(): String = "${conventionBuild.name}: ${catalog.catalogName} ${accessor.name}"
}

internal data class CatalogAccessorInCatalogCase(
    val catalog: VersionCatalogCase,
    val accessor: CatalogAccessorCase,
) {
    override fun toString(): String = "${catalog.catalogName} ${accessor.name}"
}

internal data class CatalogRenameCase(
    val name: String,
    val oldDeclarationPath: String,
    val newAliasName: String,
    val newDeclarationPath: String,
) {
    override fun toString(): String = name
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
            Registry.get(CommonGradleProjectResolverExtension.GRADLE_VERSION_CATALOGS_DYNAMIC_SUPPORT)
                .setValue(true, project)
            syncGradleProject(projectRoot)
        } catch (throwable: Throwable) {
            projectJdk = null
            cleanupProjectJdk(sdk)
            throw throwable
        }
    }

    suspend fun createAndSyncAdditionalProject(
        additionalProjectRoot: Path,
        createProject: (Path) -> Unit,
    ) {
        checkNotNull(projectJdk) { "The primary Gradle project must be set up first" }
        createProject(additionalProjectRoot)
        syncGradleProject(additionalProjectRoot)
    }

    private suspend fun syncGradleProject(gradleProjectRoot: Path) {
        val modelFetchFuture = CompletableDeferred<Unit>()
        val importFuture = CompletableDeferred<Unit>()
        fun failSync(stage: String, throwable: Throwable) {
            val failure = IllegalStateException(
                "Gradle $stage failed for $gradleProjectRoot",
                throwable,
            )
            modelFetchFuture.completeExceptionally(failure)
            importFuture.completeExceptionally(failure)
        }
        @Suppress("UnstableApiUsage")
        project.messageBus.connect(project).subscribe(
            GradleSyncListener.TOPIC,
            object : GradleSyncListener {
                override fun onModelFetchCompleted(context: ProjectResolverContext) {
                    modelFetchFuture.complete(Unit)
                }

                override fun onModelFetchFailed(context: ProjectResolverContext, exception: Throwable) {
                    failSync("model fetch", exception)
                }
            },
        )
        @Suppress("CAST_NEVER_SUCCEEDS")
        project.messageBus.connect(project).subscribe(
            ProjectDataImportListener.TOPIC as Topic<ProjectDataImportListener>,
            object : ProjectDataImportListener {
                override fun onImportFailed(projectPath: String?, t: Throwable) {
                    failSync("project import for ${projectPath ?: "unknown project"}", t)
                }

                override fun onFinalTasksFinished(projectPath: String?) {
                    importFuture.complete(Unit)
                }
            },
        )

        project.trackActivity(ExternalSystemActivityKey) {
            linkAndSyncGradleProject(project, gradleProjectRoot.toString())
        }
        withTimeout(GRADLE_SYNC_TIMEOUT) {
            modelFetchFuture.await()
            importFuture.await()
        }
        project.service<TypesafeConventionsCatalogRefreshService>().awaitIdle()
        IndexingTestUtil.waitUntilIndexesAreReady(project)
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
        expressionText: String = versionCatalog.expressionText,
        resolveTargets: (PsiElement, Int) -> Array<PsiElement>?,
    ) {
        val tomlPath = projectRoot.resolve(versionCatalog.catalogPath).realPath()
        val conventionBuildScript = requirePsiFile(scriptPath)

        val resolvedTargets = readAction {
            val (sourceElement, offset) = findElementAtText(
                conventionBuildScript,
                expressionText,
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
            "Expected $referenceText in $expressionText to resolve to TOML entry $expectedEntryText. " +
                    "resolvedTargets=$realResolvedTargets ${workspaceModelState()} ${moduleGradleState()}",
        )
    }

    suspend fun assertConventionBuildKotlinCatalogReferencesResolveToTomlSegments(
        scriptPath: Path,
        versionCatalog: VersionCatalogCase,
        declarationPath: String,
        expressionText: String = versionCatalog.expressionText,
    ) {
        val expectedEntry = requireTomlCatalogEntry(versionCatalog, declarationPath)
        val expectedSegments = readAction {
            requireNotNull(findTypesafeConventionsTomlCatalogAlias(expectedEntry)).segments
        }
        val conventionBuildScript = requirePsiFile(scriptPath)
        val generatedEntrypointState = generatedEntrypointState()

        val (resolvedSegments, expectedTargets) = readAction {
            val expression = findKotlinCatalogExpression(conventionBuildScript, expressionText)
            val accessor = requireNotNull(expression.typesafeConventionsCatalogAccessor())
            val groups = expression.createTypesafeConventionsKotlinCatalogSelectorGroups(accessor, expectedSegments)
            val references = expression.references
                .filterIsInstance<TypesafeConventionsKotlinCatalogReference>()
                .sortedBy { it.rangeInElement.startOffset }
            assertEquals(
                accessor.aliasSelectorExpressions.size,
                references.size,
                "Expected $expressionText to expose one stable reference per Kotlin alias selector. " +
                        "references=${expression.references.map { it.javaClass.name }} " +
                        "catalogRootResolution=${expression.catalogRootResolutionState()} " +
                        "generatedEntrypoints=$generatedEntrypointState",
            )
            references.map { it.resolve() } to groups.flatMap { group ->
                List(group.selectorEndIndex - group.selectorStartIndex) { group.targetSegment }
            }
        }

        assertEquals(
            expectedTargets,
            resolvedSegments,
            "Expected every selector in $expressionText to resolve through its current TOML alias group.",
        )
    }

    suspend fun assertKotlinExpressionHasNoCustomCatalogReferences(
        scriptPath: Path,
        expressionText: String,
    ) {
        val script = requirePsiFile(scriptPath)
        readAction {
            val expression = findKotlinCatalogExpression(script, expressionText)
            val accessor = requireNotNull(expression.typesafeConventionsCatalogAccessor())
            val root = accessor.nameExpressions.first()
            assertNotNull(
                root.mainReference.resolve(),
                "Expected local root ${root.text} to resolve so the shadowing assertion exercises semantic provenance. " +
                        "catalogRootResolution=${expression.catalogRootResolutionState()}",
            )
            assertTrue(
                expression.references.none { it is TypesafeConventionsKotlinCatalogReference },
                "Expected $expressionText in ${script.name} to keep only its standard Kotlin references. " +
                        "references=${expression.references.map { it.javaClass.name }}",
            )
        }
    }

    suspend fun assertKotlinExpressionHasOnlyUnresolvedSoftCatalogReferences(
        scriptPath: Path,
        expressionText: String,
    ) {
        val script = requirePsiFile(scriptPath)
        readAction {
            val expression = findKotlinCatalogExpression(script, expressionText)
            val accessor = requireNotNull(expression.typesafeConventionsCatalogAccessor())
            val references = expression.references
                .filterIsInstance<TypesafeConventionsKotlinCatalogReference>()
            assertEquals(accessor.aliasSelectorExpressions.size, references.size)
            assertTrue(references.all(PsiReference::isSoft))
            assertTrue(
                references.all { it.resolve() == null },
                "Expected $expressionText to remain structurally referenced but have no TOML target.",
            )
        }
    }

    suspend fun assertExistingReferenceTracksPublishedCatalogChanges(
        scriptPath: Path,
        expressionText: String,
        selectorText: String,
        originalCatalog: VersionCatalogCase,
        remappedCatalog: VersionCatalogCase,
        declarationPath: String,
    ) {
        val script = requirePsiFile(scriptPath)
        val (expression, reference) = readAction {
            val expression = findKotlinCatalogExpression(script, expressionText)
            val reference = expression.references
                .filterIsInstance<TypesafeConventionsKotlinCatalogReference>()
                .single { candidate ->
                    expression.text.substring(
                        candidate.rangeInElement.startOffset,
                        candidate.rangeInElement.endOffset,
                    ) == selectorText
                }
            expression to reference
        }
        val originalTarget = requireTomlCatalogKeySegment(originalCatalog, declarationPath, 0)
        val remappedTarget = requireTomlCatalogKeySegment(remappedCatalog, declarationPath, 0)
        val indexService = project.service<TypesafeConventionsCatalogIndexService>()
        val originalIndex = readAction { indexService.currentIndex() }
        val contextUrl = readAction { requireNotNull(expression.containingFile.virtualFile?.url) }
        val matchedCatalog = requireNotNull(originalIndex.findCatalog(contextUrl, originalCatalog.catalogName))
        val remappedCatalogUrl = readAction {
            requireNotNull((remappedTarget.containingFile as TomlFile).virtualFile?.url)
        }
        val remappedIndex = TypesafeConventionsCatalogIndex.create(
            originalIndex.entries.map { entry ->
                if (entry == matchedCatalog) entry.copy(catalogUrl = remappedCatalogUrl) else entry
            },
        )
        val emptyIndex = TypesafeConventionsCatalogIndex.create(emptyList())

        try {
            assertSame(originalTarget, readAction { reference.resolve() })

            indexService.publishForTests(emptyIndex)
            assertNull(readAction { reference.resolve() }, "Missing mapping must invalidate the existing reference")

            indexService.publishForTests(originalIndex)
            assertSame(originalTarget, readAction { reference.resolve() })

            indexService.publishForTests(remappedIndex)
            assertSame(remappedTarget, readAction { reference.resolve() })

            indexService.publishForTests(emptyIndex)
            assertNull(readAction { reference.resolve() }, "Disabled or unlinked mapping must resolve to null")
        } finally {
            indexService.publishForTests(originalIndex)
        }
    }

    suspend fun assertExistingReferenceTracksTomlEditsWithoutSync(
        scriptPath: Path,
        expressionText: String,
        selectorText: String,
        versionCatalog: VersionCatalogCase,
        aliasText: String,
    ) {
        val script = requirePsiFile(scriptPath)
        val reference = readAction {
            val expression = findKotlinCatalogExpression(script, expressionText)
            expression.references
                .filterIsInstance<TypesafeConventionsKotlinCatalogReference>()
                .single { candidate ->
                    expression.text.substring(
                        candidate.rangeInElement.startOffset,
                        candidate.rangeInElement.endOffset,
                    ) == selectorText
                }
        }
        val tomlFile = requirePsiFile(projectRoot.resolve(versionCatalog.catalogPath)) as TomlFile
        val documentManager = PsiDocumentManager.getInstance(project)
        val document = readAction { requireNotNull(documentManager.getDocument(tomlFile)) }
        val originalText = document.text
        val replacementText = "changed-$aliasText"
        require(originalText.contains(aliasText))

        try {
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project) {
                    document.setText(originalText.replace(aliasText, replacementText))
                    documentManager.doPostponedOperationsAndUnblockDocument(document)
                    documentManager.commitDocument(document)
                }
            }
            assertNull(readAction { reference.resolve() })
        } finally {
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project) {
                    document.setText(originalText)
                    documentManager.doPostponedOperationsAndUnblockDocument(document)
                    documentManager.commitDocument(document)
                    FileDocumentManager.getInstance().saveDocumentAsIs(document)
                }
            }
        }

        assertNotNull(readAction { reference.resolve() })
    }

    suspend fun assertSuccessfulCommitRefreshesExternallyReplacedCatalog(
        scriptPath: Path,
        expressionText: String,
        selectorText: String,
        versionCatalog: VersionCatalogCase,
        aliasText: String,
    ) {
        val script = requirePsiFile(scriptPath)
        val reference = readAction {
            val expression = findKotlinCatalogExpression(script, expressionText)
            expression.references
                .filterIsInstance<TypesafeConventionsKotlinCatalogReference>()
                .single { candidate ->
                    expression.text.substring(
                        candidate.rangeInElement.startOffset,
                        candidate.rangeInElement.endOffset,
                    ) == selectorText
                }
        }
        val state = project.service<TypesafeConventionsGradleBuildState>()
        val projectPath = projectRoot.normalizedPathText()
        val buildUrls = state.state.buildUrlsByProjectPath.getValue(projectPath).toSet()
        val indexService = project.service<TypesafeConventionsCatalogIndexService>()
        val index = readAction { indexService.currentIndex() }
        val candidate = TypesafeConventionsGradleBuildCandidate(
            buildUrls = buildUrls,
            catalogUrls = index.entries.mapTo(sortedSetOf()) { it.catalogUrl },
        )
        val catalogPath = projectRoot.resolve(versionCatalog.catalogPath)
        val catalogPsiFile = requirePsiFile(catalogPath)
        val catalogDocument = readAction {
            FileDocumentManager.getInstance().getDocument(catalogPsiFile.virtualFile)
        }
        if (catalogDocument != null) {
            withContext(Dispatchers.EDT) {
                FileDocumentManager.getInstance().saveDocumentAsIs(catalogDocument)
            }
        }
        val originalText = withContext(Dispatchers.IO) { catalogPath.readText() }
        val replacementText = "changed-$aliasText"
        val externallyReplacedText = originalText.replace(aliasText, replacementText)
        require(externallyReplacedText != originalText) {
            "Expected $catalogPath to contain $aliasText before simulating an external replacement"
        }
        val originalGeneration = indexService.modificationCount

        try {
            withContext(Dispatchers.IO) {
                catalogPath.writeText(externallyReplacedText)
            }
            state.stageCandidate(projectPath, candidate)
            withContext(Dispatchers.EDT) {
                TypesafeConventionsProjectDataImportListener(project).onImportFinished(projectPath)
            }
            project.service<TypesafeConventionsCatalogRefreshService>().awaitIdle()
            val (documentText, psiText, resolvedTarget) = readAction {
                Triple(
                    catalogDocument?.text,
                    PsiManager.getInstance(project).findFile(catalogPsiFile.virtualFile)?.text,
                    reference.resolve(),
                )
            }
            val diskText = withContext(Dispatchers.IO) { catalogPath.readText() }
            assertNull(
                resolvedTarget,
                "Expected successful commit to expose externally replaced catalog content. " +
                        "diskUpdated=${diskText == externallyReplacedText}, " +
                        "documentUnsaved=${catalogDocument?.let(FileDocumentManager.getInstance()::isDocumentUnsaved)}, " +
                        "documentUpdated=${documentText == externallyReplacedText}, " +
                        "psiUpdated=${psiText == externallyReplacedText}",
            )
            assertEquals(
                originalGeneration + 1,
                indexService.modificationCount,
                "Refreshing a same-URL catalog must advance the shared index generation exactly once",
            )
        } finally {
            withContext(Dispatchers.IO) {
                catalogPath.writeText(originalText)
            }
            state.stageCandidate(projectPath, candidate)
            withContext(Dispatchers.EDT) {
                TypesafeConventionsProjectDataImportListener(project).onImportFinished(projectPath)
            }
            project.service<TypesafeConventionsCatalogRefreshService>().awaitIdle()
        }

        // The fixture is restored for the remaining tests. The feature assertion is the first refresh above:
        // an unchanged catalog URL exposed the externally replaced content after a successful commit.
    }

    @Suppress("UnstableApiUsage")
    suspend fun assertSyncContributorRejectsPartialWorkspaceMutation() {
        val storage = project.workspaceModel.currentSnapshot
        val validBuildEntity = storage.entities<GradleBuildEntity>()
            .first { build -> build.versionCatalogs.isNotEmpty() }
        val validBuildRoot = Path.of(URI(validBuildEntity.url.url))
        val catalogPath = Path.of(URI(validBuildEntity.versionCatalogs.first().url.url))
        val missingBuildRoot = projectRoot.resolve("missing-atomic-build")

        fun completeModel(catalogName: String): TypesafeConventionsCatalogModel =
            object : TypesafeConventionsCatalogModel {
                override val status = TypesafeConventionsCatalogModelStatus.COMPLETE
                override val catalogs = mapOf(catalogName to catalogPath.toString())
                override val diagnostics = emptyList<TypesafeConventionsCatalogDiagnostic>()
            }

        fun build(root: Path): GradleLightBuild {
            val buildIdentifier = BuildIdentifier { root.toFile() }
            return Proxy.newProxyInstance(
                GradleLightBuild::class.java.classLoader,
                arrayOf(GradleLightBuild::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "getBuildIdentifier" -> buildIdentifier
                    "equals" -> proxy === arguments?.singleOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "TestGradleLightBuild($root)"
                    else -> null
                }
            } as GradleLightBuild
        }

        val validBuild = build(validBuildRoot)
        val missingBuild = build(missingBuildRoot)
        val models = mapOf(
            validBuild to completeModel("atomicCandidate"),
            missingBuild to completeModel("missingCandidate"),
        )
        val context = Proxy.newProxyInstance(
            ProjectResolverContext::class.java.classLoader,
            arrayOf(ProjectResolverContext::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getAllBuilds" -> listOf(validBuild, missingBuild)
                "getBuildModel" -> models[arguments?.firstOrNull()]
                "getProject" -> project
                "getProjectPath", "getExternalProjectPath", "getIdeProjectPath" -> projectRoot.toString()
                "getRootBuild" -> validBuild
                "getNestedBuilds" -> listOf(missingBuild)
                "equals" -> proxy === arguments?.singleOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "AtomicFailureProjectResolverContext($projectRoot)"
                else -> null
            }
        } as ProjectResolverContext

        val result = TypesafeConventionsGradleSyncContributor().createProjectModel(context, storage)

        assertSame(storage, result)
        assertTrue(
            storage.entities<GradleBuildEntity>()
                .flatMap { build -> build.versionCatalogs.asSequence() }
                .none { catalog -> catalog.name == "atomicCandidate" },
            "A rejected later build must not publish catalogs prepared for an earlier build",
        )
        assertNull(project.service<TypesafeConventionsGradleBuildState>().commit(projectRoot.toString()))
    }

    suspend fun assertKotlinCatalogFindUsagesAreIsolatedToTargetCatalog(
        versionCatalog: VersionCatalogCase,
        declarationPath: String,
        expectedExpressionText: String,
        expectedScriptPaths: List<Path>,
    ) {
        val expectedEntry = requireTomlCatalogEntry(versionCatalog, declarationPath)
        val keySegment = readAction {
            requireNotNull(findTypesafeConventionsTomlCatalogAlias(expectedEntry)).segments.singleOrNull()
        } ?: error("Expected $declarationPath to use a single TOML alias segment")
        val expectedPsiFiles = expectedScriptPaths.map { requirePsiFile(it) }
        readAction {
            expectedPsiFiles.forEach { file ->
                val expression = findKotlinCatalogExpression(file, expectedExpressionText)
                val reference = expression.references
                    .filterIsInstance<TypesafeConventionsKotlinCatalogReference>()
                    .firstOrNull { reference -> reference.resolve() === keySegment }
                    ?: error("Expected $expectedExpressionText to expose a local reference in ${file.name}")
                assertSame(
                    keySegment,
                    reference.resolve(),
                    "Expected $expectedExpressionText to resolve to the searched TOML key segment in ${file.name}",
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
            .filter { (_, _, resolved) -> resolved === keySegment }
            .map { (path, text, _) -> path.realPath() to text }
            .toSet()
        val usageReferences = references.filterIsInstance<TypesafeConventionsKotlinCatalogUsageReference>()
        val usageReferenceDetails = readAction {
            usageReferences.map { reference ->
                val expression = reference.element
                "${expression.containingFile.virtualFile?.path}:${expression.textRange.startOffset}:" +
                        "${reference.rangeInElement}:selector=${reference.selectorIndex}"
            }
        }
        assertEquals(
            expectedScriptPaths.size,
            usageReferences.size,
            "Expected one deduplicated rename usage per Kotlin expression. " +
                    "references=${references.map { it.javaClass.name }}, usages=$usageReferenceDetails",
        )
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

    suspend fun assertKotlinCatalogSearchUsesBuildScopedWordRequest(
        versionCatalog: VersionCatalogCase,
        declarationPath: String,
        segmentIndex: Int,
        expectedSearchWord: String,
        expectedScriptPaths: List<Path>,
        unrelatedPath: Path,
    ) {
        val keySegment = requireTomlCatalogKeySegment(versionCatalog, declarationPath, segmentIndex)
        val expectedFiles = expectedScriptPaths.map { requirePsiFile(it).virtualFile }
        val unrelatedFile = requirePsiFile(unrelatedPath).virtualFile

        readAction {
            val optimizer = SearchRequestCollector(SearchSession(keySegment))
            val parameters = ReferencesSearch.SearchParameters(
                keySegment,
                GlobalSearchScope.projectScope(project),
                false,
                optimizer,
            )
            TypesafeConventionsKotlinCatalogReferencesSearcher().execute(parameters) { true }

            assertTrue(
                optimizer.takeCustomSearchActions().isEmpty(),
                "Expected catalog search to use the word index instead of a custom full-scope scan.",
            )
            val requests = optimizer.takeSearchRequests()
            assertEquals(1, requests.size, "Expected one indexed word request for the TOML segment.")
            val request = requests.single()
            assertEquals(expectedSearchWord, request.word)
            assertTrue(
                expectedFiles.all(request.searchScope::contains),
                "Expected the word request to include every related convention build source.",
            )
            assertFalse(
                request.searchScope.contains(unrelatedFile),
                "Expected the word request to exclude files outside the catalog build roots.",
            )

            val additionalScope = assertInstanceOf(
                GlobalSearchScope::class.java,
                TypesafeConventionsKotlinCatalogUseScopeEnlarger().getAdditionalUseScope(keySegment),
            )
            assertTrue(
                expectedFiles.all(additionalScope::contains),
                "Expected the TOML segment use scope to include every related convention build source.",
            )
            assertFalse(
                additionalScope.contains(unrelatedFile),
                "Expected the TOML segment use scope to exclude files outside the catalog build roots.",
            )
        }
    }

    suspend fun renameKotlinCatalogAliasAndAssertUsages(
        versionCatalog: VersionCatalogCase,
        oldDeclarationPath: String,
        newAliasName: String,
        newDeclarationPath: String,
        expectedScriptPaths: List<Path>,
        segmentIndex: Int = 0,
    ) {
        val keySegment = requireTomlCatalogKeySegment(versionCatalog, oldDeclarationPath, segmentIndex)
        @Suppress("UnstableApiUsage")
        writeIntentReadAction {
            RenameProcessor(project, keySegment, newAliasName, false, false).run()
        }

        val tomlFile = requirePsiFile(projectRoot.resolve(versionCatalog.catalogPath)) as TomlFile
        val scripts = expectedScriptPaths.map { requirePsiFile(it) }
        readAction {
            assertNull(findTomlCatalogAlias(tomlFile, oldDeclarationPath))
            val renamedAlias = requireNotNull(findTomlCatalogAlias(tomlFile, newDeclarationPath))
            val renamedSegment = renamedAlias.segments[segmentIndex]
            assertEquals(newAliasName, renamedSegment.name)
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

    suspend fun renameKotlinCatalogAliasFromUsageAndAssertUsages(
        versionCatalog: VersionCatalogCase,
        oldDeclarationPath: String,
        sourceExpressionText: String,
        sourceSelectorText: String,
        newAliasName: String,
        newDeclarationPath: String,
        expectedScriptPaths: List<Path>,
    ) {
        val sourceScript = requirePsiFile(expectedScriptPaths.first())
        val keySegment = readAction {
            val expression = findKotlinCatalogExpression(sourceScript, sourceExpressionText)
            val selectorOffset = sourceExpressionText.lastIndexOf(sourceSelectorText)
                .takeIf { it >= 0 }
                ?: error("Cannot find selector $sourceSelectorText in $sourceExpressionText")
            val caretOffset = selectorOffset + sourceSelectorText.length / 2
            expression.references
                .filterIsInstance<TypesafeConventionsKotlinCatalogReference>()
                .singleOrNull { reference -> reference.rangeInElement.containsOffset(caretOffset) }
                ?.resolve()
                ?: error(
                    "Expected $sourceExpressionText at $sourceSelectorText to resolve through a custom " +
                            "catalog reference. references=${expression.references.map { it.javaClass.name }}",
                )
        }

        @Suppress("UnstableApiUsage")
        writeIntentReadAction {
            RenameProcessor(project, keySegment, newAliasName, false, false).run()
        }

        val tomlFile = requirePsiFile(projectRoot.resolve(versionCatalog.catalogPath)) as TomlFile
        val scripts = expectedScriptPaths.map { requirePsiFile(it) }
        readAction {
            assertNull(findTomlCatalogAlias(tomlFile, oldDeclarationPath))
            val renamedAlias = requireNotNull(findTomlCatalogAlias(tomlFile, newDeclarationPath))
            assertEquals(newAliasName, renamedAlias.segments.single().name)
            val newExpression = "${versionCatalog.catalogName}.$newDeclarationPath"
            scripts.forEach { script ->
                assertTrue(
                    script.text.contains(newExpression),
                    "Expected Kotlin-initiated rename to update $newExpression in ${script.virtualFile.url}",
                )
                assertFalse(
                    script.text.contains(sourceExpressionText),
                    "Expected Kotlin-initiated rename to remove $sourceExpressionText from ${script.virtualFile.url}",
                )
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

    private fun KtDotQualifiedExpression.catalogRootResolutionState(): String {
        val accessor = typesafeConventionsCatalogAccessor()
            ?: return "accessor=null"
        val root = accessor.nameExpressions.firstOrNull()
            ?: return "root=null"
        val target = root.mainReference.resolve()
        return buildString {
            append("root=")
            append(root.text)
            append(", target=")
            append(target.describeCatalogRootResolutionElement())
            append(", navigation=")
            append(target?.navigationElement.describeCatalogRootResolutionElement())
            append(", original=")
            append(target?.originalElement.describeCatalogRootResolutionElement())
        }
    }

    private fun PsiElement?.describeCatalogRootResolutionElement(): String {
        this ?: return "null"
        return buildString {
            append(javaClass.name)
            append("(text=")
            append(text.take(240))
            append(", file=")
            append(containingFile?.virtualFile?.path)
            append(", receiver=")
            append((this@describeCatalogRootResolutionElement as? KtProperty)?.receiverTypeReference?.text)
            append(')')
        }
    }

    private suspend fun generatedEntrypointState(): String {
        val entrypointPaths = withContext(Dispatchers.IO) {
            Files.walk(projectRoot).use { paths ->
                paths.filter { path ->
                    val name = path.fileName?.toString().orEmpty()
                    Files.isRegularFile(path) && name.startsWith("EntrypointFor") && name.endsWith(".kt")
                }.toList()
            }
        }
        return readAction {
            val fileIndex = ProjectFileIndex.getInstance(project)
            entrypointPaths.joinToString(prefix = "[", postfix = "]") { path ->
                val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(path)
                buildString {
                    append(path)
                    append("(vfs=")
                    append(virtualFile != null)
                    append(", source=")
                    append(virtualFile?.let(fileIndex::isInSourceContent))
                    append(", module=")
                    append(virtualFile?.let(fileIndex::getModuleForFile)?.name)
                    append(", psi=")
                    append(virtualFile?.let { PsiManager.getInstance(project).findFile(it) }?.javaClass?.name)
                    append(')')
                }
            }
        }
    }

    private suspend fun requireTomlCatalogEntry(
        versionCatalog: VersionCatalogCase,
        declarationPath: String,
    ): TomlKeyValue {
        val tomlFile = requirePsiFile(projectRoot.resolve(versionCatalog.catalogPath)) as? TomlFile
            ?: error("Expected ${versionCatalog.catalogPath} to be a TOML PSI file")
        return readAction {
            findTomlCatalogAlias(tomlFile, declarationPath)?.entry
        } ?: error("Cannot find $declarationPath in ${tomlFile.virtualFile.url}")
    }

    private suspend fun requireTomlCatalogKeySegment(
        versionCatalog: VersionCatalogCase,
        declarationPath: String,
        segmentIndex: Int,
    ): TomlKeySegment {
        val entry = requireTomlCatalogEntry(versionCatalog, declarationPath)
        return readAction {
            requireNotNull(findTypesafeConventionsTomlCatalogAlias(entry)).segments.getOrNull(segmentIndex)
        } ?: error("Cannot find TOML alias segment $segmentIndex for $declarationPath")
    }

    private fun findTomlCatalogAlias(
        tomlFile: TomlFile,
        declarationPath: String,
    ): TypesafeConventionsTomlCatalogAlias? {
        val prefix = declarationPath.substringBefore('.', missingDelimiterValue = declarationPath)
        val section = TypesafeConventionsCatalogSection.fromAccessorPrefix(prefix)
            ?: TypesafeConventionsCatalogSection.LIBRARIES
        val aliasPath = if (section == TypesafeConventionsCatalogSection.LIBRARIES) {
            declarationPath
        } else {
            declarationPath.substringAfter('.', missingDelimiterValue = "")
        }
        return aliasPath.takeIf(String::isNotEmpty)
            ?.let { typesafeConventionsTomlCatalogAliasIndex(tomlFile).find(section, it) }
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

    @Test
    suspend fun `sync contributor rejects a partially prepared multi-build candidate atomically`() {
        syncedProject.assertSyncContributorRejectsPartialWorkspaceMutation()
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
    @MethodSource("catalogAccessorsInConventionBuildCases")
    suspend fun `kotlin dsl catalog sections resolve and navigate to exact toml segment`(
        testCase: CatalogAccessorInConventionBuildCase,
    ) {
        val projectRoot = projectPathFixture.get()
        val expressionText = "${testCase.catalog.catalogName}.${testCase.accessor.declarationPath}"
        val scriptPath = projectRoot.resolve(testCase.conventionBuild.scriptPath)
        syncedProject.assertConventionBuildKotlinCatalogReferencesResolveToTomlSegments(
            scriptPath = scriptPath,
            versionCatalog = testCase.catalog,
            declarationPath = testCase.accessor.declarationPath,
            expressionText = expressionText,
        )
        syncedProject.assertConventionBuildCatalogAccessorGotoDeclarationResolvesToTomlEntry(
            scriptPath = scriptPath,
            versionCatalog = testCase.catalog,
            referenceText = testCase.accessor.referenceText,
            expectedEntryText = testCase.accessor.expectedEntryText,
            expressionText = expressionText,
        ) { sourceElement, offset ->
            syncedProject.resolveTargetsWithRegisteredGotoDeclarationHandlers(sourceElement, offset)
        }
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("catalogAccessorsInCatalogCases")
    suspend fun `kotlin dsl find usages supports every catalog section`(
        testCase: CatalogAccessorInCatalogCase,
    ) {
        val projectRoot = projectPathFixture.get()
        syncedProject.assertKotlinCatalogFindUsagesAreIsolatedToTargetCatalog(
            versionCatalog = testCase.catalog,
            declarationPath = testCase.accessor.declarationPath,
            expectedExpressionText = "${testCase.catalog.catalogName}.${testCase.accessor.declarationPath}",
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("catalogRenameCases")
    suspend fun `renaming toml alias updates kotlin usages in every catalog section`(
        testCase: CatalogRenameCase,
    ) {
        val projectRoot = projectPathFixture.get()
        syncedProject.renameKotlinCatalogAliasAndAssertUsages(
            versionCatalog = versionCatalogCasesForTypesafeConventions().single { it.catalogName == "libs" },
            oldDeclarationPath = testCase.oldDeclarationPath,
            newAliasName = testCase.newAliasName,
            newDeclarationPath = testCase.newDeclarationPath,
            expectedScriptPaths = kotlinDslConventionScriptPaths(projectRoot),
        )
    }

    @Test
    suspend fun `renaming kotlin usage from junit selector updates toml and all usages`() {
        val projectRoot = projectPathFixture.get()
        syncedProject.renameKotlinCatalogAliasFromUsageAndAssertUsages(
            versionCatalog = versionCatalogCasesForTypesafeConventions().single { it.catalogName == "libs" },
            oldDeclarationPath = "rename.from.junit",
            sourceExpressionText = "libs.rename.from.junit",
            sourceSelectorText = "junit",
            newAliasName = "renamed-from-junit",
            newDeclarationPath = "renamed.from.junit",
            expectedScriptPaths = kotlinDslConventionScriptPaths(projectRoot),
        )
    }

    @Test
    suspend fun `renaming kotlin usage from jupiter selector updates toml and all usages`() {
        val projectRoot = projectPathFixture.get()
        syncedProject.renameKotlinCatalogAliasFromUsageAndAssertUsages(
            versionCatalog = versionCatalogCasesForTypesafeConventions().single { it.catalogName == "libs" },
            oldDeclarationPath = "rename.from.jupiter",
            sourceExpressionText = "libs.rename.from.jupiter",
            sourceSelectorText = "jupiter",
            newAliasName = "renamed-from-jupiter",
            newDeclarationPath = "renamed.from.jupiter",
            expectedScriptPaths = kotlinDslConventionScriptPaths(projectRoot),
        )
    }

    @Test
    suspend fun `separator alias maps multiple kotlin selectors to one toml segment`() {
        val projectRoot = projectPathFixture.get()
        val versionCatalog = versionCatalogCasesForTypesafeConventions().single { it.catalogName == "libs" }
        syncedProject.assertConventionBuildKotlinCatalogReferencesResolveToTomlSegments(
            scriptPath = kotlinDslConventionScriptPaths(projectRoot).first(),
            versionCatalog = versionCatalog,
            declarationPath = "dotted.rename",
            expressionText = "libs.dotted.rename",
        )
    }

    @Test
    suspend fun `same kotlin reference tracks missing present remapped and disabled catalog mappings`() {
        val projectRoot = projectPathFixture.get()
        val catalogs = versionCatalogCasesForTypesafeConventions()
        syncedProject.assertExistingReferenceTracksPublishedCatalogChanges(
            scriptPath = kotlinDslConventionScriptPaths(projectRoot).first(),
            expressionText = "libs.usage.target",
            selectorText = "target",
            originalCatalog = catalogs.single { it.catalogName == "libs" },
            remappedCatalog = catalogs.single { it.catalogName == "customLibs" },
            declarationPath = "usage.target",
        )
    }

    @Test
    suspend fun `same kotlin reference observes toml edits without gradle sync`() {
        val projectRoot = projectPathFixture.get()
        syncedProject.assertExistingReferenceTracksTomlEditsWithoutSync(
            scriptPath = kotlinDslConventionScriptPaths(projectRoot).first(),
            expressionText = "libs.usage.target",
            selectorText = "target",
            versionCatalog = versionCatalogCasesForTypesafeConventions().single { it.catalogName == "libs" },
            aliasText = "usage-target",
        )
    }

    @Test
    suspend fun `successful commit refreshes externally replaced same url catalog`() {
        val projectRoot = projectPathFixture.get()
        syncedProject.assertSuccessfulCommitRefreshesExternallyReplacedCatalog(
            scriptPath = kotlinDslConventionScriptPaths(projectRoot).first(),
            expressionText = "libs.usage.target",
            selectorText = "target",
            versionCatalog = versionCatalogCasesForTypesafeConventions().single { it.catalogName == "libs" },
            aliasText = "usage-target",
        )
    }

    @Test
    suspend fun `custom build directory contains generated entrypoints used by navigation`() {
        val projectRoot = projectPathFixture.get()
        val entrypoints = withContext(Dispatchers.IO) {
            Files.walk(projectRoot).use { paths ->
                paths.filter { path ->
                    Files.isRegularFile(path) &&
                            path.fileName.toString().startsWith("EntrypointFor") &&
                            path.fileName.toString().endsWith(".kt")
                }.toList()
            }
        }

        assertTrue(entrypoints.isNotEmpty())
        assertTrue(
            entrypoints.all { path -> path.toString().replace('\\', '/').contains("/custom-build/") },
            "Expected every generated entrypoint to use the custom convention-build output: $entrypoints",
        )
    }

    @Test
    suspend fun `local catalog shaped expression does not expose custom reference`() {
        val projectRoot = projectPathFixture.get()
        syncedProject.assertKotlinExpressionHasNoCustomCatalogReferences(
            scriptPath = projectRoot.resolve("buildSrc/src/main/kotlin/LocalShadow.kt"),
            expressionText = "libs.usage.target",
        )
    }

    @Test
    suspend fun `non Project extension does not expose custom reference`() {
        val projectRoot = projectPathFixture.get()
        syncedProject.assertKotlinExpressionHasNoCustomCatalogReferences(
            scriptPath = projectRoot.resolve("buildSrc/src/main/kotlin/NonProjectExtension.kt"),
            expressionText = "libs.usage.target",
        )
    }

    @Test
    suspend fun `unrelated Project extension does not expose custom reference`() {
        val projectRoot = projectPathFixture.get()
        syncedProject.assertKotlinExpressionHasNoCustomCatalogReferences(
            scriptPath = projectRoot.resolve("buildSrc/src/main/kotlin/ProjectExtensionShadow.kt"),
            expressionText = "libs.usage.target",
        )
    }

    @Test
    suspend fun `programmatic only alias exposes only unresolved soft toml references`() {
        val projectRoot = projectPathFixture.get()
        syncedProject.assertKotlinExpressionHasOnlyUnresolvedSoftCatalogReferences(
            scriptPath = kotlinDslConventionScriptPaths(projectRoot).first(),
            expressionText = "customLibs.programmatic.only",
        )
    }

    @Test
    suspend fun `kotlin find usages registers a build scoped word request`() {
        val projectRoot = projectPathFixture.get()
        syncedProject.assertKotlinCatalogSearchUsesBuildScopedWordRequest(
            versionCatalog = versionCatalogCasesForTypesafeConventions().single { it.catalogName == "libs" },
            declarationPath = "usage.target",
            segmentIndex = 0,
            expectedSearchWord = "target",
            expectedScriptPaths = kotlinDslConventionScriptPaths(projectRoot),
            unrelatedPath = projectRoot.resolve("build.gradle.kts"),
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
                            library("programmatic-only", "org.junit.jupiter", "junit-jupiter").version("6.1.1")
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
                    id("buildlogic.fixture")
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
        projectRoot.resolve("build-logic/src/main/kotlin/buildlogic.fixture.gradle.kts").writeText("")
    }

    fun versionCatalogInConventionBuildCases(): List<VersionCatalogInConventionBuildCase> =
        kotlinDslVersionCatalogInConventionBuildCases()

    fun versionCatalogCases(): List<VersionCatalogCase> =
        versionCatalogCasesForTypesafeConventions()

    fun catalogAccessorsInConventionBuildCases(): List<CatalogAccessorInConventionBuildCase> =
        kotlinDslConventionBuildCases().flatMap { conventionBuild ->
            versionCatalogCasesForTypesafeConventions().flatMap { catalog ->
                catalogAccessorCases().map { accessor ->
                    CatalogAccessorInConventionBuildCase(catalog, conventionBuild, accessor)
                }
            }
        }


    fun catalogAccessorsInCatalogCases(): List<CatalogAccessorInCatalogCase> =
        versionCatalogCasesForTypesafeConventions().flatMap { catalog ->
            catalogAccessorCases().map { accessor -> CatalogAccessorInCatalogCase(catalog, accessor) }
        }

    fun catalogRenameCases(): List<CatalogRenameCase> =
        listOf(
            CatalogRenameCase("libraries", "rename.library", "renamed-library", "renamed.library"),
            CatalogRenameCase(
                "versions",
                "versions.rename.version",
                "renamed-version",
                "versions.renamed.version",
            ),
            CatalogRenameCase(
                "bundles",
                "bundles.rename.bundle",
                "renamed-bundle",
                "bundles.renamed.bundle",
            ),
            CatalogRenameCase(
                "plugins",
                "plugins.rename.plugin",
                "renamed-plugin",
                "plugins.renamed.plugin",
            ),
        )
}

@TestApplication
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class MultipleLinkedGradleRootsTypesafeConventionsSyncTest {

    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private lateinit var syncedProject: GradleTypesafeConventionsSyncedProject
    private lateinit var firstRoot: Path
    private lateinit var secondRoot: Path

    @BeforeAll
    suspend fun setUp() {
        val fixtureRoot = projectPathFixture.get()
        firstRoot = fixtureRoot.resolve("root-a")
        secondRoot = fixtureRoot.resolve("root-b")
        syncedProject = GradleTypesafeConventionsSyncedProject(
            project = project,
            projectRoot = firstRoot,
            createGradleProject = { root -> writeSingleLinkedGradleRoot(root, "root-a") },
        )
        syncedProject.setUp()
        syncedProject.createAndSyncAdditionalProject(secondRoot) { root ->
            writeSingleLinkedGradleRoot(root, "root-b")
        }
    }

    @AfterAll
    suspend fun tearDown() {
        syncedProject.tearDown()
    }

    @Test
    fun `sequential linked root sync preserves both committed states`() {
        val state = project.service<TypesafeConventionsGradleBuildState>()
        val expectedProjectPaths = setOf(firstRoot.normalizedPathText(), secondRoot.normalizedPathText())

        assertEquals(expectedProjectPaths, state.state.buildUrlsByProjectPath.keys)
        expectedProjectPaths.forEach { projectPath ->
            val buildUrls = state.state.buildUrlsByProjectPath.getValue(projectPath)
            assertTrue(
                buildUrls.any { it.contains("$projectPath/buildSrc") },
                "Expected $projectPath to retain its typesafe-conventions build. state=${state.state}",
            )
        }
    }
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
    @MethodSource("catalogAccessorsInConventionBuildCases")
    suspend fun `groovy dsl catalog sections navigate to toml entries`(
        testCase: CatalogAccessorInConventionBuildCase,
    ) {
        val projectRoot = projectPathFixture.get()
        syncedProject.assertConventionBuildCatalogAccessorGotoDeclarationResolvesToTomlEntry(
            scriptPath = projectRoot.resolve(testCase.conventionBuild.scriptPath),
            versionCatalog = testCase.catalog,
            referenceText = testCase.accessor.referenceText,
            expectedEntryText = testCase.accessor.expectedEntryText,
            expressionText = "${testCase.catalog.catalogName}.${testCase.accessor.declarationPath}",
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
        writeMixedJupiterCatalog(projectRoot)
        writeGroovyDslConventionBuild(projectRoot.resolve("buildSrc"), rootProjectName = null)
        writeGroovyDslConventionBuild(projectRoot.resolve("build-logic"), rootProjectName = "build-logic")
    }

    fun versionCatalogInConventionBuildCases(): List<VersionCatalogInConventionBuildCase> =
        groovyDslVersionCatalogInConventionBuildCases()

    fun catalogAccessorsInConventionBuildCases(): List<CatalogAccessorInConventionBuildCase> =
        groovyDslConventionBuildCases().flatMap { conventionBuild ->
            versionCatalogCasesForTypesafeConventions().flatMap { catalog ->
                catalogAccessorCases().map { accessor ->
                    val shapedAccessor = if (catalog.catalogName == "customLibs") {
                        when (accessor.name) {
                            "libraries" -> accessor.copy(expectedEntryText = "libraries.junit-jupiter")
                            "versions" -> accessor.copy(expectedEntryText = "versions.junit-jupiter")
                            else -> accessor
                        }
                    } else {
                        accessor
                    }
                    CatalogAccessorInConventionBuildCase(catalog, conventionBuild, shapedAccessor)
                }
            }
        }
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
    groovyDslConventionBuildCases().flatMap { conventionBuild ->
        versionCatalogCasesForTypesafeConventions().map { versionCatalog ->
            VersionCatalogInConventionBuildCase(
                versionCatalog = versionCatalog,
                conventionBuild = conventionBuild,
            )
        }
    }

private fun groovyDslConventionBuildCases(): List<ConventionBuildCase> =
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

private fun catalogAccessorCases(): List<CatalogAccessorCase> =
    listOf(
        CatalogAccessorCase(
            name = "libraries",
            declarationPath = "junit.jupiter",
            referenceText = "jupiter",
            expectedEntryText = "junit-jupiter",
        ),
        CatalogAccessorCase(
            name = "versions",
            declarationPath = "versions.junit.jupiter",
            referenceText = "jupiter",
            expectedEntryText = "junit-jupiter",
        ),
        CatalogAccessorCase(
            name = "bundles",
            declarationPath = "bundles.junit.bundle",
            referenceText = "bundle",
            expectedEntryText = "junit-bundle",
        ),
        CatalogAccessorCase(
            name = "plugins",
            declarationPath = "plugins.kotlin.jvm",
            referenceText = "jvm",
            expectedEntryText = "kotlin-jvm",
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

            layout.buildDirectory = file("custom-build")
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
                    testImplementation(libs.rename.library)
                    testImplementation(libs.rename.from.junit)
                    testImplementation(libs.rename.from.jupiter)
                    testImplementation(libs.dotted.rename)
                    testImplementation(customLibs.programmatic.only)
                }

                val libsVersion = libs.versions.junit.jupiter
                val libsBundle = libs.bundles.junit.bundle
                val libsPlugin = libs.plugins.kotlin.jvm
                val customLibsVersion = customLibs.versions.junit.jupiter
                val customLibsBundle = customLibs.bundles.junit.bundle
                val customLibsPlugin = customLibs.plugins.kotlin.jvm
                val renameVersion = libs.versions.rename.version
                val renameBundle = libs.bundles.rename.bundle
                val renamePlugin = libs.plugins.rename.plugin
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
                        dependencies.add("testImplementation", libs.rename.library)
                        dependencies.add("testImplementation", libs.rename.from.junit)
                        dependencies.add("testImplementation", libs.rename.from.jupiter)
                        dependencies.add("testImplementation", libs.dotted.rename)
                        dependencies.add("testImplementation", customLibs.programmatic.only)
                        val libsVersion = libs.versions.junit.jupiter
                        val libsBundle = libs.bundles.junit.bundle
                        val libsPlugin = libs.plugins.kotlin.jvm
                        val customLibsVersion = customLibs.versions.junit.jupiter
                        val customLibsBundle = customLibs.bundles.junit.bundle
                        val customLibsPlugin = customLibs.plugins.kotlin.jvm
                        val renameVersion = libs.versions.rename.version
                        val renameBundle = libs.bundles.rename.bundle
                        val renamePlugin = libs.plugins.rename.plugin
                    }
                }
            }
        """.trimIndent(),
    )
    sourceRoot.resolve("LocalShadow.kt").writeText(
        """
            private class LocalCatalog {
                val usage = LocalUsage()
            }

            private class LocalUsage {
                val target = "local"
            }

            private fun localTarget(): String {
                val libs = LocalCatalog()
                return libs.usage.target
            }
        """.trimIndent(),
    )
    sourceRoot.resolve("NonProjectExtension.kt").writeText(
        """
            private class NonProject

            private class NonProjectCatalog {
                val usage = NonProjectUsage()
            }

            private class NonProjectUsage {
                val target = "not a Gradle Project extension"
            }

            private val NonProject.libs: NonProjectCatalog
                get() = NonProjectCatalog()

            private fun nonProjectTarget(): String = with(NonProject()) {
                libs.usage.target
            }
        """.trimIndent(),
    )
    sourceRoot.resolve("ProjectExtensionShadow.kt").writeText(
        """
            package fixture.shadow

            import org.gradle.api.Project

            private class ProjectExtensionCatalog {
                val usage = ProjectExtensionUsage()
            }

            private class ProjectExtensionUsage {
                val target = "not a generated typesafe-conventions entrypoint"
            }

            private val Project.libs: ProjectExtensionCatalog
                get() = ProjectExtensionCatalog()

            private fun projectExtensionTarget(project: Project): String = with(project) {
                libs.usage.target
            }
        """.trimIndent(),
    )
}

private fun writeSingleLinkedGradleRoot(
    projectRoot: Path,
    rootProjectName: String,
) {
    projectRoot.createDirectories()
    copyGradleWrapper(projectRoot)
    projectRoot.resolve("settings.gradle.kts").writeText(
        """
            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    mavenCentral()
                }
            }

            rootProject.name = "$rootProjectName"
        """.trimIndent(),
    )
    projectRoot.resolve("build.gradle.kts").writeText(
        """
            plugins {
                java
                id("linked.fixture")
            }
        """.trimIndent(),
    )
    writeJupiterCatalog(projectRoot, "gradle/libs.versions.toml")

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
    buildSrc.resolve("src/main/kotlin").createDirectories()
        .resolve("linked.fixture.gradle.kts")
        .writeText(
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

                def libsVersion = libs.versions.junit.jupiter
                def libsBundle = libs.bundles.junit.bundle
                def libsPlugin = libs.plugins.kotlin.jvm
                def customVersion = customLibs.versions.junit.jupiter
                def customBundle = customLibs.bundles.junit.bundle
                def customPlugin = customLibs.plugins.kotlin.jvm
            """.trimIndent(),
        )
}

private fun copyGradleWrapper(projectRoot: Path) {
    val repositoryRoot = findRepositoryRoot()
    listOf(
        "gradlew",
        "gradlew.bat",
        "gradle/wrapper/gradle-wrapper.jar",
        "gradle/wrapper/gradle-wrapper.properties",
    ).forEach { relativePath ->
        val target = projectRoot.resolve(relativePath)
        target.parent?.createDirectories()
        Files.copy(repositoryRoot.resolve(relativePath), target, StandardCopyOption.COPY_ATTRIBUTES)
    }
}

private fun writeJupiterCatalog(
    projectRoot: Path,
    catalogPath: String,
    includeRenameAlias: Boolean = false,
) {
    projectRoot.resolve(catalogPath).parent.createDirectories()
    projectRoot.resolve(catalogPath).writeText(
        buildString {
            appendLine("[versions]")
            appendLine("junit-jupiter = \"6.1.1\"")
            if (includeRenameAlias) {
                appendLine("rename-version = \"6.1.1\"")
            }
            appendLine()
            appendLine("[libraries]")
            appendLine(
                "junit-jupiter = { module = \"org.junit.jupiter:junit-jupiter\", version.ref = \"junit-jupiter\" }",
            )
            appendLine(
                "usage-target = { module = \"org.junit.jupiter:junit-jupiter\", version.ref = \"junit-jupiter\" }",
            )
            appendLine(
                "dotted-rename = { module = \"org.junit.jupiter:junit-jupiter\", version.ref = \"junit-jupiter\" }",
            )
            if (includeRenameAlias) {
                appendLine(
                    "rename-target = { module = \"org.junit.jupiter:junit-jupiter\", version.ref = \"junit-jupiter\" }",
                )
                appendLine(
                    "rename-library = { module = \"org.junit.jupiter:junit-jupiter\", version.ref = \"junit-jupiter\" }",
                )
                appendLine(
                    "rename-from-junit = { module = \"org.junit.jupiter:junit-jupiter\", version.ref = \"junit-jupiter\" }",
                )
                appendLine(
                    "rename-from-jupiter = { module = \"org.junit.jupiter:junit-jupiter\", version.ref = \"junit-jupiter\" }",
                )
            }
            appendLine()
            appendLine("[bundles]")
            appendLine("junit-bundle = [\"junit-jupiter\"]")
            if (includeRenameAlias) {
                appendLine("rename-bundle = [\"junit-jupiter\"]")
            }
            appendLine()
            appendLine("[plugins]")
            appendLine("kotlin-jvm = { id = \"org.jetbrains.kotlin.jvm\", version = \"2.3.0\" }")
            if (includeRenameAlias) {
                appendLine("rename-plugin = { id = \"org.jetbrains.kotlin.jvm\", version = \"2.3.0\" }")
            }
        }.trimEnd(),
    )
}

private fun writeMixedJupiterCatalog(projectRoot: Path) {
    val catalogPath = projectRoot.resolve("gradle/customLibs.versions.toml")
    catalogPath.parent.createDirectories()
    catalogPath.writeText(
        """
            versions.junit-jupiter = "6.1.1"
            libraries.junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit-jupiter" }
            bundles = { junit-bundle = ["junit-jupiter"] }
            plugins = { kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version = "2.3.0" } }
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

private fun Path.normalizedPathText(): String =
    toAbsolutePath().normalize().toString().replace('\\', '/')
