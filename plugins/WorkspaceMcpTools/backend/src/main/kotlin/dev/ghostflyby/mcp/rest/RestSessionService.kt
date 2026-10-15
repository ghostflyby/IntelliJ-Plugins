/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.filecontent.ExposedRoot
import dev.ghostflyby.mcp.filecontent.exposedWorkspaceRoots
import dev.ghostflyby.mcp.sdk.openWorkspaceProjects
import dev.ghostflyby.mcp.sdk.workspaceProjectKey
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

internal data class RestSessionRecord(
    val sessionId: String,
    val pathPrefix: Path,
    val projectKey: String,
    val projectName: String,
    val projectBasePath: String?,
    val rootId: String,
    val rootPath: Path,
    val expiresAt: Instant,
)

internal data class RestSessionResolvedTarget(
    val record: RestSessionRecord,
    val relativePathUnderRoot: String,
)

internal sealed class RestSessionCreateResult {
    data class Created(val record: RestSessionRecord) : RestSessionCreateResult()
    data class Failed(val message: String) : RestSessionCreateResult()
    data class Ambiguous(val message: String) : RestSessionCreateResult()
}

internal sealed class RestSessionTargetResult {
    data class Resolved(val target: RestSessionResolvedTarget) : RestSessionTargetResult()
    data class NotFound(val message: String) : RestSessionTargetResult()
    data class Forbidden(val message: String) : RestSessionTargetResult()
}

internal sealed class RestSessionRecordResult {
    data class Resolved(val record: RestSessionRecord) : RestSessionRecordResult()
    data class NotFound(val message: String) : RestSessionRecordResult()
}

@Service(Service.Level.APP)
internal class RestSessionService {
    private val sessions = ConcurrentHashMap<String, RestSessionRecord>()
    private val random = SecureRandom()
    private val ttl: Duration = Duration.ofMinutes(30)
    private val clock: Clock = Clock.systemUTC()
    private val rootMatchComparator = compareByDescending<RootMatch> { it.relationRank }
        .thenByDescending { it.depthScore }
        .thenBy { it.root.base.path }
        .thenBy { it.projectKey }

    internal suspend fun create(pathPrefix: String): RestSessionCreateResult {
        val normalizedPrefix = normalizeExistingPath(pathPrefix)
            ?: return RestSessionCreateResult.Failed("Path prefix not found: $pathPrefix")
        val matches = openWorkspaceProjects()
            .flatMap { project -> matchingRoots(project, normalizedPrefix) }
        val bestByProject = matches
            .sortedWith(rootMatchComparator)
            .distinctBy { it.projectKey }
            .sortedWith(rootMatchComparator)
        val best = bestByProject.firstOrNull()
            ?: return RestSessionCreateResult.Failed("No open IntelliJ project owns path prefix: $pathPrefix")
        val ambiguous = bestByProject.drop(1).firstOrNull { it.hasSameRankAs(best) }
        if (ambiguous != null) {
            return RestSessionCreateResult.Ambiguous(
                "Path prefix is ambiguous between '${best.projectKey}' and '${ambiguous.projectKey}'",
            )
        }
        val now = clock.instant()
        val record = RestSessionRecord(
            sessionId = newSessionId(),
            pathPrefix = normalizedPrefix,
            projectKey = best.projectKey,
            projectName = best.project.name,
            projectBasePath = best.project.basePath,
            rootId = best.root.id,
            rootPath = Path.of(best.root.base.path).normalizeAbsolute(),
            expiresAt = now.plus(ttl),
        )
        sessions[record.sessionId] = record
        return RestSessionCreateResult.Created(record)
    }

    internal fun delete(sessionId: String): Boolean = sessions.remove(sessionId) != null

    internal fun resolveRecord(sessionId: String?): RestSessionRecordResult {
        if (sessionId.isNullOrBlank()) {
            return RestSessionRecordResult.NotFound("Missing $RestSessionHeader header")
        }
        val record = sessions[sessionId]
            ?: return RestSessionRecordResult.NotFound("Session not found")
        if (record.expiresAt <= clock.instant()) {
            sessions.remove(sessionId)
            return RestSessionRecordResult.NotFound("Session expired")
        }
        val refreshed = record.copy(expiresAt = clock.instant().plus(ttl))
        sessions[sessionId] = refreshed
        return RestSessionRecordResult.Resolved(refreshed)
    }

    internal fun resolveTarget(sessionId: String?, relativePath: String): RestSessionTargetResult {
        val refreshed = when (val result = resolveRecord(sessionId)) {
            is RestSessionRecordResult.Resolved -> result.record
            is RestSessionRecordResult.NotFound -> return RestSessionTargetResult.NotFound(result.message)
        }
        val requested = refreshed.pathPrefix.resolve(relativePath).normalizeAbsolute()
        if (!requested.startsWith(refreshed.pathPrefix)) {
            return RestSessionTargetResult.Forbidden("Path is outside the session prefix")
        }
        if (!requested.startsWith(refreshed.rootPath)) {
            return RestSessionTargetResult.Forbidden("Path is outside the inferred root")
        }
        return RestSessionTargetResult.Resolved(
            RestSessionResolvedTarget(
                record = refreshed,
                relativePathUnderRoot = refreshed.rootPath.relativize(requested).toRoutePath(),
            ),
        )
    }

    private suspend fun matchingRoots(project: Project, prefix: Path): List<RootMatch> {
        val projectKey = workspaceProjectKey(project)
        return exposedWorkspaceRoots(project).mapNotNull { root ->
            val rootPath = Path.of(root.base.path).normalizeAbsolute()
            val ownsPrefix = prefix.startsWith(rootPath)
            val prefixOwnsRoot = rootPath.startsWith(prefix)
            if (!ownsPrefix && !prefixOwnsRoot) return@mapNotNull null
            RootMatch(
                project = project,
                projectKey = projectKey,
                root = root,
                relationRank = if (ownsPrefix) 1 else 0,
                depthScore = if (ownsPrefix) rootPath.nameCount else -rootPath.nameCount,
            )
        }
    }

    private fun normalizeExistingPath(path: String): Path? {
        return runCatching {
            val normalized = Path.of(path).normalizeAbsolute()
            if (!normalized.exists()) return null
            normalized.toRealPath()
        }.getOrNull()
    }

    private fun newSessionId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return "s_" + bytes.toHexString()
    }

    private data class RootMatch(
        val project: Project,
        val projectKey: String,
        val root: ExposedRoot,
        val relationRank: Int,
        val depthScore: Int,
    ) {
        fun hasSameRankAs(other: RootMatch): Boolean =
            relationRank == other.relationRank && depthScore == other.depthScore
    }
}

private fun Path.normalizeAbsolute(): Path = toAbsolutePath().normalize()

private fun Path.toRoutePath(): String = joinToString("/")
