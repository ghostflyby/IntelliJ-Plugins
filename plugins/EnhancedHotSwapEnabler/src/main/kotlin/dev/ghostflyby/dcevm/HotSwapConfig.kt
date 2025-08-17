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

internal interface HotSwapConfigWithDefault : HotSwapConfig {
    override val enable: Boolean
    override val enableHotswapAgent: Boolean
}

internal interface HotSwapConfigMutable : HotSwapConfig {
    override var enable: Boolean?
    override var enableHotswapAgent: Boolean?
    fun setFrom(other: HotSwapConfig) {
        enable = other.enable ?: enable
        enableHotswapAgent = other.enableHotswapAgent ?: enableHotswapAgent
    }
}

internal interface HotSwapConfig {
    val enable: Boolean?
    val enableHotswapAgent: Boolean?

}

internal data class HotSwapConfigState(
    override val enable: Boolean? = null,
    override val enableHotswapAgent: Boolean? = null,
) : HotSwapConfig {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HotSwapConfig) return false
        return enable == other.enable && enableHotswapAgent == other.enableHotswapAgent
    }

    override fun hashCode(): Int {
        var result = enable?.hashCode() ?: 0
        result = 31 * result + (enableHotswapAgent?.hashCode() ?: 0)
        return result
    }
}


internal abstract class HotSwapPersistent(config: HotSwapConfigState) :
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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HotSwapConfig) return false
        return enable == other.enable && enableHotswapAgent == other.enableHotswapAgent
    }

    override fun hashCode(): Int {
        var result = enable?.hashCode() ?: 0
        result = 31 * result + (enableHotswapAgent?.hashCode() ?: 0)
        return result
    }
}

@Service(Service.Level.APP)
@State(name = "HotswapApp", storages = [Storage("HotSwapEnabler.xml")])
internal class AppSettings : HotSwapPersistent(HotSwapConfigState(true, enableHotswapAgent = true))

internal open class BranchSettings : HotSwapPersistent(HotSwapConfigState())

@Service
@State(name = "HotswapProjectShared", storages = [Storage("HotSwapEnabler.xml")])
internal class ProjectSharedSettings : BranchSettings()

@Service
@State(name = "HotswapProjectShared", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class ProjectUserSettings : BranchSettings()

