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
    fun `external system integration and host-side work live on the backend`() {
        val descriptor = descriptor()
        assertTrue("externalSystemManager" in descriptor)
        assertTrue("externalProjectDataService" in descriptor)
        assertTrue("additionalLibraryRootsProvider" in descriptor)
        assertTrue("resolveScopeEnlarger" in descriptor)
        assertTrue("fileTypeOverrider" in descriptor)
        assertTrue(
            "platform.rpc.backend.remoteApiProvider" in descriptor,
            "the backend provides the executable discovery/probing RPC implementation",
        )
        // The settings UI, tool window and actions read settings/state that also exists in a
        // split-mode frontend, so they live in the frontend module; the backend keeps the
        // external-system resolver, task manager, data services and host-side probing.
        assertFalse("projectConfigurable" in descriptor)
        assertFalse("toolWindow" in descriptor)
        assertFalse("<actions>" in descriptor)
    }

    private fun descriptor(): String {
        return javaClass.classLoader
            .getResource("dev.ghostflyby.mill.backend.xml")?.readText()
            .also { assertNotNull(it, "backend module descriptor must be on the test classpath") }
            .orEmpty()
    }
}
