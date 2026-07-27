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
    fun `reuses index for the same snapshot identity and state version`() {
        val cache = TypesafeConventionsCatalogIndexCache()
        val snapshot = Any()
        var buildCount = 0

        fun index(snapshotIdentity: Any, stateModificationCount: Long) =
            cache.getOrBuild(snapshotIdentity, stateModificationCount) {
                buildCount++
                TypesafeConventionsCatalogIndex.create(emptyList())
            }

        val first = index(snapshot, 1)
        val reused = index(snapshot, 1)
        val stateChanged = index(snapshot, 2)
        val snapshotChanged = index(Any(), 2)

        assertSame(first, reused)
        assertNotSame(reused, stateChanged)
        assertNotSame(stateChanged, snapshotChanged)
        assertEquals(3, buildCount)
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

        assertSame(
            nestedCatalog,
            index.findCatalog("file:///repo/build-logic/src/main/kotlin/Plugin.kt", "libs"),
        )
        assertEquals(
            setOf("file:///repo", "file:///repo/build-logic"),
            index.buildRootUrls(sharedCatalogUrl),
        )
    }
}
