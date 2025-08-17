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

import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfile
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

internal interface HotSwapConfigLike {
    val enable: Boolean?
    val enableHotswapAgent: Boolean?
}

internal interface HotSwapConfigMutable : HotSwapConfigLike {
    override var enable: Boolean?
    override var enableHotswapAgent: Boolean?
    fun setFrom(other: HotSwapConfigLike) {
        enable = other.enable ?: enable
        enableHotswapAgent = other.enableHotswapAgent ?: enableHotswapAgent
    }
}

internal data class HotSwapConfigState(
    override var enable: Boolean? = null,
    override var enableHotswapAgent: Boolean? = null,
) : HotSwapConfigMutable

internal object HotSwapRunConfigurationDataKey {
    val KEY: Key<HotSwapConfigState> = Key.create("HotSwapEnabler.State")
}

internal data class ResolvedHotSwapConfig(
    val enable: Boolean,
    val enableHotswapAgent: Boolean,
)

internal fun effectiveHotSwapConfig(
    profile: RunProfile?,
    project: Project?,
): ResolvedHotSwapConfig {
    val app = service<AppSettings>()
    val projectUser = project?.getService(ProjectUserSettings::class.java)
    val projectShared = project?.getService(ProjectSharedSettings::class.java)
    val runState = (profile as? RunConfigurationBase<*>)?.getUserData(HotSwapRunConfigurationDataKey.KEY)

    val enable = runState?.enable
        ?: projectUser?.enable
        ?: projectShared?.enable
        ?: app.enable

    val enableHotswapAgent = runState?.enableHotswapAgent
        ?: projectUser?.enableHotswapAgent
        ?: projectShared?.enableHotswapAgent
        ?: app.enableHotswapAgent

    return ResolvedHotSwapConfig(
        enable = enable ?: false,
        enableHotswapAgent = enableHotswapAgent ?: false,
    )
}


internal sealed class HotSwapPersistent(config: HotSwapConfigState) :
    SerializablePersistentStateComponent<HotSwapConfigState>(
        config
    ), HotSwapConfigMutable {
    override var enable
        get() = state.enable
        set(value) {
            updateState {
                it.copy(enable = value)
            }
        }
    override var enableHotswapAgent
        get() = state.enableHotswapAgent
        set(value) {
            updateState {
                it.copy(enableHotswapAgent = value)
            }
        }
}

@Service
@State(name = "HotSwapEnabler", storages = [Storage("HotSwapEnabler.xml")])
internal class AppSettings : HotSwapPersistent(HotSwapConfigState(true, enableHotswapAgent = true))

@Service(Service.Level.PROJECT)
@State(name = "HotSwapEnabler", storages = [Storage("HotSwapEnabler.xml")])
internal class ProjectSharedSettings : HotSwapPersistent(HotSwapConfigState())

@Service(Service.Level.PROJECT)
@State(name = "HotSwapEnabler", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class ProjectUserSettings : HotSwapPersistent(HotSwapConfigState())
