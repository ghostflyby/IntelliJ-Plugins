/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill

import com.intellij.openapi.externalSystem.service.task.ui.AbstractExternalSystemToolWindowFactory
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemSettings
import com.intellij.openapi.project.Project
import dev.ghostflyby.mill.settings.MillSettings

internal class MillToolWindowFactory : AbstractExternalSystemToolWindowFactory(MillConstants.systemId) {
    override fun getSettings(project: Project): AbstractExternalSystemSettings<*, *, *> {
        return MillSettings.getInstance(project)
    }
}
