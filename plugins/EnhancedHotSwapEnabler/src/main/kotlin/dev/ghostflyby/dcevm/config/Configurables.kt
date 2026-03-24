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

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.ghostflyby.dcevm.Bundle
import javax.swing.JComponent

internal sealed class SingleLayerDCEVMConfigurable : Configurable {

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


internal class AppDCEVMConfigurable : SingleLayerDCEVMConfigurable() {
    override val persistent: HotswapPersistent = service<AppSettings>()
    override fun createComponent(): JComponent {
        state.setFrom(persistent)
        return hotswapConfigView(state.apply { inheritEnable = false })
    }
}

internal class ProjectDCEVMConfigurable(project: Project) : Configurable {
    private val applicationPersistent: HotswapPersistent = service<AppSettings>()
    private val sharedPersistent: HotswapPersistent = project.service<ProjectSharedSettings>()
    private val workspacePersistent: HotswapPersistent = project.service<ProjectUserSettings>()
    private val state = ProjectDCEVMConfigViewModel(applicationPersistent)

    override fun getDisplayName(): @NlsContexts.ConfigurableName String {
        return Bundle.message("configuration.title.project")
    }

    override fun isModified(): Boolean {
        return state.isModified(sharedPersistent, workspacePersistent)
    }

    override fun reset() {
        state.reset(sharedPersistent, workspacePersistent)
    }

    override fun createComponent(): JComponent {
        reset()
        return panel {
            group(Bundle.message("configuration.group.project")) {
                hotswapConfigRows(
                    model = state.sharedState,
                    comment = Bundle.message("configuration.group.project.comment"),
                )
            }
            group(Bundle.message("configuration.group.workspace")) {
                hotswapConfigRows(
                    model = state.workspaceState,
                    comment = Bundle.message("configuration.group.workspace.comment"),
                )
            }
            group(Bundle.message("configuration.group.summary")) {
                row(Bundle.message("configuration.summary.source")) {
                    label("").bindText(state.summarySourceProperty)
                }.rowComment(Bundle.message("configuration.group.summary.comment"))
                row(Bundle.message("configuration.summary.enable")) {
                    label("").bindText(state.summaryEnableProperty)
                }
                row(Bundle.message("configuration.summary.hotswapAgent")) {
                    label("").bindText(state.summaryEnableHotswapAgentProperty)
                }
            }
        }
    }

    override fun apply() {
        state.apply(sharedPersistent, workspacePersistent)
    }
}
