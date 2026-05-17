/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import dev.ghostflyby.mcp.resource.segment.ResourceRouteCompiler
import dev.ghostflyby.mcp.resource.segment.ResourceRouteSnapshot
import dev.ghostflyby.mcp.resource.segment.WorkspaceResourceRouteContribution
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

internal class WorkspaceMcpFeatureCoordinator(
    private val parentScope: CoroutineScope,
    private val projectResolver: WorkspaceProjectResolver,
    private val primitiveRegistry: WorkspaceMcpPrimitiveRegistry,
    private val onSnapshotChanged: (ResourceRouteSnapshot) -> Unit,
) {
    private val logger: Logger = logger<WorkspaceMcpFeatureCoordinator>()
    private val lock = Any()
    private val registrations = linkedMapOf<String, WorkspaceMcpFeatureRegistration>()

    fun registerInitial(activeServer: Server, features: List<WorkspaceMcpFeature>) {
        features.forEach { register(activeServer, it, sync = false) }
        syncResources(activeServer)
    }

    fun register(activeServer: Server, feature: WorkspaceMcpFeature, sync: Boolean = true) {
        synchronized(lock) {
            if (feature.featureName in registrations) {
                logger.warn("Workspace MCP feature ${feature.featureName} is already registered")
                return
            }
        }

        val featureJob = SupervisorJob(parentScope.coroutineContext[Job])
        val featureScope = CoroutineScope(parentScope.coroutineContext + featureJob)
        val context = WorkspaceMcpFeatureRegistrationContext(
            projectResolver = projectResolver,
            server = activeServer,
            featureScope = featureScope,
            featureName = feature.featureName,
        )
        val registration = try {
            with(feature) { context.register() }
        } catch (error: Exception) {
            featureJob.cancel()
            logger.warn("Failed to register Workspace MCP feature ${feature.featureName}", error)
            return
        }

        synchronized(lock) {
            val previous = registrations.putIfAbsent(feature.featureName, registration)
            if (previous != null) {
                featureJob.cancel()
                logger.warn("Workspace MCP feature ${feature.featureName} was registered concurrently")
                return
            }
        }
        if (sync) syncResources(activeServer)
    }

    fun unregister(activeServer: Server, featureName: String) {
        val registration = synchronized(lock) { registrations.remove(featureName) } ?: return
        registration.job.cancel()
        registration.registeredTools.forEach { name ->
            runCatching { activeServer.removeTool(name) }
                .onFailure { error ->
                    logger.warn("Failed to remove Workspace MCP tool $name for feature $featureName", error)
                }
        }
        syncResources(activeServer)
    }

    fun syncResources(activeServer: Server) {
        val snapshot = compileSnapshot()
        onSnapshotChanged(snapshot)
        primitiveRegistry.syncResources(activeServer, snapshot)
    }

    private fun compileSnapshot(): ResourceRouteSnapshot {
        val contributions = synchronized(lock) {
            registrations.values.map { registration ->
                WorkspaceResourceRouteContribution(
                    featureName = registration.featureName,
                    roots = registration.roots,
                    pendingAnchors = registration.pendingAnchors,
                )
            }
        }
        return ResourceRouteCompiler.compile(contributions)
    }
}

