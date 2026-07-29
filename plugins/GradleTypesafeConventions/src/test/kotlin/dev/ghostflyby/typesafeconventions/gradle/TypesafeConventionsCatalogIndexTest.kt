/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

internal class TypesafeConventionsCatalogIndexTest {

    @Test
    fun `publishes only semantic index changes`() {
        val published = TypesafeConventionsPublishedCatalogIndex()
        var buildCount = 0

        val initial = published.currentOrBuild {
            buildCount++
            TypesafeConventionsCatalogIndex.create(emptyList())
        }
        val reused = published.currentOrBuild {
            buildCount++
            TypesafeConventionsCatalogIndex.create(emptyList())
        }

        assertSame(initial, reused)
        assertFalse(published.publish(TypesafeConventionsCatalogIndex.create(emptyList())))
        assertEquals(0, published.modificationCount)
        assertTrue(
            published.publish(
                TypesafeConventionsCatalogIndex.create(emptyList(), setOf("file:///repo")),
            ),
        )
        assertEquals(1, published.modificationCount)
        assertFalse(
            published.publish(
                TypesafeConventionsCatalogIndex.create(emptyList(), setOf("file:///repo")),
            ),
        )
        assertEquals(1, published.modificationCount)
        assertEquals(1, buildCount)
    }

    @Test
    fun `normalizes entry ordering before semantic comparison`() {
        val published = TypesafeConventionsPublishedCatalogIndex()
        val first = TypesafeConventionsCatalogIndexEntry(
            catalogName = "libs",
            catalogUrl = "file:///repo/gradle/libs.versions.toml",
            buildUrl = "file:///repo",
            contextRootUrls = linkedSetOf("file:///repo/subproject", "file:///repo"),
        )
        val second = first.copy(catalogName = "tools")
        published.currentOrBuild {
            TypesafeConventionsCatalogIndex.create(listOf(first, second))
        }

        assertFalse(
            published.publish(
                TypesafeConventionsCatalogIndex.create(
                    listOf(
                        second.copy(contextRootUrls = first.contextRootUrls.reversed().toSet()),
                        first.copy(contextRootUrls = first.contextRootUrls.reversed().toSet()),
                    ),
                ),
            ),
        )
        assertEquals(0, published.modificationCount)
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
