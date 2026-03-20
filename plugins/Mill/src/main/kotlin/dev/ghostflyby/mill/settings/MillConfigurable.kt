/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
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

package dev.ghostflyby.mill.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import dev.ghostflyby.mill.Bundle
import javax.swing.JComponent

internal class MillConfigurable(
    private val project: Project,
) : SearchableConfigurable {
    private var disposable: Disposable? = null
    private var panel: DialogPanel? = null
    private var viewModel: MillConfigurableViewModel? = null

    override fun getId(): String = ID

    override fun getDisplayName(): String = Bundle.message("settings.display.name")

    override fun createComponent(): JComponent {
        if (panel == null) {
            val currentDisposable = Disposer.newDisposable()
            val currentViewModel = MillConfigurableViewModel(
                linkedProjectSettings = MillSettings.getInstance(project).linkedProjectsSettings,
                parentDisposable = currentDisposable,
            )
            val currentPanel = millConfigurableView(project, currentViewModel)
            currentPanel.registerValidators(currentDisposable)

            disposable = currentDisposable
            viewModel = currentViewModel
            panel = currentPanel
        }
        return requireNotNull(panel)
    }

    override fun isModified(): Boolean {
        return viewModel?.isModified(MillSettings.getInstance(project).linkedProjectsSettings) == true
    }

    @Throws(ConfigurationException::class)
    override fun apply() {
        val currentPanel = panel ?: return
        val validationErrors = currentPanel.validateAll()
        if (validationErrors.isNotEmpty()) {
            throw ConfigurationException(validationErrors.first().message)
        }
        currentPanel.apply()
        val currentViewModel = viewModel ?: return
        currentViewModel.validateBeforeApply()
        currentViewModel.applyTo(MillSettings.getInstance(project))
    }

    override fun reset() {
        viewModel?.resetFrom(MillSettings.getInstance(project).linkedProjectsSettings)
    }

    override fun getPreferredFocusedComponent(): JComponent? = panel?.preferredFocusedComponent

    override fun disposeUIResources() {
        disposable?.let(Disposer::dispose)
        disposable = null
        panel = null
        viewModel = null
    }

    companion object {
        internal const val ID: String = "reference.settingsdialog.project.mill"
    }
}
