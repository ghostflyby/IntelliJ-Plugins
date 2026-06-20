/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager.getInstance
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

/**
 * Resolves the stable instanceKey for this IDE application instance.
 * Uses product code lowercase + configured port, e.g. "iu-63341".
 */
internal fun workspaceInstanceKey(): String {
    val productCode = ApplicationInfo.getInstance().build.productCode.lowercase()
    val port = service<WorkspaceMcpSdkServerService>().port
    return "$productCode-$port"
}

/**
 * Computes a stable projectKey for a Project.
 * Uses slug(project.name/basePath) + short hash(Canonical basePath/location).
 */
internal fun workspaceProjectKey(project: Project): String {
    val slug = (project.name.takeIf { it.isNotBlank() } ?: "unnamed")
        .replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        .take(48)
    val basePath = project.basePath ?: project.projectFilePath ?: project.name
    val digest = MessageDigest.getInstance("SHA-256").digest(basePath.toByteArray())
    val shortHash = digest.take(4).joinToString("") { "%02x".format(it) }
    return "$slug-$shortHash"
}

internal data class WorkspaceProjectHints(
    val projectKey: String? = null,
    val projectPath: String? = null,
    val rawVfsUrl: String? = null,
    val relativePath: String? = null,
    val rootsCandidates: List<String> = emptyList(),
)

internal fun openWorkspaceProjects(): List<Project> {
    return getInstance().openProjects
        .filterNot { it.isDisposed }
        .sortedBy { it.basePath ?: it.name }
}

internal suspend fun resolveWorkspaceProject(hints: WorkspaceProjectHints): Project? {
    hints.projectKey?.takeIf { it.isNotBlank() }?.let { key ->
        findByProjectKey(key)?.let { project -> return project }
    }

    hints.projectPath?.takeIf { it.isNotBlank() }?.let { path ->
        findByProjectPath(path)?.let { project -> return project }
    }

    hints.rawVfsUrl?.takeIf { it.isNotBlank() }?.let { url ->
        findByRawVfsUrl(url)?.let { project -> return project }
    }

    hints.relativePath?.let { relativePath ->
        findByRelativePath(relativePath, hints.rootsCandidates)?.let { project -> return project }
    }

    return openWorkspaceProjects().singleOrNull()
}

private fun findByProjectKey(key: String): Project? {
    return openWorkspaceProjects().firstOrNull { workspaceProjectKey(it) == key }
}

private fun findByProjectPath(projectPath: String): Project? {
    val normalized = normalizePath(projectPath)
    return openWorkspaceProjects().firstOrNull { project ->
        project.basePath?.let { normalizePath(it) } == normalized
    }
}

private suspend fun findByRawVfsUrl(rawVfsUrl: String): Project? {
    val file = service<VirtualFileManager>().findFileByUrl(rawVfsUrl) ?: return null
    return readAction {
        openWorkspaceProjects()
            .filter { project -> project.owns(file) }
            .maxByOrNull { project -> project.basePath?.length ?: 0 }
    }
}

private fun findByRelativePath(relativePath: String, rootsCandidates: List<String>): Project? {
    if (rootsCandidates.isEmpty()) return null
    val normalizedRoots = rootsCandidates.map { normalizePath(it) }
    val candidates = openWorkspaceProjects().filter { project ->
        val basePath = project.basePath?.let(::normalizePath) ?: return@filter false
        normalizedRoots.any { root ->
            basePath == root || basePath.startsWith(root) || root.startsWith(basePath)
        }
    }
    val withFile = candidates.filter { project ->
        val basePath = project.basePath ?: return@filter false
        Path.of(basePath, relativePath).exists()
    }
    return if (withFile.size == 1) {
        withFile.single()
    } else if (withFile.isEmpty() && candidates.size == 1) {
        candidates.single()
    } else {
        null
    }
}

private fun Project.owns(file: VirtualFile): Boolean {
    return ProjectFileIndex.getInstance(this).isInContent(file) ||
            basePath?.let { file.path.startsWith(it) } == true
}

private fun normalizePath(path: String): String {
    return runCatching {
        val nioPath = Path.of(path)
        if (nioPath.exists()) {
            nioPath.toRealPath().absolutePathString()
        } else {
            nioPath.toAbsolutePath().normalize().absolutePathString()
        }
    }.getOrElse {
        path.trim().trimEnd('/')
    }
}
