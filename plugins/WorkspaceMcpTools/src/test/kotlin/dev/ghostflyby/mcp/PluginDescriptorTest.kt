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
    fun `root descriptor declares the content modules with their loading rules`() {
        val descriptor = rootDescriptor()
        val entries = Regex("""<module name="([^"]+)"([^>]*)/>""")
            .findAll(descriptor.substring(descriptor.indexOf("<content>"), descriptor.indexOf("</content>")))
            .map { it.groupValues[1] to it.groupValues[2].trim() }
            .toList()
        assertEquals(
            listOf(
                "dev.ghostflyby.mcp.workspace.shared" to """loading="required"""",
                "dev.ghostflyby.mcp.workspace.frontend" to "",
                "dev.ghostflyby.mcp.workspace.backend" to """loading="embedded" required-if-available="intellij.platform.backend"""",
            ),
            entries,
            "the root descriptor must declare the content modules with their split-mode loading rules",
        )
    }

    private fun rootDescriptor(): String {
        return Collections.list(javaClass.classLoader.getResources("META-INF/plugin.xml"))
            .asSequence()
            .map { it.readText() }
            .firstOrNull { "<id>dev.ghostflyby.mcp.workspace</id>" in it }
            .also { assertNotNull(it, "plugin.xml of this plugin must be reachable from the test classpath") }
            .orEmpty()
    }
}
