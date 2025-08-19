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

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import dev.ghostflyby.dcevm.Bundle
import javax.swing.JComponent

internal sealed class DCEVMConfigurable : Configurable {

    abstract val persistent: HotswapPersistent
    protected val state = HotswapConfigViewModel()

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
        return hotswapConfigView(state)
    }

    final override fun apply() {
        persistent.setFrom(state)
    }

}




internal class AppDCEVMConfigurable : DCEVMConfigurable() {
    override val persistent: HotswapPersistent = service<AppSettings>()
    override fun createComponent(): JComponent {
        state.setFrom(persistent)
        return hotswapConfigView(state.apply { inheritEnable = false })
    }
}

internal class ProjectUserDCEVMConfigurable(project: Project) :
    DCEVMConfigurable() {
    override val persistent: HotswapPersistent = project.service<ProjectUserSettings>()
}

internal class ProjectSharedDCEVMConfigurable(project: Project) :
    DCEVMConfigurable() {
    override val persistent: HotswapPersistent = project.service<ProjectSharedSettings>()
}
