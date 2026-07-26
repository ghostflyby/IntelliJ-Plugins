/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.intellij

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.extensions.PluginDescriptor
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.net.URLClassLoader

internal class PluginInfoTest {
    @Test
    fun rejectsPlainClassLoader() {
        URLClassLoader(emptyArray(), null).use { classLoader ->
            val anchorClass = Proxy.newProxyInstance(
                classLoader,
                arrayOf(Runnable::class.java),
            ) { _, _, _ -> null }.javaClass

            val error = assertThrows(IllegalArgumentException::class.java) {
                anchorClass.pluginId
            }

            assertTrue(error.message.orEmpty().contains("PluginAwareClassLoader"))
        }
    }

    @Test
    fun doesNotStorePluginClassLoaderState() {
        val fields = Class.forName("dev.ghostflyby.intellij.PluginInfoKt").declaredFields

        assertTrue(fields.none { ClassLoader::class.java.isAssignableFrom(it.type) })
        assertTrue(fields.none { PluginAwareClassLoader::class.java.isAssignableFrom(it.type) })
        assertTrue(fields.none { PluginDescriptor::class.java.isAssignableFrom(it.type) })
    }
}
