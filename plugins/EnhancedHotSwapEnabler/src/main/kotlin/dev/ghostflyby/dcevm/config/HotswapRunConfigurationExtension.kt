/*
 * Copyright (c) 2025 ghostflyby <ghostflyby+intellij@outlook.com>
 *
 * This program is free software; you can redistribute it and/or
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
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.removeUserData
import com.intellij.util.xmlb.XmlSerializer
import dev.ghostflyby.dcevm.Bundle
import dev.ghostflyby.dcevm.PluginDisposable
import org.jdom.Element
import javax.swing.JComponent

// do not change qualified name to avoid breaking existing configurations
internal class HotswapRunConfigurationExtension : RunConfigurationExtension(), Disposable {

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
        runConfiguration.putUserData(HotSwapRunConfigurationDataKey.KEY, deserialized)
    }

    override fun writeExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        val state = runConfiguration.getUserData(HotSwapRunConfigurationDataKey.KEY) ?: return
        val child = element.getOrCreateChild(configKey)
        XmlSerializer.serializeInto(state, child)
    }

    override fun cleanUserData(runConfigurationBase: RunConfigurationBase<*>) {
        runConfigurationBase.removeUserData(HotSwapRunConfigurationDataKey.KEY)
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
                s.putUserData(HotSwapRunConfigurationDataKey.KEY, newState)
            }

            override fun createEditor(): JComponent = ui

        }
    }

    override fun getEditorTitle(): String = Bundle.message("configuration.section.name")

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        return
    }

    override fun dispose() {
        service<ProjectManager>().openProjects.map {
            it.service<RunManager>()
        }.forEach {
            it.allConfigurationsList.filterIsInstance<UserDataHolder>()
                .forEach { holder ->
                    holder.putUserData(HotSwapRunConfigurationDataKey.KEY, null)
                }
        }
    }
}