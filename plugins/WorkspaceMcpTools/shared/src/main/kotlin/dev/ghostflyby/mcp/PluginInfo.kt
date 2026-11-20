/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.platform.ide.productMode.IdeProductMode

// Anchor class of this shared content module; a loaded plugin's module classloader is always a
// PluginAwareClassLoader carrying the plugin descriptor.
private object PluginInfoHolder

public val pluginVersion: String
    get() {
        val classLoader = PluginInfoHolder::class.java.classLoader
        require(classLoader is PluginAwareClassLoader) {
            "Plugin metadata requires a PluginAwareClassLoader, " +
                    "but got ${classLoader?.javaClass?.name ?: "the bootstrap class loader"}."
        }
        return classLoader.pluginDescriptor.version
    }

/**
 * True when this process hosts the project model: a monolith IDE or a remote-development backend.
 * The backend content module already requires `intellij.platform.backend`, which frontend
 * processes do not provide, so this is the runtime second gate for the same contract.
 * `IdeProductMode` is `@ApiStatus.Experimental` on 2026.1; re-check on platform upgrades
 * (2026.2 widens `isFrontend` to the light product modes, which do not satisfy the module
 * dependency either).
 */
@Suppress("UnstableApiUsage")
public fun runsOnIdeBackendSide(): Boolean = !IdeProductMode.isFrontend
