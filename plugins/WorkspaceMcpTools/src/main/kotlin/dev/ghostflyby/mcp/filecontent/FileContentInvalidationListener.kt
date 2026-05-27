/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.asDisposable
import dev.ghostflyby.mcp.sdk.WorkspaceMcpInvalidationSink
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import dev.ghostflyby.mcp.sdk.workspaceInstanceKey
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class FileContentInvalidationListener(
    private val projectResolver: WorkspaceProjectResolver,
    private val invalidationSink: WorkspaceMcpInvalidationSink,
    private val scope: CoroutineScope,
) {
    private val resourceUpdates = MutableStateFlow(ResourceUpdateState())

    fun install() {
        invalidationSink.registerResourceUpdates(resourceUpdates) { it.uris }

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    invalidateDocument(event.document)
                }
            },
            scope.asDisposable(),
        )

        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    val uris = events.mapNotNull { it.file }
                        .flatMap(::resourceUrisForFile)
                        .toSet()
                    if (uris.isNotEmpty()) {
                        resourceUpdates.update { it.next(uris) }
                    }
                }
            },
        )
    }

    private fun invalidateDocument(document: Document) {
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        val uris = resourceUrisForFile(file).toSet()
        if (uris.isNotEmpty()) {
            resourceUpdates.update { it.next(uris) }
        }
    }

    private fun resourceUrisForFile(file: VirtualFile): List<String> {
        val instanceKey = workspaceInstanceKey()
        val result = mutableListOf(workspaceRawVfsUri(instanceKey, file.url))
        result += projectRelativeUris(instanceKey, file)
        return result
    }

    private fun projectRelativeUris(instanceKey: String, file: VirtualFile): List<String> {
        return projectResolver.openProjects().mapNotNull { project ->
            val basePath = project.basePath ?: return@mapNotNull null
            val relativePath = relativePathUnder(file.path, basePath) ?: return@mapNotNull null
            workspaceProjectFileUri(
                instanceKey = instanceKey,
                projectKey = workspaceProjectKey(project),
                relativePath = relativePath,
            )
        }
    }

    private fun relativePathUnder(path: String, basePath: String): String? {
        val normalizedBase = basePath.trimEnd('/')
        return when {
            path == normalizedBase -> null
            path.startsWith("$normalizedBase/") -> path.removePrefix("$normalizedBase/")
            else -> null
        }
    }
}

private data class ResourceUpdateState(
    val generation: Long = 0L,
    val uris: Set<String> = emptySet(),
) {
    fun next(uris: Set<String>): ResourceUpdateState = copy(
        generation = generation + 1,
        uris = uris,
    )
}
