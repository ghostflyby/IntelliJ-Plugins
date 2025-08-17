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

internal sealed class DCEVMConfigurable : Configurable, HotSwapConfigMutable {

    abstract val persistent: HotSwapPersistent

    override fun getDisplayName(): @NlsContexts.ConfigurableName String {
        return "DCEVM and Hotswap Agent"
    }

    final override fun isModified(): Boolean {
        return modified
    }

    private var modified = false

    private var ui: HotSwapPanelUi? = null
    final override fun reset() {
        this.setFrom(persistent)
        ui?.refreshFrom(this)
    }

    override fun createComponent(): JComponent {
        val ui = createHotSwapPanelAndControls(this) { modified = true }
        this.ui = ui
        return ui.panel
    }

    final override fun apply() {
        persistent.setFrom(this)
    }

    override var enable: Boolean? = null
    override var enableHotswapAgent: Boolean? = null
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

internal data class HotSwapPanelUi(
    val panel: JComponent,
    val dcevm: ThreeStateCheckBox,
    val agent: ThreeStateCheckBox,
) {
    fun refreshFrom(model: HotSwapConfigMutable) {
        dcevm.state = model.enable.toState()
        agent.state = model.enableHotswapAgent.toState()
    }
}

internal fun createHotSwapPanelAndControls(
    model: HotSwapConfigMutable,
    onChange: () -> Unit = {},
): HotSwapPanelUi {
    lateinit var dcevm: ThreeStateCheckBox
    lateinit var agent: ThreeStateCheckBox
    val panel = panel {
        row {
            dcevm = threeStateCheckBox("Enable DCEVM").applyToComponent {
                state = model.enable.toState()
            }.onChanged {
                model.enable = dcevm.state.toBool()
                onChange()
            }.component
        }
        row {
            agent = threeStateCheckBox("Enable hotswap agent").applyToComponent {
                state = model.enableHotswapAgent.toState()
            }.onChanged {
                model.enableHotswapAgent = agent.state.toBool()
                onChange()
            }.component
        }
    }
    return HotSwapPanelUi(panel, dcevm, agent)
}

internal class AppDCEVMConfigurable : DCEVMConfigurable() {
    override val persistent: HotSwapPersistent = service<AppSettings>()
}

internal class ProjectUserDCEVMConfigurable(project: Project) :
    DCEVMConfigurable() {
    override val persistent: HotSwapPersistent = project.service<ProjectUserSettings>()
}

internal class ProjectSharedDCEVMConfigurable(project: Project) :
    DCEVMConfigurable() {
    override val persistent: HotSwapPersistent = project.service<ProjectSharedSettings>()
}
