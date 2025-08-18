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

import com.intellij.openapi.components.*
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolder

internal interface HotSwapConfigLike {
    val inherit: Boolean
    val enable: Boolean
    val enableHotswapAgent: Boolean
}

internal interface HotSwapConfigMutable : HotSwapConfigLike {
    override var inherit: Boolean
    override var enable: Boolean
    override var enableHotswapAgent: Boolean
    fun setFrom(other: HotSwapConfigLike): HotSwapConfigMutable {
        enable = other.enable
        enableHotswapAgent = other.enableHotswapAgent
        inherit = other.inherit
        return this
    }
}

internal data class HotSwapConfigState(
    override var inherit: Boolean = true,
    override var enable: Boolean = false,
    override var enableHotswapAgent: Boolean = false,
) : HotSwapConfigMutable

internal class HotSwapConfigViewModel(
    inherit: Boolean = true,
    enable: Boolean = false,
    enableHotswapAgent: Boolean = false,
) : HotSwapConfigMutable {
    private val graph = PropertyGraph("HotSwapConfigViewModel")
    val inheritProperty = graph.property(inherit)
    val enableProperty = graph.property(enable)
    val enableEditableProperty = graph.property(!inherit).apply {
        dependsOn(inheritProperty) {
            !inheritProperty.get()
        }
    }
    val enableHotswapAgentProperty = graph.property(enableHotswapAgent)
    val enableHotswapAgentEditableProperty = graph.property(!inherit && enable).apply {
        fun reset(): Boolean {
            return !inheritProperty.get() && enableProperty.get()
        }
        dependsOn(enableEditableProperty, ::reset)
        dependsOn(enableProperty, ::reset)
    }

    override var inherit by inheritProperty

    override var enable by enableProperty
    override var enableHotswapAgent by enableHotswapAgentProperty
}

internal object HotSwapRunConfigurationDataKey {
    val KEY: Key<HotSwapConfigMutable> = Key.create("HotSwapEnabler.State")
}

internal data class ResolvedHotSwapConfig(
    val enable: Boolean,
    val enableHotswapAgent: Boolean,
)

internal fun effectiveHotSwapConfig(
    profile: UserDataHolder?,
    project: Project?,
): ResolvedHotSwapConfig {
    val config = sequence {
        yield(profile?.getUserData(HotSwapRunConfigurationDataKey.KEY))
        yield(project?.service<ProjectUserSettings>())
        yield(project?.service<ProjectSharedSettings>())
        yield(service<AppSettings>())
    }
        .filterNotNull().filter {
            !it.inherit
        }.map {
            ResolvedHotSwapConfig(
                enable = it.enable,
                enableHotswapAgent = it.enableHotswapAgent
            )
        }.first()

    return config
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

    override var inherit
        get() = state.inherit
        set(value) {
            updateState {
                it.copy(inherit = value)
            }
        }
}

@Service
@State(name = "HotSwapEnabler", storages = [Storage("HotSwapEnabler.xml")])
internal class AppSettings : HotSwapPersistent(HotSwapConfigState(false, enable = true, enableHotswapAgent = true))

@Service(Service.Level.PROJECT)
@State(name = "HotSwapEnablerShared", storages = [Storage("HotSwapEnabler.xml")])
internal class ProjectSharedSettings : HotSwapPersistent(HotSwapConfigState())

@Service(Service.Level.PROJECT)
@State(name = "HotSwapEnablerUser", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class ProjectUserSettings : HotSwapPersistent(HotSwapConfigState())
