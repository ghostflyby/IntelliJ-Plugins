/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.intellij

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.extensions.PluginId

public val Class<*>.pluginId: PluginId
    get() = requirePluginAwareClassLoader().pluginId

public val Class<*>.pluginName: String
    get() = readPluginDescriptor { name }

public val Class<*>.pluginVersion: String
    get() = readPluginDescriptor { version }

private inline fun <T> Class<*>.readPluginDescriptor(read: PluginDescriptor.() -> T): T {
    return requirePluginAwareClassLoader().pluginDescriptor.read()
}

private fun Class<*>.requirePluginAwareClassLoader(): PluginAwareClassLoader {
    val classLoader = classLoader
    require(classLoader is PluginAwareClassLoader) {
        "Plugin metadata for $name requires a PluginAwareClassLoader, " +
                "but got ${classLoader?.javaClass?.name ?: "the bootstrap class loader"}."
    }
    return classLoader
}
