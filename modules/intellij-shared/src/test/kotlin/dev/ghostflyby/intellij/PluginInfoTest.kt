/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.intellij

import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URLClassLoader
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

@TestApplication
internal class PluginInfoTest {
    @Test
    fun readsPluginXmlMetadataFromProvider() {
        withPluginXmlResource(
            """
            <idea-plugin>
                <id>com.intellij</id>
                <name>Ignored Test Plugin Name</name>
                <version>1.2.3</version>
            </idea-plugin>
            """.trimIndent(),
        ) { classLoader ->
            val info = PluginInfoProvider(classLoader)

            assertEquals("com.intellij", info.id)
            assertEquals("IDEA CORE", info.name)
        }
    }

    @Test
    fun readsPluginXmlOnlyOncePerProvider() {
        withPluginXmlResource(
            """
            <idea-plugin>
                <id>com.intellij</id>
                <name>Ignored Test Plugin Name</name>
                <version>2.0.0</version>
            </idea-plugin>
            """.trimIndent(),
        ) { classLoader ->
            val info = PluginInfoProvider(classLoader)

            assertEquals("com.intellij", info.id)
            assertEquals("com.intellij", info.id)
            assertEquals("IDEA CORE", info.name)
        }
    }

    private fun withPluginXmlResource(
        pluginXml: String,
        block: (ClassLoader) -> Unit,
    ) {
        val root = createTempDirectory("plugin-info-test")
        val resourceDirectory = root.resolve("META-INF")
        resourceDirectory.createDirectories()
        resourceDirectory.resolve("plugin.xml").writeText(pluginXml)

        URLClassLoader(arrayOf(root.toUri().toURL()), null).use { loader ->
            block(loader)
        }
    }

}
