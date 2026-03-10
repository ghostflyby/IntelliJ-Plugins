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

package dev.ghostflyby.mill

import com.intellij.openapi.externalSystem.service.settings.AbstractExternalSystemConfigurable
import com.intellij.openapi.externalSystem.util.ExternalSystemSettingsControl
import com.intellij.openapi.project.Project

internal class MillConfigurable(project: Project) :
    AbstractExternalSystemConfigurable<MillProjectSettings, MillSettingsListener, MillSettings>(
        project,
        MillConstants.systemId,
    ) {
    override fun createProjectSettingsControl(settings: MillProjectSettings): ExternalSystemSettingsControl<MillProjectSettings> {
        return MillProjectSettingsControl(settings)
    }

    override fun createSystemSettingsControl(settings: MillSettings): ExternalSystemSettingsControl<MillSettings>? =
        null

    override fun newProjectSettings(): MillProjectSettings = MillProjectSettings()

    override fun getId(): String = ID

    companion object {
        internal const val ID: String = "reference.settingsdialog.project.mill"
    }
}
