/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service
@State(
    name = "WorkspaceAgentBridge",
    storages = [Storage("workspace-agent-bridge.xml", roamingType = RoamingType.LOCAL)],
)
// Lives in the frontend content module: the notify-once bookkeeping is per machine, which in a
// split-mode setup is the frontend (JetBrains Client) process.
internal class WorkspaceMcpSdkServerSettings : PersistentStateComponent<WorkspaceMcpSdkServerSettings.State> {

    private var myState = State()

    var previousVersion: String
        get() = myState.previousVersion
        set(value) {
            myState = myState.copy(previousVersion = value)
        }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    internal data class State(
        val previousVersion: String = "",
    )
}
