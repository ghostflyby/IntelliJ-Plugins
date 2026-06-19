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

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.components.*

@Service
@State(
    name = "WorkspaceMcpSdkServerSettings",
    storages = [Storage("WorkspaceMcpSdkServerSettings.xml", roamingType = RoamingType.LOCAL)],
)
internal class WorkspaceMcpSdkServerSettings :
    SerializablePersistentStateComponent<WorkspaceMcpSdkServerSettings.State>(State()) {

    var codexSkillNotifiedVersion: String
        get() = state.codexSkillNotifiedVersion
        set(v) {
            updateState { state.copy(codexSkillNotifiedVersion = v) }
        }

    internal var port: Int
        get() = state.port
        set(value) {
            updateState { state.copy(port = value) }
        }

    internal data class State(
        @JvmField val port: Int = 63341,
        @JvmField val codexSkillNotifiedVersion: String = "",
    )
}
