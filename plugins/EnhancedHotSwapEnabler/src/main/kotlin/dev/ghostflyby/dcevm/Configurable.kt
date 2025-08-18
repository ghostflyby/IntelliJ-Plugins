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

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

internal sealed class DCEVMConfigurable : Configurable {

    abstract val persistent: HotSwapPersistent
    protected val state = HotSwapConfigViewModel()

    override fun getDisplayName(): @NlsContexts.ConfigurableName String {
        return Bundle.message("configuration.title")
    }

    final override fun isModified(): Boolean {
        return persistent.enable != state.enable ||
                persistent.enableHotswapAgent != state.enableHotswapAgent ||
                persistent.inherit != state.inherit
    }

    final override fun reset() {
        state.setFrom(persistent)
    }

    override fun createComponent(): JComponent {
        state.setFrom(persistent)
        return createHotSwapPanelAndControls(state)
    }

    final override fun apply() {
        persistent.setFrom(state)
    }

}


internal fun createHotSwapPanelAndControls(
    model: HotSwapConfigViewModel,
    inheritEnabled: Boolean = true,
) =
    panel {
        row {
            checkBox(Bundle.message("checkbox.settings.inherit.parent")).bindSelected(model.inheritProperty)
        }.visible(inheritEnabled)
        row {
            checkBox(Bundle.message("checkbox.enable")).bindSelected(model.enableProperty)
                .enabledIf(model.enableEditableProperty)
        }
        row {
            checkBox(Bundle.message("checkbox.hotswapAgent"))
                .bindSelected(model.enableHotswapAgentProperty)
                .enabledIf(model.enableHotswapAgentEditableProperty)
        }
    }


internal class AppDCEVMConfigurable : DCEVMConfigurable() {
    override val persistent: HotSwapPersistent = service<AppSettings>()
    override fun createComponent(): JComponent {
        state.setFrom(persistent)
        return createHotSwapPanelAndControls(state, false)
    }
}

internal class ProjectUserDCEVMConfigurable(project: Project) :
    DCEVMConfigurable() {
    override val persistent: HotSwapPersistent = project.service<ProjectUserSettings>()
}

internal class ProjectSharedDCEVMConfigurable(project: Project) :
    DCEVMConfigurable() {
    override val persistent: HotSwapPersistent = project.service<ProjectSharedSettings>()
}
