/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class TypesafeConventionsGradleBuildStateTest {

    @Test
    fun `committing linked roots independently preserves both roots`() {
        val state = TypesafeConventionsGradleBuildState()

        state.stageCandidate("/repo/root-a", candidate("file:///repo/root-a", "file:///repo/root-a/libs.toml"))
        state.commit("/repo/root-a")
        state.stageCandidate("/repo/root-b", candidate("file:///repo/root-b", "file:///repo/root-b/libs.toml"))
        state.commit("/repo/root-b")

        assertEquals(
            setOf("file:///repo/root-a", "file:///repo/root-b"),
            state.committedBuildUrls(),
        )
    }

    @Test
    fun `failed import discards pending candidate and preserves last known good`() {
        val state = TypesafeConventionsGradleBuildState()
        state.stageCandidate("/repo/root", candidate("file:///repo/old", "file:///repo/old/libs.toml"))
        state.commit("/repo/root")

        state.stageCandidate("/repo/root", candidate("file:///repo/new", "file:///repo/new/libs.toml"))
        state.discard("/repo/root")

        assertEquals(setOf("file:///repo/old"), state.committedBuildUrls())
        assertTrue(state.pendingProjectPaths().isEmpty())
        assertEquals(
            TypesafeConventionsGradleProjectPathHealth.STALE,
            state.projectPathHealth("/repo/root"),
        )

        state.stageCandidate("/repo/root", candidate("file:///repo/new", "file:///repo/new/libs.toml"))
        state.commit("/repo/root")

        assertEquals(
            TypesafeConventionsGradleProjectPathHealth.CURRENT,
            state.projectPathHealth("/repo/root"),
        )
    }

    @Test
    fun `successful disabled candidate removes only current linked root`() {
        val state = TypesafeConventionsGradleBuildState()
        state.stageCandidate("/repo/root-a", candidate("file:///repo/root-a", "file:///repo/root-a/libs.toml"))
        state.stageCandidate("/repo/root-b", candidate("file:///repo/root-b", "file:///repo/root-b/libs.toml"))
        state.commit(null)

        state.stageCandidate("/repo/root-a", candidate())
        state.commit("/repo/root-a")

        assertEquals(setOf("file:///repo/root-b"), state.committedBuildUrls())
    }

    @Test
    fun `null project path commits all pending candidates atomically`() {
        val state = TypesafeConventionsGradleBuildState()
        state.stageCandidate("/repo/root-a", candidate("file:///repo/root-a", "file:///repo/root-a/libs.toml"))
        state.stageCandidate("/repo/root-b", candidate("file:///repo/root-b", "file:///repo/root-b/libs.toml"))

        val commit = state.commit(null)

        assertEquals(setOf("/repo/root-a", "/repo/root-b"), commit.projectPaths)
        assertEquals(
            setOf("file:///repo/root-a/libs.toml", "file:///repo/root-b/libs.toml"),
            commit.catalogUrlsToRefresh,
        )
        assertTrue(state.pendingProjectPaths().isEmpty())
    }

    @Test
    fun `recommitting an unchanged catalog refreshes it without changing persistent state`() {
        val state = TypesafeConventionsGradleBuildState()
        val candidate = candidate("file:///repo/root", "file:///repo/root/libs.toml")

        state.stageCandidate("/repo/root", candidate)
        val firstCommit = state.commit("/repo/root")
        val firstModificationCount = state.stateModificationCount
        state.stageCandidate("/repo/root", candidate)
        val secondCommit = state.commit("/repo/root")

        assertEquals(setOf("file:///repo/root/libs.toml"), firstCommit.catalogUrlsToRefresh)
        assertEquals(setOf("file:///repo/root/libs.toml"), secondCommit.catalogUrlsToRefresh)
        assertEquals(firstModificationCount, state.stateModificationCount)
    }

    @Test
    fun `mapping change increments persistent state once`() {
        val state = TypesafeConventionsGradleBuildState()
        state.stageCandidate("/repo/root", candidate("file:///repo/root", "file:///repo/root/libs.toml"))
        state.commit("/repo/root")
        val previousModificationCount = state.stateModificationCount

        state.stageCandidate("/repo/root", candidate("file:///repo/remapped", "file:///repo/remapped/libs.toml"))
        state.commit("/repo/root")

        assertEquals(previousModificationCount + 1, state.stateModificationCount)
        assertEquals(setOf("file:///repo/remapped"), state.committedBuildUrls())
    }

    @Test
    fun `unlink removes committed and pending state for one root`() {
        val state = TypesafeConventionsGradleBuildState()
        state.stageCandidate("/repo/root-a", candidate("file:///repo/root-a", "file:///repo/root-a/libs.toml"))
        state.stageCandidate("/repo/root-b", candidate("file:///repo/root-b", "file:///repo/root-b/libs.toml"))
        state.commit(null)
        state.stageCandidate("/repo/root-a", candidate("file:///repo/root-a/new", "file:///repo/root-a/new.toml"))

        assertTrue(state.remove("/repo/root-a"))

        assertEquals(setOf("file:///repo/root-b"), state.committedBuildUrls())
        assertTrue(state.pendingProjectPaths().isEmpty())
        assertEquals(
            TypesafeConventionsGradleProjectPathHealth.UNKNOWN,
            state.projectPathHealth("/repo/root-a"),
        )

        val modificationCount = state.stateModificationCount
        assertFalse(state.remove("/repo/missing"))
        assertEquals(modificationCount, state.stateModificationCount)
    }

    @Test
    fun `persistent state restores committed roots without restoring pending candidates`() {
        val original = TypesafeConventionsGradleBuildState()
        original.stageCandidate(
            "/repo/root-a/../root-a",
            candidate("file:///repo/root-a", "file:///repo/root-a/libs.toml"),
        )
        original.commit("/repo/root-a")
        original.stageCandidate("/repo/pending", candidate("file:///repo/pending", "file:///repo/pending/libs.toml"))

        val restored = TypesafeConventionsGradleBuildState()
        restored.loadState(original.state)

        assertEquals(setOf("file:///repo/root-a"), restored.committedBuildUrls())
        assertTrue(restored.pendingProjectPaths().isEmpty())
        assertEquals(
            TypesafeConventionsGradleProjectPathHealth.UNKNOWN,
            restored.projectPathHealth("/repo/root-a"),
        )
    }

    private fun candidate(
        buildUrl: String? = null,
        catalogUrl: String? = null,
    ): TypesafeConventionsGradleBuildCandidate =
        TypesafeConventionsGradleBuildCandidate(
            buildUrls = buildUrl?.let(::setOf).orEmpty(),
            catalogUrls = catalogUrl?.let(::setOf).orEmpty(),
        )
}
