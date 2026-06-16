/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.intellij

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URLClassLoader
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class PluginInfoTest {
    @Test
    fun readsPluginXmlMetadataFromProvider() {
        withPluginXmlResource(
            """
            <idea-plugin>
                <id>dev.ghostflyby.test</id>
                <name>Test Plugin</name>
                <version>1.2.3</version>
            </idea-plugin>
            """.trimIndent(),
        ) { classLoader ->
            val info = TestPluginInfoProvider(classLoader)

            assertEquals("dev.ghostflyby.test", info.id)
            assertEquals("Test Plugin", info.name)
            assertEquals("1.2.3", info.version)
        }
    }

    @Test
    fun readsPluginXmlOnlyOncePerProvider() {
        withPluginXmlResource(
            """
            <idea-plugin>
                <id>dev.ghostflyby.lazy</id>
                <name>Lazy Plugin</name>
                <version>2.0.0</version>
            </idea-plugin>
            """.trimIndent(),
        ) { classLoader ->
            val info = TestPluginInfoProvider(classLoader)

            assertEquals("dev.ghostflyby.lazy", info.id)
            assertEquals("dev.ghostflyby.lazy", info.id)
            assertEquals("Lazy Plugin", info.name)
            assertEquals("2.0.0", info.version)
        }
    }

    @Test
    fun requiresVersionElement() {
        withPluginXmlResource(
            """
            <idea-plugin>
                <id>dev.ghostflyby.test</id>
                <name>Test Plugin</name>
            </idea-plugin>
            """.trimIndent(),
        ) { classLoader ->
            val info = TestPluginInfoProvider(classLoader)

            val error = assertThrows(IllegalStateException::class.java) {
                info.version
            }

            assertEquals("plugin.xml is missing required <version> element.", error.message)
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

    private class TestPluginInfoProvider(classLoader: ClassLoader) : PluginInfoProvider(classLoader)
}
