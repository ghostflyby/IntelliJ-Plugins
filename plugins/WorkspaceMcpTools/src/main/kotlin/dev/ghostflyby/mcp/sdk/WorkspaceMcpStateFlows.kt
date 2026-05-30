/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.components.Service
import dev.ghostflyby.mcp.server.PerSessionListChange
import dev.ghostflyby.mcp.server.ResourceChangeNotifier
import dev.ghostflyby.mcp.server.ResourceUpdateState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@Service(Service.Level.APP)
internal class WorkspaceMcpStateFlows : ResourceChangeNotifier {
    private val _resourceUpdates = MutableStateFlow(ResourceUpdateState())
    override val resourceUpdates: StateFlow<ResourceUpdateState> = _resourceUpdates.asStateFlow()

    private val _globalResourceListChanges = MutableStateFlow(0L)
    override val globalResourceListChanges: StateFlow<Long> = _globalResourceListChanges.asStateFlow()

    private val _perSessionResourceListChanges = MutableStateFlow(PerSessionListChange())
    override val perSessionResourceListChanges: StateFlow<PerSessionListChange> =
        _perSessionResourceListChanges.asStateFlow()

    fun resourceContentChanged(uris: Set<String>) {
        if (uris.isEmpty()) return
        _resourceUpdates.update { it.next(uris) }
    }

    fun globalResourcesChanged() {
        _globalResourceListChanges.update { it + 1 }
    }

    override fun sessionResourcesChanged(sessionId: String) {
        if (sessionId.isBlank()) return
        _perSessionResourceListChanges.update { it.next(sessionId) }
    }
}
