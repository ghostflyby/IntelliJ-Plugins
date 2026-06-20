/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.intellij

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.extensions.PluginDescriptor
import java.lang.ref.WeakReference

public class PluginInfoProvider private constructor(
    private val classLoader: WeakReference<ClassLoader>,
) {
    public constructor(anchorClass: Class<*>) : this(WeakReference(anchorClass.classLoader))

    public constructor(classLoader: ClassLoader) : this(WeakReference(classLoader))

    public val pluginDescriptor: PluginDescriptor by lazy(LazyThreadSafetyMode.PUBLICATION) {
        readPluginInfo(classLoader.get()?.apply { classLoader.clear() }!!)
    }

    public val id: String
        get() = pluginDescriptor.pluginId.idString

    public val name: String
        get() = pluginDescriptor.name

    public val version: String
        get() = pluginDescriptor.version
}

private fun readPluginInfo(classLoader: ClassLoader): PluginDescriptor {
    return (classLoader as PluginAwareClassLoader).pluginDescriptor
}
