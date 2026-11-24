/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class BackendDescriptorTest {

    @Test
    fun `backend module pins the runtime to the IDE backend`() {
        val descriptor = descriptor()
        assertTrue(
            "<module name=\"intellij.platform.backend\"/>" in descriptor,
            "the backend module must require intellij.platform.backend so split-mode frontends skip it",
        )
        assertTrue(
            "<module name=\"dev.ghostflyby.mill.shared\"/>" in descriptor,
            "the backend module must depend on the shared content module",
        )
        assertFalse(
            "com.intellij.modules.lang" in descriptor,
            "each content module declares its own runtime-module dependencies",
        )
    }

    @Test
    fun `external system integration and its UI live on the backend`() {
        val descriptor = descriptor()
        assertTrue("externalSystemManager" in descriptor)
        assertTrue("projectConfigurable" in descriptor)
        assertTrue("toolWindow" in descriptor)
        // Tool window, settings and actions read external-system state that only exists on the
        // backend, and backend Swing replicates to the JetBrains Client; the notification group
        // stays backend-side too until a Remote Topic bridge exists (see
        // WorkspaceMcpTools-ModularSplitMigration.md for the same limitation).
        assertTrue("notificationGroup" in descriptor)
    }

    private fun descriptor(): String {
        return javaClass.classLoader
            .getResource("dev.ghostflyby.mill.backend.xml")?.readText()
            .also { assertNotNull(it, "backend module descriptor must be on the test classpath") }
            .orEmpty()
    }
}
