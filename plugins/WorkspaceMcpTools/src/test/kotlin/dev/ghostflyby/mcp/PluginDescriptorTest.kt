/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.util.Collections

internal class PluginDescriptorTest {

    @Test
    fun `root descriptor declares the split content modules`() {
        val descriptor = rootPluginDescriptor()
        val modules = Regex("""<module name="([^"]+)""").findAll(descriptor).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(
                "dev.ghostflyby.mcp.workspace.shared",
                "dev.ghostflyby.mcp.workspace.frontend",
                "dev.ghostflyby.mcp.workspace.backend",
            ),
            modules,
            "the root descriptor must declare the shared/frontend/backend content modules",
        )
    }

    private fun rootPluginDescriptor(): String {
        return Collections.list(javaClass.classLoader.getResources("META-INF/plugin.xml"))
            .asSequence()
            .map { it.readText() }
            .firstOrNull { "<id>dev.ghostflyby.mcp.workspace</id>" in it }
            .also { assertNotNull(it, "plugin.xml of this plugin must be reachable from the test classpath") }
            .orEmpty()
    }
}
