/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.ghostflyby.mcp.sdk.WorkspaceMcpSessionStateService
import dev.ghostflyby.mcp.server.WorkspaceMcpStateFlows

@Service(Service.Level.APP)
internal class FileContentDocumentListenerService : Disposable.Default {
    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (!service<WorkspaceMcpSessionStateService>().hasResourceSubscriptions()) return
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    service<WorkspaceMcpStateFlows>().resourceContentChanged(fileContentResourceUrisForFile(file))
                }
            },
            this,
        )
    }
}
