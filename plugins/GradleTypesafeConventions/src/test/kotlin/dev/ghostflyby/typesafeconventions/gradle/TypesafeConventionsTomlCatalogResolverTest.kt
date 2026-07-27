/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.application.readAction
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlFileType

@TestApplication
internal class TypesafeConventionsTomlCatalogResolverTest {

    private val projectFixture = projectFixture(openAfterCreation = true)
    private val project by projectFixture

    @Test
    suspend fun `resolves all version catalog sections`() = readAction {
        val file = createTomlFile(
            """
                [versions]
                kotlin = "2.3.0"

                [libraries]
                kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }

                [bundles]
                kotlin = ["kotlin-stdlib"]

                [plugins]
                kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
            """.trimIndent(),
        )

        assertEquals("kotlin-stdlib", findTypesafeConventionsCatalogEntry(file, "kotlin.stdlib")?.key?.text)
        assertEquals("kotlin", findTypesafeConventionsCatalogEntry(file, "versions.kotlin")?.key?.text)
        assertEquals("kotlin", findTypesafeConventionsCatalogEntry(file, "bundles.kotlin")?.key?.text)
        assertEquals("kotlin-jvm", findTypesafeConventionsCatalogEntry(file, "plugins.kotlin.jvm")?.key?.text)
    }

    @Test
    suspend fun `matches equivalent separators and ignores case after separators`() = readAction {
        val file = createTomlFile(
            """
                [libraries]
                foo-Bar_baz.qux = { module = "example:library", version = "1.0" }
            """.trimIndent(),
        )

        assertEquals(
            "foo-Bar_baz.qux",
            findTypesafeConventionsCatalogEntry(file, "foo.bar.baz.qux")?.key?.text,
        )
    }

    @Test
    suspend fun `resolves top level dotted keys`() = readAction {
        val file = createTomlFile(
            """
                libraries.junit.jupiter = { module = "org.junit.jupiter:junit-jupiter", version = "6.1.1" }
            """.trimIndent(),
        )
        val entry = findTypesafeConventionsCatalogEntry(file, "junit.jupiter")

        assertEquals(
            "libraries.junit.jupiter",
            entry?.key?.text,
        )
        assertEquals(
            listOf("junit", "jupiter"),
            entry?.let {
                findTypesafeConventionsCatalogAliasSegments(
                    it,
                    TypesafeConventionsCatalogSection.LIBRARIES,
                ).mapNotNull { segment -> segment.name }
            },
        )
    }

    @Test
    suspend fun `resolves table dotted keys to individual alias segments`() = readAction {
        val file = createTomlFile(
            """
                [libraries]
                junit.jupiter = { module = "org.junit.jupiter:junit-jupiter", version = "6.1.1" }
            """.trimIndent(),
        )
        val entry = requireNotNull(findTypesafeConventionsCatalogEntry(file, "junit.jupiter"))

        assertEquals("junit.jupiter", entry.key.text)
        assertEquals(
            listOf("junit", "jupiter"),
            findTypesafeConventionsCatalogAliasSegments(
                entry,
                TypesafeConventionsCatalogSection.LIBRARIES,
            ).mapNotNull { segment -> segment.name },
        )
    }

    @Test
    suspend fun `resolves inline tables`() = readAction {
        val file = createTomlFile(
            """
                plugins = { kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version = "2.3.0" } }
            """.trimIndent(),
        )

        assertEquals(
            "kotlin-jvm",
            findTypesafeConventionsCatalogEntry(file, "plugins.kotlin.jvm")?.key?.text,
        )
    }

    @Test
    suspend fun `returns null when accessor has no matching entry`() = readAction {
        val file = createTomlFile(
            """
                [libraries]
                present = { module = "example:present", version = "1.0" }
            """.trimIndent(),
        )

        assertNull(findTypesafeConventionsCatalogEntry(file, "missing"))
        assertNull(findTypesafeConventionsCatalogEntry(file, "plugins.present"))
    }

    @Test
    suspend fun `builds renamed kotlin accessor expressions`() = readAction {
        val libraryExpression = KtPsiFactory(project).createExpression("libs.old.alias") as KtDotQualifiedExpression
        val pluginExpression =
            KtPsiFactory(project).createExpression("customLibs.plugins.old.alias") as KtDotQualifiedExpression

        assertEquals(
            "libs.new.alias",
            createTypesafeConventionsKotlinCatalogAccessorExpression(
                libraryExpression,
                TypesafeConventionsCatalogSection.LIBRARIES,
                "new-alias",
            ).text,
        )
        assertEquals(
            "customLibs.plugins.new.alias",
            createTypesafeConventionsKotlinCatalogAccessorExpression(
                pluginExpression,
                TypesafeConventionsCatalogSection.PLUGINS,
                "new_alias",
            ).text,
        )
    }

    @Test
    suspend fun `renames only the targeted dotted alias segment`() = readAction {
        assertDottedSegmentRenames(
            """
                [libraries]
                dotted.rename = { module = "example:library", version = "1.0" }
            """.trimIndent(),
            declarationPath = "dotted.rename",
            expressionText = "libs.dotted.rename",
        )
    }

    @Test
    suspend fun `renames only the targeted top level dotted alias segment`() = readAction {
        assertDottedSegmentRenames(
            """
                libraries.dotted.rename = { module = "example:library", version = "1.0" }
            """.trimIndent(),
            declarationPath = "dotted.rename",
            expressionText = "libs.dotted.rename",
        )
    }

    @Test
    suspend fun `one separator alias segment owns all matching kotlin selectors`() = readAction {
        val file = createTomlFile(
            """
                [libraries]
                foo-Bar_baz = { module = "example:library", version = "1.0" }
            """.trimIndent(),
        )
        val entry = requireNotNull(findTypesafeConventionsCatalogEntry(file, "foo.bar.baz"))
        val segments = findTypesafeConventionsCatalogAliasSegments(
            entry,
            TypesafeConventionsCatalogSection.LIBRARIES,
        )
        val expression =
            KtPsiFactory(project).createExpression("libs.foo.bar.baz") as KtDotQualifiedExpression
        val accessor = requireNotNull(expression.typesafeConventionsCatalogAccessor())
        val contexts = expression.createTypesafeConventionsCatalogReferenceContexts(
            accessor,
            segments,
            catalogUrl = null,
        )

        assertEquals(
            listOf("foo.bar.baz"),
            contexts.map { context ->
                expression.text.substring(
                    context.rangeInElement.startOffset,
                    context.rangeInElement.endOffset,
                )
            },
        )

        val renamed = TypesafeConventionsKotlinCatalogReference(expression, contexts.single())
            .handleElementRename("new-alias")

        assertEquals("libs.new.alias", renamed.text)
    }

    private fun assertDottedSegmentRenames(
        tomlText: String,
        declarationPath: String,
        expressionText: String,
    ) {
        val file = createTomlFile(tomlText)
        val entry = requireNotNull(findTypesafeConventionsCatalogEntry(file, declarationPath))
        val segments = findTypesafeConventionsCatalogAliasSegments(
            entry,
            TypesafeConventionsCatalogSection.LIBRARIES,
        )

        val expectedRanges = listOf("dotted", "rename")
        val expectedRenames = listOf("libs.renamed.rename", "libs.dotted.renamed")
        expectedRenames.forEachIndexed { segmentIndex, expectedExpression ->
            val expression =
                KtPsiFactory(project).createExpression(expressionText) as KtDotQualifiedExpression
            val accessor = requireNotNull(expression.typesafeConventionsCatalogAccessor())
            val contexts = expression.createTypesafeConventionsCatalogReferenceContexts(
                accessor,
                segments,
                catalogUrl = null,
            )
            assertEquals(
                expectedRanges,
                contexts.map { context ->
                    expression.text.substring(
                        context.rangeInElement.startOffset,
                        context.rangeInElement.endOffset,
                    )
                },
            )

            val renamed = TypesafeConventionsKotlinCatalogReference(expression, contexts[segmentIndex])
                .handleElementRename("renamed")
            assertEquals(expectedExpression, renamed.text)
        }
    }

    private fun createTomlFile(text: String): TomlFile =
        PsiFileFactory.getInstance(project)
            .createFileFromText("libs.versions.toml", TomlFileType, text) as TomlFile
}
