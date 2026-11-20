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

internal class SharedDescriptorTest {

    @Test
    fun `shared module descriptor ships and stays empty`() {
        val descriptor = javaClass.classLoader
            .getResource("dev.ghostflyby.mcp.workspace.shared.xml")?.readText()
            .also { assertNotNull(it, "shared module descriptor must be on the test classpath") }
            .orEmpty()
        // The shared module only defines the classloader; platform-module dependencies live in
        // each consuming content module's descriptor.
        assertTrue("<idea-plugin" in descriptor)
        listOf("<dependencies", "<extensions", "postStartupActivity", "notificationGroup").forEach {
            assertFalse(it in descriptor, "shared module descriptor must stay empty, found $it")
        }
    }
}
