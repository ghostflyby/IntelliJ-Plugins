/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.components.service
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

@TestApplication
internal class TypesafeConventionsCatalogIndexTest {

    private val projectFixture = projectFixture(openAfterCreation = true)
    private val project by projectFixture
    private val indexService
        get() = project.service<TypesafeConventionsCatalogIndexService>()

    @Test
    fun `same index is a no-op unless publication is explicitly invalidated`() {
        val initial = TypesafeConventionsCatalogIndex.create(emptyList())
        assertTrue(indexService.publishForTests(initial))
        val initialGeneration = indexService.modificationCount

        assertFalse(indexService.publishForTests(TypesafeConventionsCatalogIndex.create(emptyList())))
        assertEquals(initialGeneration, indexService.modificationCount)

        assertTrue(
            indexService.publishForTests(
                TypesafeConventionsCatalogIndex.create(emptyList()),
                forceInvalidate = true,
            ),
        )
        assertEquals(initialGeneration + 1, indexService.modificationCount)
    }

    @Test
    fun `normalizes entry ordering before semantic comparison`() {
        val first = TypesafeConventionsCatalogIndexEntry(
            catalogName = "libs",
            catalogUrl = "file:///repo/gradle/libs.versions.toml",
            buildUrl = "file:///repo",
            contextRootUrls = linkedSetOf("file:///repo/subproject", "file:///repo"),
        )
        val second = first.copy(catalogName = "tools")
        assertTrue(indexService.publishForTests(TypesafeConventionsCatalogIndex.create(listOf(first, second))))
        val initialGeneration = indexService.modificationCount

        assertFalse(
            indexService.publishForTests(
                TypesafeConventionsCatalogIndex.create(
                    listOf(
                        second.copy(contextRootUrls = first.contextRootUrls.reversed().toSet()),
                        first.copy(contextRootUrls = first.contextRootUrls.reversed().toSet()),
                    ),
                ),
            ),
        )
        assertEquals(initialGeneration, indexService.modificationCount)
    }

    @Test
    fun `chooses the deepest matching catalog context and indexes build roots by catalog url`() {
        val sharedCatalogUrl = "file:///repo/gradle/libs.versions.toml"
        val rootCatalog = TypesafeConventionsCatalogIndexEntry(
            catalogName = "libs",
            catalogUrl = sharedCatalogUrl,
            buildUrl = "file:///repo",
            contextRootUrls = setOf("file:///repo"),
        )
        val nestedCatalog = TypesafeConventionsCatalogIndexEntry(
            catalogName = "libs",
            catalogUrl = sharedCatalogUrl,
            buildUrl = "file:///repo/build-logic",
            contextRootUrls = setOf("file:///repo/build-logic"),
        )
        val index = TypesafeConventionsCatalogIndex.create(listOf(rootCatalog, nestedCatalog))

        assertEquals(
            nestedCatalog,
            index.findCatalog("file:///repo/build-logic/src/main/kotlin/Plugin.kt", "libs"),
        )
        assertEquals(
            setOf("file:///repo", "file:///repo/build-logic"),
            index.buildRootUrls(sharedCatalogUrl),
        )
    }
}
