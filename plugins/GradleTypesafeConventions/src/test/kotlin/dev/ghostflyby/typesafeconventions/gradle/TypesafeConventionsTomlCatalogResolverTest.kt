/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.moduleFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.psiFileFixture
import com.intellij.testFramework.junit5.fixture.sourceRootFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlFileType

@TestApplication
internal class TypesafeConventionsTomlCatalogResolverTest {

    private val projectFixture = projectFixture(openAfterCreation = true)
    private val project by projectFixture
    private val moduleFixture = projectFixture.moduleFixture()
    private val sourceRootFixture = moduleFixture.sourceRootFixture()
    private val cachedTomlFileFixture = sourceRootFixture.psiFileFixture(
        "cached.versions.toml",
        """
            [libraries]
            before = { module = "example:before", version = "1.0" }
        """.trimIndent(),
    )
    private val cachedTomlFile by cachedTomlFileFixture

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

        assertEquals("kotlin-stdlib", findCatalogEntry(file, "kotlin.stdlib")?.key?.text)
        assertEquals("kotlin", findCatalogEntry(file, "versions.kotlin")?.key?.text)
        assertEquals("kotlin", findCatalogEntry(file, "bundles.kotlin")?.key?.text)
        assertEquals("kotlin-jvm", findCatalogEntry(file, "plugins.kotlin.jvm")?.key?.text)
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
            findCatalogEntry(file, "foo.bar.baz.qux")?.key?.text,
        )
    }

    @Test
    suspend fun `resolves top level dotted keys`() = readAction {
        val file = createTomlFile(
            """
                libraries.junit.jupiter = { module = "org.junit.jupiter:junit-jupiter", version = "6.1.1" }
            """.trimIndent(),
        )
        val entry = findCatalogEntry(file, "junit.jupiter")

        assertEquals(
            "libraries.junit.jupiter",
            entry?.key?.text,
        )
        assertEquals(
            listOf("junit", "jupiter"),
            entry?.let(::findTypesafeConventionsTomlCatalogAlias)
                ?.segments
                ?.mapNotNull { segment -> segment.name },
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
        val entry = requireNotNull(findCatalogEntry(file, "junit.jupiter"))

        assertEquals("junit.jupiter", entry.key.text)
        assertEquals(
            listOf("junit", "jupiter"),
            requireNotNull(findTypesafeConventionsTomlCatalogAlias(entry))
                .segments
                .mapNotNull { segment -> segment.name },
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
            findCatalogEntry(file, "plugins.kotlin.jvm")?.key?.text,
        )
    }

    @Test
    suspend fun `indexes section owners and generated accessor names for every toml shape`() = readAction {
        val standard = createTomlFile(
            """
                [libraries]
                foo-bar_baz = { module = "example:standard", version = "1.0" }
            """.trimIndent(),
        )
        val dotted = createTomlFile(
            """
                libraries.foo.bar = { module = "example:dotted", version = "1.0" }
            """.trimIndent(),
        )
        val inline = createTomlFile(
            """
                plugins = { kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version = "2.3.0" } }
            """.trimIndent(),
        )

        val standardIndex = typesafeConventionsTomlCatalogAliasIndex(standard)
        val dottedIndex = typesafeConventionsTomlCatalogAliasIndex(dotted)
        val inlineIndex = typesafeConventionsTomlCatalogAliasIndex(inline)

        assertNotNull(standardIndex.sectionOwner(TypesafeConventionsCatalogSection.LIBRARIES))
        assertEquals(
            "foo-bar_baz",
            standardIndex.findByGeneratedAccessor(TypesafeConventionsCatalogSection.LIBRARIES, "FooBarBaz")
                ?.entry
                ?.key
                ?.text,
        )
        assertNotNull(dottedIndex.sectionOwner(TypesafeConventionsCatalogSection.LIBRARIES))
        assertEquals(
            "libraries.foo.bar",
            dottedIndex.findByGeneratedAccessor(TypesafeConventionsCatalogSection.LIBRARIES, "FooBar")
                ?.entry
                ?.key
                ?.text,
        )
        assertNotNull(inlineIndex.sectionOwner(TypesafeConventionsCatalogSection.PLUGINS))
        assertEquals(
            "kotlin-jvm",
            inlineIndex.findByGeneratedAccessor(TypesafeConventionsCatalogSection.PLUGINS, "KotlinJvm")
                ?.entry
                ?.key
                ?.text,
        )
    }

    @Test
    suspend fun `generated accessor collisions stay isolated by catalog section`() = readAction {
        val file = createTomlFile(
            """
                [versions]
                shared-name = "1.0"

                [libraries]
                shared_name = { module = "example:library", version = "1.0" }
            """.trimIndent(),
        )
        val index = typesafeConventionsTomlCatalogAliasIndex(file)

        assertEquals(
            "shared-name",
            index.findByGeneratedAccessor(TypesafeConventionsCatalogSection.VERSIONS, "SharedName")
                ?.entry
                ?.key
                ?.text,
        )
        assertEquals(
            "shared_name",
            index.findByGeneratedAccessor(TypesafeConventionsCatalogSection.LIBRARIES, "SharedName")
                ?.entry
                ?.key
                ?.text,
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

        assertNull(findCatalogEntry(file, "missing"))
        assertNull(findCatalogEntry(file, "plugins.present"))
    }

    @Test
    suspend fun `reuses toml alias index until the file changes`() {
        val file = cachedTomlFile as TomlFile
        val first = readAction { typesafeConventionsTomlCatalogAliasIndex(file) }
        val reused = readAction { typesafeConventionsTomlCatalogAliasIndex(file) }

        assertSame(first, reused)

        val documentManager = PsiDocumentManager.getInstance(project)
        val document = readAction { requireNotNull(documentManager.getDocument(file)) }
        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) {
                document.replaceString(
                    0,
                    document.textLength,
                    """
                        [libraries]
                        after = { module = "example:after", version = "2.0" }
                    """.trimIndent(),
                )
                documentManager.doPostponedOperationsAndUnblockDocument(document)
                documentManager.commitDocument(document)
            }
        }

        val rebuilt = readAction { typesafeConventionsTomlCatalogAliasIndex(file) }
        assertNotSame(reused, rebuilt)
        assertNull(readAction { findCatalogEntry(file, "before") })
        assertEquals(
            "after",
            readAction { findCatalogEntry(file, "after")?.key?.text },
        )
    }

    @Test
    suspend fun `renames only the targeted dotted alias segment`() = readAction {
        assertDottedSegmentRenames(
            """
                [libraries]
                dotted.rename = { module = "example:library", version = "1.0" }
            """.trimIndent(),
        )
    }

    @Test
    suspend fun `renames only the targeted top level dotted alias segment`() = readAction {
        assertDottedSegmentRenames(
            """
                libraries.dotted.rename = { module = "example:library", version = "1.0" }
            """.trimIndent(),
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
        val entry = requireNotNull(findCatalogEntry(file, "foo.bar.baz"))
        val segments = requireNotNull(findTypesafeConventionsTomlCatalogAlias(entry)).segments
        val expression =
            KtPsiFactory(project).createExpression("libs.foo.bar.baz") as KtDotQualifiedExpression
        val accessor = requireNotNull(expression.typesafeConventionsCatalogAccessor())
        val groups = expression.createTypesafeConventionsKotlinCatalogSelectorGroups(
            accessor,
            segments,
        )

        assertEquals(
            listOf("foo.bar.baz"),
            groups.map { group ->
                expression.text.substring(
                    group.rangeInElement.startOffset,
                    group.rangeInElement.endOffset,
                )
            },
        )
        val renamed = expression.replaceTypesafeConventionsCatalogAliasGroup(groups.single(), "new-alias")

        assertEquals("libs.new.alias", renamed.text)
    }

    private fun assertDottedSegmentRenames(
        tomlText: String,
    ) {
        val file = createTomlFile(tomlText)
        val entry = requireNotNull(findCatalogEntry(file, "dotted.rename"))
        val segments = requireNotNull(findTypesafeConventionsTomlCatalogAlias(entry)).segments

        val expectedRanges = listOf("dotted", "rename")
        val expectedRenames = listOf("libs.renamed.rename", "libs.dotted.renamed")
        expectedRenames.forEachIndexed { segmentIndex, expectedExpression ->
            val expression =
                KtPsiFactory(project).createExpression("libs.dotted.rename") as KtDotQualifiedExpression
            val accessor = requireNotNull(expression.typesafeConventionsCatalogAccessor())
            val groups = expression.createTypesafeConventionsKotlinCatalogSelectorGroups(
                accessor,
                segments,
            )
            assertEquals(
                expectedRanges,
                groups.map { group ->
                    expression.text.substring(
                        group.rangeInElement.startOffset,
                        group.rangeInElement.endOffset,
                    )
                },
            )

            val renamed = expression.replaceTypesafeConventionsCatalogAliasGroup(
                groups[segmentIndex],
                "renamed",
            )
            assertEquals(expectedExpression, renamed.text)
        }
    }

    private fun createTomlFile(text: String): TomlFile =
        PsiFileFactory.getInstance(project)
            .createFileFromText("libs.versions.toml", TomlFileType, text) as TomlFile

    private fun findCatalogEntry(tomlFile: TomlFile, declarationPath: String) =
        findCatalogAlias(tomlFile, declarationPath)?.entry

    private fun findCatalogAlias(
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
}
