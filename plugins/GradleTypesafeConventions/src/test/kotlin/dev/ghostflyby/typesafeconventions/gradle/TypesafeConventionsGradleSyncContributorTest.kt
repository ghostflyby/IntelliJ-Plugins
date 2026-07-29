/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.components.service
import com.intellij.platform.workspace.storage.ImmutableEntityStorage
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import kotlinx.coroutines.runBlocking
import org.gradle.tooling.model.BuildIdentifier
import org.jetbrains.plugins.gradle.model.GradleLightBuild
import org.jetbrains.plugins.gradle.service.project.ProjectResolverContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.concurrent.CancellationException

@TestApplication
internal class TypesafeConventionsGradleSyncContributorTest {

    private val projectFixture = projectFixture(openAfterCreation = true)
    private val project by projectFixture
    private val buildPathFixture = tempPathFixture()

    @Test
    fun `missing Gradle build entity rejects candidate atomically`() = runBlocking {
        val buildRoot = buildPathFixture.get()
        val model = TestCatalogModel(
            status = TypesafeConventionsCatalogModelStatus.COMPLETE,
            catalogs = mapOf("libs" to buildRoot.resolve("libs.versions.toml").toString()),
        )
        val context = projectResolverContext(buildRoot, model)
        val storage = ImmutableEntityStorage.empty()

        val result = TypesafeConventionsGradleSyncContributor().createProjectModel(context, storage)

        assertSame(storage, result)
        assertTrue(
            project.service<TypesafeConventionsGradleBuildState>().pendingProjectPaths().isEmpty(),
            "A candidate without its Gradle build entity must not be staged",
        )
        assertEquals(
            TypesafeConventionsGradleProjectPathHealth.STALE,
            project.service<TypesafeConventionsGradleBuildState>().projectPathHealth(buildRoot.toString()),
        )
    }

    @Test
    fun `cancelled import discards pending candidate and preserves last known good`() {
        val state = project.service<TypesafeConventionsGradleBuildState>()
        val projectPath = buildPathFixture.get().toString()
        state.stageCandidate(
            projectPath,
            TypesafeConventionsGradleBuildCandidate(setOf("file:///old"), emptySet()),
        )
        state.commit(projectPath)
        state.stageCandidate(
            projectPath,
            TypesafeConventionsGradleBuildCandidate(setOf("file:///new"), emptySet()),
        )

        TypesafeConventionsProjectDataImportListener(project).onImportFailed(
            projectPath,
            CancellationException("cancelled"),
        )

        assertEquals(setOf("file:///old"), state.committedBuildUrls())
        assertTrue(state.pendingProjectPaths().isEmpty())
        assertEquals(TypesafeConventionsGradleProjectPathHealth.STALE, state.projectPathHealth(projectPath))
    }

    @Test
    fun `null import path commits every pending linked root`() {
        val state = project.service<TypesafeConventionsGradleBuildState>()
        val root = buildPathFixture.get()
        state.stageCandidate(
            root.resolve("root-a").toString(),
            TypesafeConventionsGradleBuildCandidate(setOf("file:///root-a"), emptySet()),
        )
        state.stageCandidate(
            root.resolve("root-b").toString(),
            TypesafeConventionsGradleBuildCandidate(setOf("file:///root-b"), emptySet()),
        )

        TypesafeConventionsProjectDataImportListener(project).onImportFinished(null)

        assertEquals(setOf("file:///root-a", "file:///root-b"), state.committedBuildUrls())
        assertTrue(state.pendingProjectPaths().isEmpty())
    }

    private fun projectResolverContext(
        buildRoot: Path,
        model: TypesafeConventionsCatalogModel,
    ): ProjectResolverContext {
        val buildIdentifier = object : BuildIdentifier {
            override fun getRootDir() = buildRoot.toFile()
        }
        val build = Proxy.newProxyInstance(
            GradleLightBuild::class.java.classLoader,
            arrayOf(GradleLightBuild::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getBuildIdentifier" -> buildIdentifier
                "equals" -> proxy === arguments?.singleOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "TestGradleLightBuild($buildRoot)"
                else -> defaultValue(method.returnType)
            }
        } as GradleLightBuild

        return Proxy.newProxyInstance(
            ProjectResolverContext::class.java.classLoader,
            arrayOf(ProjectResolverContext::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getAllBuilds" -> listOf(build)
                "getBuildModel" -> model
                "getProject" -> project
                "getProjectPath", "getExternalProjectPath", "getIdeProjectPath" -> buildRoot.toString()
                "getRootBuild" -> build
                "getNestedBuilds" -> emptyList<GradleLightBuild>()
                "equals" -> proxy === arguments?.singleOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "TestProjectResolverContext($buildRoot)"
                else -> defaultValue(method.returnType)
            }
        } as ProjectResolverContext
    }

    private fun defaultValue(returnType: Class<*>): Any? =
        when (returnType) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> '\u0000'
            else -> null
        }

    private data class TestCatalogModel(
        override val status: TypesafeConventionsCatalogModelStatus,
        override val catalogs: Map<String, String>,
        override val diagnostics: List<TypesafeConventionsCatalogDiagnostic> = emptyList(),
    ) : TypesafeConventionsCatalogModel {
        override val enabled: Boolean
            get() = status != TypesafeConventionsCatalogModelStatus.DISABLED
    }
}
