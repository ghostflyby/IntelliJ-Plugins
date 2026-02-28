/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.dcevm.config

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.removeUserData
import com.intellij.util.xmlb.XmlSerializer
import dev.ghostflyby.dcevm.Bundle
import dev.ghostflyby.dcevm.PluginDisposable
import org.jdom.Element
import java.util.*
import javax.swing.JComponent

// do not change qualified name to avoid breaking existing configurations
internal class HotswapRunConfigurationExtension : RunConfigurationExtension(), Disposable {
    private val trackedHolders =
        Collections.newSetFromMap(WeakHashMap<UserDataHolder, Boolean>())

    init {
        val plugin = service<PluginDisposable>()
        Disposer.register(plugin, this)
    }

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean =
        true

    override fun readExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        val child = element.getChild(configKey) ?: return
        val deserialized = try {
            XmlSerializer.deserialize(child, HotswapConfigState::class.java)
        } catch (_: Throwable) {
            null
        }
        setTrackedState(runConfiguration, deserialized)
    }

    override fun writeExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        val state = runConfiguration.getUserData(HotSwapRunConfigurationDataKey.KEY) ?: return
        val child = element.getOrCreateChild(configKey)
        XmlSerializer.serializeInto(state, child)
    }

    override fun cleanUserData(runConfigurationBase: RunConfigurationBase<*>) {
        clearTrackedState(runConfigurationBase)
    }

    override fun <P : RunConfigurationBase<*>> createEditor(configuration: P): SettingsEditor<P?> {
        return object : SettingsEditor<P?>() {
            private var model = HotswapConfigViewModel()
            private val ui = hotswapConfigView(model)

            override fun resetEditorFrom(s: P) {
                val st = s.getUserData(HotSwapRunConfigurationDataKey.KEY)
                if (st != null) model.setFrom(st)
            }

            override fun applyEditorTo(s: P) {
                val newState = HotswapConfigState().setFrom(model)
                setTrackedState(s, newState)
            }

            override fun createEditor(): JComponent = ui

        }
    }

    override fun getEditorTitle(): String = Bundle.message("configuration.section.name")

    @Suppress("EmptyMethod")
    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        return
    }

    override fun dispose() {
        synchronized(trackedHolders) {
            trackedHolders.forEach { holder ->
                holder.removeUserData(HotSwapRunConfigurationDataKey.KEY)
            }
            trackedHolders.clear()
        }
    }

    private fun setTrackedState(holder: UserDataHolder, state: HotswapConfigState?) {
        holder.putUserData(HotSwapRunConfigurationDataKey.KEY, state)
        synchronized(trackedHolders) {
            if (state == null) {
                trackedHolders.remove(holder)
            } else {
                trackedHolders.add(holder)
            }
        }
    }

    private fun clearTrackedState(holder: UserDataHolder) {
        holder.removeUserData(HotSwapRunConfigurationDataKey.KEY)
        synchronized(trackedHolders) {
            trackedHolders.remove(holder)
        }
    }
}
