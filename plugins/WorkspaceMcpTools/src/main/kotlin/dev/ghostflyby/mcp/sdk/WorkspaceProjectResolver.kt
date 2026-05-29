/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager.getInstance
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.ghostflyby.mcp.sdk.WorkspaceMcpSdkServerSettings
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

internal enum class WorkspaceProjectResolutionReason {
    EXPLICIT_PROJECT_KEY,
    EXPLICIT_PROJECT_PATH,
    EXPLICIT_PROJECT_NAME,
    RAW_VFS_URL,
    RELATIVE_PATH,
    SINGLE_OPEN_PROJECT,
}

/**
 * Resolves the stable instanceKey for this IDE application instance.
 * Uses product code lowercase + configured port, e.g. "iu-63341".
 */
internal fun workspaceInstanceKey(): String {
    val productCode = ApplicationInfo.getInstance().build.productCode.lowercase()
    val port = service<WorkspaceMcpSdkServerSettings>().port
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

internal interface WorkspaceProjectProvider {
    fun openProjects(): List<Project>

    suspend fun resolve(
        projectKey: String? = null,
        projectPath: String? = null,
        rawVfsUrl: String? = null,
        relativePath: String? = null,
        rootsCandidates: List<String>? = null,
    ): WorkspaceProjectResolution
}

@Service(Service.Level.APP)
internal class WorkspaceProjectResolver : WorkspaceProjectProvider {

    override fun openProjects(): List<Project> {
        return getInstance().openProjects
            .filterNot { it.isDisposed }
            .sortedBy { it.basePath ?: it.name }
    }

    /**
     * Enhanced resolver supporting projectKey, projectPath, rawVfsUrl, relativePath, and single-project fallback.
     */
    override suspend fun resolve(
        projectKey: String?,
        projectPath: String?,
        rawVfsUrl: String?,
        relativePath: String?,
        rootsCandidates: List<String>?,
    ): WorkspaceProjectResolution {
        // 1. explicit projectKey
        projectKey?.takeIf { it.isNotBlank() }?.let { key ->
            findByProjectKey(key)?.let { project ->
                return WorkspaceProjectResolution.Resolved(project, WorkspaceProjectResolutionReason.EXPLICIT_PROJECT_KEY)
            }
        }

        // 2. explicit projectPath
        projectPath?.takeIf { it.isNotBlank() }?.let { path ->
            findByProjectPath(path)?.let { project ->
                return WorkspaceProjectResolution.Resolved(project, WorkspaceProjectResolutionReason.EXPLICIT_PROJECT_PATH)
            }
        }

        // 3. rawVfsUrl
        rawVfsUrl?.takeIf { it.isNotBlank() }?.let { url ->
            findByRawVfsUrl(url)?.let { project ->
                return WorkspaceProjectResolution.Resolved(project, WorkspaceProjectResolutionReason.RAW_VFS_URL)
            }
        }

        // 4. relativePath + roots candidates
        if (relativePath != null && rootsCandidates != null) {
            val projects = openProjects()
            val normalizedRoots = rootsCandidates.map { normalizePath(it) }
            val candidates = projects.filter { project ->
                val bp = project.basePath?.let(::normalizePath) ?: return@filter false
                // bidirectional prefix match: project base path is a root, or root is inside project
                normalizedRoots.any { root -> bp == root || bp.startsWith(root) || root.startsWith(bp) }
            }
            // Among matching projects, verify relativePath file actually exists
            val withFile = candidates.filter { project ->
                val bp = project.basePath ?: return@filter false
                Path.of(bp, relativePath).exists()
            }
            val chosen = if (withFile.size == 1) withFile.single()
            else if (withFile.isEmpty() && candidates.size == 1) candidates.single()
            else null
            if (chosen != null) {
                return WorkspaceProjectResolution.Resolved(chosen, WorkspaceProjectResolutionReason.RELATIVE_PATH)
            }
        }

        // 5. single project fallback
        val projects = openProjects()
        return when (projects.size) {
            0 -> WorkspaceProjectResolution.Unresolved("No open IntelliJ projects are available.")
            1 -> WorkspaceProjectResolution.Resolved(projects.single(), WorkspaceProjectResolutionReason.SINGLE_OPEN_PROJECT)
            else -> {
                val candidates = projects.joinToString("; ") { p ->
                    "'${workspaceProjectKey(p)}' (${p.name}, ${p.basePath ?: "?"})"
                }
                WorkspaceProjectResolution.Unresolved(
                    "Multiple IntelliJ projects are open. Provide an explicit project key or project path. Candidates: $candidates",
                )
            }
        }
    }

    private fun findByProjectKey(key: String): Project? {
        return openProjects().firstOrNull { workspaceProjectKey(it) == key }
    }

    private fun findByProjectPath(projectPath: String): Project? {
        val normalized = normalizePath(projectPath)
        return openProjects().firstOrNull { project ->
            project.basePath?.let { normalizePath(it) } == normalized
        }
    }

    private suspend fun findByRawVfsUrl(rawVfsUrl: String): Project? {
        val file = service<VirtualFileManager>().findFileByUrl(rawVfsUrl) ?: return null
        return readAction {
            openProjects()
                .filter { project -> project.owns(file) }
                .maxByOrNull { project -> project.basePath?.length ?: 0 }
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
}

internal sealed class WorkspaceProjectResolution {
    data class Resolved(
        val project: Project,
        val reason: WorkspaceProjectResolutionReason,
    ) : WorkspaceProjectResolution()

    data class Unresolved(
        val message: String,
    ) : WorkspaceProjectResolution()
}
