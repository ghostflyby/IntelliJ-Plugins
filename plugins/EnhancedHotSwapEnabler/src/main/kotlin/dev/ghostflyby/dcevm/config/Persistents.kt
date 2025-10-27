/*
 * Copyright (c) 2025 ghostflyby
 * SPDX-FileCopyrightText: 2025 ghostflyby
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

import com.intellij.openapi.components.*

internal sealed class HotswapPersistent(config: HotswapConfigState) :
    SerializablePersistentStateComponent<HotswapConfigState>(
        config
    ), HotswapConfig {
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
@State(name = configKey, storages = [Storage("$configKey.xml")])
internal class AppSettings : HotswapPersistent(HotswapConfigState(false, enable = true, enableHotswapAgent = true)) {
    override var inherit: Boolean
        get() = false
        @Suppress("unused")
        set(value) {
        }
}

@Service(Service.Level.PROJECT)
@State(name = "${configKey}Project", storages = [Storage("$configKey.xml")])
internal class ProjectSharedSettings : HotswapPersistent(HotswapConfigState())

@Service(Service.Level.PROJECT)
@State(name = "${configKey}Workspace", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
internal class ProjectUserSettings : HotswapPersistent(HotswapConfigState())
