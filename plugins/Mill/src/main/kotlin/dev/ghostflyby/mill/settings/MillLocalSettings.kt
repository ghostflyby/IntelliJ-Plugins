/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.externalSystem.settings.AbstractExternalSystemLocalSettings
import com.intellij.openapi.project.Project
import dev.ghostflyby.mill.MillConstants

@Service(Service.Level.PROJECT)
@State(name = "MillLocalSettings", storages = [Storage(StoragePathMacros.CACHE_FILE)])
internal class MillLocalSettings(
    project: Project,
) : AbstractExternalSystemLocalSettings<MillLocalSettings.State>(MillConstants.systemId, project, State()),
    PersistentStateComponent<MillLocalSettings.State> {

    override fun getState(): State = state

    override fun loadState(state: State) {
        super.loadState(state)
    }

    class State : AbstractExternalSystemLocalSettings.State()

    companion object {
        @JvmStatic
        fun getInstance(project: Project): MillLocalSettings = project.service()
    }
}
