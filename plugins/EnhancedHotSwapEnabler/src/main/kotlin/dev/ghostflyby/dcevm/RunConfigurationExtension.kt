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

package dev.ghostflyby.dcevm

import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.util.removeUserData
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import javax.swing.JComponent

internal class HotSwapRunConfigurationExtension : com.intellij.execution.RunConfigurationExtension() {

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean =
        true

    override fun readExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        val child = element.getChild("hotSwapEnabler") ?: return
        val deserialized = try {
            XmlSerializer.deserialize(child, HotSwapConfigState::class.java)
        } catch (_: Throwable) {
            null
        }
        runConfiguration.putUserData(HotSwapRunConfigurationDataKey.KEY, deserialized)
    }

    override fun writeExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        val state = runConfiguration.getUserData(HotSwapRunConfigurationDataKey.KEY) ?: return
        if (state.enable == null && state.enableHotswapAgent == null) return
        val child = element.getOrCreateChild("hotSwapEnabler")
        XmlSerializer.serializeInto(HotSwapConfigState(state.enable, state.enableHotswapAgent), child)
    }

    override fun cleanUserData(runConfigurationBase: RunConfigurationBase<*>) {
        runConfigurationBase.removeUserData(HotSwapRunConfigurationDataKey.KEY)
    }

    override fun <P : RunConfigurationBase<*>> createEditor(configuration: P): SettingsEditor<P?> {
        return object : SettingsEditor<P?>() {
            private var model = HotSwapConfigState()
            private val ui = createHotSwapPanelAndControls(model)

            override fun resetEditorFrom(s: P) {
                val st = configuration.getUserData(HotSwapRunConfigurationDataKey.KEY)
                if (st != null) model.setFrom(st)
            }

            override fun applyEditorTo(s: P) {
                val newState = model.copy()
                configuration.putUserData(HotSwapRunConfigurationDataKey.KEY, newState)
            }

            override fun createEditor(): JComponent = ui
        }
    }

    override fun getEditorTitle(): String = "Enhanced HotSwap"

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        return
    }
}
