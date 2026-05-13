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

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "WorkspaceMcpSdkServerSettings",
    storages = [Storage("workspace-mcp-sdk.xml")],
)
internal class WorkspaceMcpSdkServerSettings :
    SerializablePersistentStateComponent<WorkspaceMcpSdkServerSettings.State>(State()) {

    internal val port: Int
        get() = workspaceMcpPortProperty()?.takeIf(::isValidPort) ?: state.port

    internal data class State(
        val port: Int = DEFAULT_PORT,
    )

    private companion object {
        private const val DEFAULT_PORT = 63341
        private const val PORT_PROPERTY = "dev.ghostflyby.mcp.workspace.port"

        private fun workspaceMcpPortProperty(): Int? {
            return System.getProperty(PORT_PROPERTY)?.toIntOrNull()
        }

        private fun isValidPort(port: Int): Boolean {
            return port in 1..65535
        }
    }
}
