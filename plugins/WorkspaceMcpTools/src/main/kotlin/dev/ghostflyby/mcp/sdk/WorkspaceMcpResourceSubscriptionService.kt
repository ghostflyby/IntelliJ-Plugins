/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

internal class WorkspaceMcpResourceSubscriptionService(
    private val sessionState: WorkspaceMcpSessionState,
) {

    fun recordResourceSubscription(sessionId: String, resourceUri: String) {
        if (!isWorkspaceResourceUri(resourceUri)) return
        sessionState.recordResourceSubscription(sessionId, resourceUri)
    }

    fun removeResourceSubscription(sessionId: String, resourceUri: String) {
        sessionState.removeResourceSubscription(sessionId, resourceUri)
    }

    internal fun isSubscribed(sessionId: String, resourceUri: String): Boolean {
        if (!isWorkspaceResourceUri(resourceUri)) return false
        return sessionState.isSubscribed(sessionId, resourceUri)
    }


    internal companion object {
        private const val WORKSPACE_URI_SCHEME = "ij-workspace://"
        private const val PROJECTS_SEGMENT = "/projects/"
        private const val KIND_VFS = "vfs"
        private const val KIND_FILES = "files"

        private enum class Kind { FILES, VFS }

        private data class DecodedWorkspaceUri(
            val instanceKey: String,
            val projectKey: String?,
            val kind: Kind,
            val tail: String,
        )

        internal fun isWorkspaceResourceUri(uri: String): Boolean =
            tryDecodeWorkspaceResourceUri(uri) != null

        private fun tryDecodeWorkspaceResourceUri(uri: String): DecodedWorkspaceUri? {
            if (!uri.startsWith(WORKSPACE_URI_SCHEME)) return null
            val afterScheme = uri.removePrefix(WORKSPACE_URI_SCHEME)
            val vfsPrefix = "$KIND_VFS/"
            val firstSlash = afterScheme.indexOf('/')
            if (firstSlash < 0) return null
            val instanceKey = afterScheme.substring(0, firstSlash)
            if (instanceKey.isBlank()) return null
            val afterInstance = afterScheme.substring(firstSlash + 1)
            if (afterInstance.startsWith(vfsPrefix)) {
                val tail = afterInstance.removePrefix(vfsPrefix)
                if (tail.isBlank()) return null
                return DecodedWorkspaceUri(
                    instanceKey = instanceKey,
                    projectKey = null,
                    kind = Kind.VFS,
                    tail = tail,
                )
            }
            val projectsIdx = afterScheme.indexOf(PROJECTS_SEGMENT)
            if (projectsIdx < 0) return null
            val afterProjects = afterScheme.substring(projectsIdx + PROJECTS_SEGMENT.length)
            val projectSlash = afterProjects.indexOf('/')
            if (projectSlash < 0) return null
            val projectKey = afterProjects.substring(0, projectSlash)
            if (projectKey.isBlank()) return null
            val afterProjectKey = afterProjects.substring(projectSlash + 1)
            val kindEnd = afterProjectKey.indexOf('/')
            if (kindEnd < 0) return null
            val kindStr = afterProjectKey.substring(0, kindEnd)
            val kind = when (kindStr) {
                KIND_FILES -> Kind.FILES
                else -> return null
            }
            val tail = afterProjectKey.substring(kindEnd + 1)
            if (tail.isBlank()) return null
            return DecodedWorkspaceUri(instanceKey = instanceKey, projectKey = projectKey, kind = kind, tail = tail)
        }
    }
}
