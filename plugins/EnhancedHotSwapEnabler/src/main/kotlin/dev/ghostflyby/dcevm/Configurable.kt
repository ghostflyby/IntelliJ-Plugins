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
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.ThreeStateCheckBox
import javax.swing.JComponent

internal sealed class DCEVMConfigurable : Configurable {

    abstract val persistent: HotSwapPersistent
    protected val state: HotSwapConfigState = HotSwapConfigState()

    override fun getDisplayName(): @NlsContexts.ConfigurableName String {
        return "DCEVM and Hotswap Agent"
    }

    final override fun isModified(): Boolean {
        return persistent.enable != state.enable ||
                persistent.enableHotswapAgent != state.enableHotswapAgent
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

private fun Boolean?.toState(): ThreeStateCheckBox.State = when (this) {
    true -> ThreeStateCheckBox.State.SELECTED
    false -> ThreeStateCheckBox.State.NOT_SELECTED
    null -> ThreeStateCheckBox.State.DONT_CARE
}

private fun ThreeStateCheckBox.State.toBool(): Boolean? = when (this) {
    ThreeStateCheckBox.State.SELECTED -> true
    ThreeStateCheckBox.State.NOT_SELECTED -> false
    ThreeStateCheckBox.State.DONT_CARE -> null
}

internal fun createHotSwapPanelAndControls(
    model: HotSwapConfigMutable,
) =
    panel {
        row {
            threeStateCheckBox("Enable DCEVM").applyToComponent {
                state = model.enable.toState()
            }.onChanged {
                model.enable = it.state.toBool()
            }.component
        }
        row {
            threeStateCheckBox("Enable hotswap agent").applyToComponent {
                state = model.enableHotswapAgent.toState()
            }.onChanged {
                model.enableHotswapAgent = it.state.toBool()
            }.component
        }
    }


internal fun createHotSwapPanelTwoState(
    model: HotSwapConfigMutable,
) =
    panel {
        row {
            checkBox("Enable DCEVM").applyToComponent {
                isSelected = (model.enable == true)
            }.onChanged {
                model.enable = it.isSelected
            }.component
        }
        row {
            checkBox("Enable hotswap agent").applyToComponent {
                isSelected = (model.enableHotswapAgent == true)
            }.onChanged {
                model.enableHotswapAgent = it.isSelected
            }.component
        }
    }

internal class AppDCEVMConfigurable : DCEVMConfigurable() {
    override val persistent: HotSwapPersistent = service<AppSettings>()
    override fun createComponent(): JComponent {
        state.setFrom(persistent)
        return createHotSwapPanelTwoState(state)
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
