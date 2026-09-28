/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class BackendDescriptorTest {

    @Test
    fun `backend module pins the runtime to the IDE backend`() {
        val descriptor = javaClass.classLoader
            .getResource("dev.ghostflyby.mcp.workspace.backend.xml")?.readText()
            .also { assertNotNull(it, "backend module descriptor must be on the test classpath") }
            .orEmpty()
        assertTrue(
            "<module name=\"intellij.platform.backend\"/>" in descriptor,
            "the backend module must require intellij.platform.backend so split-mode frontends skip it",
        )
        assertTrue("dev.ghostflyby.mcp.WorkspaceMcpStartupActivity" in descriptor)
        assertFalse(
            "notificationGroup" in descriptor,
            "notifications belong to the frontend module",
        )
    }
}
