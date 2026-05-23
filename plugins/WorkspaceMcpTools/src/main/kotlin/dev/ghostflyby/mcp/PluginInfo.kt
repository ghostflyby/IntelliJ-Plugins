/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginAware
import com.intellij.openapi.extensions.PluginDescriptor

@Service
internal class MyPluginInfo : PluginAware {
    override fun setPluginDescriptor(pluginDescriptor: PluginDescriptor) {
        descriptor = pluginDescriptor
    }

    internal lateinit var descriptor: PluginDescriptor

}

internal val pluginDescriptor get() = service<MyPluginInfo>().descriptor
