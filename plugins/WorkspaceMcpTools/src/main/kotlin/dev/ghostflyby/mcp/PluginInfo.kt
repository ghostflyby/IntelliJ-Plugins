/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp

import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginAware
import com.intellij.openapi.extensions.PluginDescriptor

private val LOG = Logger.getInstance("dev.ghostflyby.mcp.PluginInfo")

/**
 * Application-level service providing the owning plugin's version string.
 *
 * Implements [PluginAware] so the IntelliJ container injects the owning plugin's
 * [PluginDescriptor] at construction time.  In test environments where the
 * container does not inject the descriptor, [version] falls back to "unknown"
 * without throwing.
 */
@Service(Service.Level.APP)
internal class PluginVersionService : PluginAware {
    private var _pluginDescriptor: PluginDescriptor? = null

    override fun setPluginDescriptor(pluginDescriptor: PluginDescriptor) {
        _pluginDescriptor = pluginDescriptor
    }

    val version: String
        get() {
            val desc = _pluginDescriptor ?: run {
                LOG.warn("Plugin descriptor not injected; reporting version as unknown.")
                return "unknown"
            }
            val result = (desc as? IdeaPluginDescriptor)?.version
            if (result == null) {
                LOG.warn("Plugin descriptor has no version; reporting as unknown.")
            }
            return result ?: "unknown"
        }
}

internal val pluginVersion: String
    get() = service<PluginVersionService>().version
