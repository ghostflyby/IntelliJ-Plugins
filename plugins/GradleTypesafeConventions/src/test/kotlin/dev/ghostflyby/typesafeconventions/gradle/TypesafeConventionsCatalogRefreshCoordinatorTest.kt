/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.seconds

internal class TypesafeConventionsCatalogRefreshCoordinatorTest {

    @Test
    fun `schedule returns before refresh completes and publishes only the latest union`() = runBlocking {
        val scopeJob = SupervisorJob()
        val firstRefreshStarted = CompletableDeferred<Unit>()
        val releaseFirstRefresh = CompletableDeferred<Unit>()
        val refreshCalls = CopyOnWriteArrayList<Set<String>>()
        val publications = CopyOnWriteArrayList<Pair<Long, Set<String>>>()
        val failures = CopyOnWriteArrayList<Throwable>()
        lateinit var coordinator: TypesafeConventionsCatalogRefreshCoordinator
        coordinator = TypesafeConventionsCatalogRefreshCoordinator(
            scope = CoroutineScope(scopeJob + Dispatchers.Default),
            refreshCatalogs = { catalogUrls ->
                refreshCalls.add(catalogUrls)
                if (refreshCalls.size == 1) {
                    firstRefreshStarted.complete(Unit)
                    releaseFirstRefresh.await()
                }
            },
            publishIfCurrent = { generation, refreshedCatalogUrls ->
                if (generation != coordinator.currentGeneration) {
                    false
                } else {
                    publications.add(generation to refreshedCatalogUrls)
                    true
                }
            },
            onFailure = { _, throwable -> failures.add(throwable) },
        )

        try {
            coordinator.schedule(setOf("file:///catalog-a.toml"))
            withTimeout(10.seconds) {
                firstRefreshStarted.await()
            }
            coordinator.schedule(setOf("file:///catalog-b.toml"))

            assertTrue(publications.isEmpty())
            releaseFirstRefresh.complete(Unit)
            withTimeout(10.seconds) {
                coordinator.awaitIdle()
            }

            assertEquals(
                listOf(setOf("file:///catalog-a.toml"), setOf("file:///catalog-b.toml")),
                refreshCalls,
            )
            assertEquals(
                listOf(2L to setOf("file:///catalog-a.toml", "file:///catalog-b.toml")),
                publications,
            )
            assertTrue(failures.isEmpty())
        } finally {
            scopeJob.cancelAndJoin()
        }
    }

    @Test
    fun `scope cancellation prevents publication while refresh is suspended`() = runBlocking {
        val scopeJob = SupervisorJob()
        val refreshStarted = CompletableDeferred<Unit>()
        val suspendedRefresh = CompletableDeferred<Unit>()
        val publications = CopyOnWriteArrayList<Set<String>>()
        val failures = CopyOnWriteArrayList<Throwable>()
        val coordinator = TypesafeConventionsCatalogRefreshCoordinator(
            scope = CoroutineScope(scopeJob + Dispatchers.Default),
            refreshCatalogs = {
                refreshStarted.complete(Unit)
                suspendedRefresh.await()
            },
            publishIfCurrent = { _, refreshedCatalogUrls ->
                publications.add(refreshedCatalogUrls)
                true
            },
            onFailure = { _, throwable -> failures.add(throwable) },
        )

        coordinator.schedule(setOf("file:///catalog.toml"))
        withTimeout(10.seconds) {
            refreshStarted.await()
        }
        scopeJob.cancelAndJoin()

        assertTrue(publications.isEmpty())
        assertTrue(failures.isEmpty())
    }
}
