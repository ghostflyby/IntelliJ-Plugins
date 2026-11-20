/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.gradle

import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
internal class StopSpotlessDaemonsActionTest {
    private val projectFixture = projectFixture(
        pathFixture = tempPathFixture(),
        openAfterCreation = true,
    )

    @Test
    fun `stop action remains visible when no daemon is running`() {
        val project = projectFixture.get()
        val action = StopSpotlessDaemonsAction()
        val event = TestActionEvent.createTestEvent(
            action,
            SimpleDataContext.getProjectContext(project),
        )

        action.update(event)

        assertTrue(event.presentation.isVisible)
        assertFalse(event.presentation.isEnabled)
    }
}
