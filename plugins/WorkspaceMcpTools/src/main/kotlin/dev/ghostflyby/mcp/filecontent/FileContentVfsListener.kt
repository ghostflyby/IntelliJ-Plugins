/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import dev.ghostflyby.mcp.sdk.WorkspaceMcpSessionStateService
import dev.ghostflyby.mcp.sdk.WorkspaceMcpStateFlows

internal class FileContentVfsListener : AsyncFileListener {
    override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
        if (!service<WorkspaceMcpSessionStateService>().hasResourceSubscriptions()) return null
        val uris = events.mapNotNull { it.file }
            .flatMap { fileContentResourceUrisForFile(it) }
            .toSet()
        if (uris.isEmpty()) return null
        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                service<WorkspaceMcpStateFlows>().resourceContentChanged(uris)
            }
        }
    }
}
