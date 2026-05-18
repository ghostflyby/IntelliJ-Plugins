/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.vfs.resources

import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mcp.sdk.WorkspaceMcpSdkServerService

/**
 * Notifies the MCP SDK server when the project-level resource list may have changed.
 *
 * These listeners cover project lifecycle (open/close), file editor (open/close),
 * and module root changes — all of which can affect the set of available
 * workspace resources.
 */
internal object WorkspaceMcpProjectLifecycleListener : ProjectManagerListener {
    override fun projectClosed(project: Project) {
        service<WorkspaceMcpSdkServerService>().scheduleResourceListChanged()
    }
}

internal object WorkspaceMcpFileEditorListener : FileEditorManagerListener {
    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        service<WorkspaceMcpSdkServerService>().scheduleResourceListChanged()
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        service<WorkspaceMcpSdkServerService>().scheduleResourceListChanged()
    }
}

internal object WorkspaceMcpModuleRootListener : ModuleRootListener {
    override fun rootsChanged(event: ModuleRootEvent) {
        service<WorkspaceMcpSdkServerService>().scheduleResourceListChanged()
    }
}

