/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*

internal class PluginDescriptorTest {

    @Test
    fun `root descriptor declares the split content modules`() {
        val descriptor = rootPluginDescriptor()
        val contentStart = descriptor.indexOf("<content>")
        val contentEnd = descriptor.indexOf("</content>")
        check(contentStart in 0..<contentEnd) { "root descriptor must declare a content block" }
        val contentModules = Regex("""<module name="([^"]+)"""")
            .findAll(descriptor.substring(contentStart, contentEnd))
            .map { it.groupValues[1] }
            .toList()
        assertEquals(
            listOf(
                "dev.ghostflyby.mcp.workspace.shared",
                "dev.ghostflyby.mcp.workspace.frontend",
                "dev.ghostflyby.mcp.workspace.backend",
            ),
            contentModules,
            "the root descriptor must declare the shared/frontend/backend content modules",
        )
        val backendModule = Regex("""<module name="dev\.ghostflyby\.mcp\.workspace\.backend"[^>]*/>""")
            .find(descriptor)?.value.orEmpty()
        assertTrue(
            """required-if-available="intellij.platform.backend"""" in backendModule,
            "the backend module must be required wherever the platform provides intellij.platform.backend",
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
