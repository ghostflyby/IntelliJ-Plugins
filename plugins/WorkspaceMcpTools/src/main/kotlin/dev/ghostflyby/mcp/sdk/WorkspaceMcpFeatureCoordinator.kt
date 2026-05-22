/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.logger
import dev.ghostflyby.mcp.route.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob


internal class WorkspaceMcpFeatureCoordinator(
    private val parentScope: CoroutineScope,
    private val projectResolver: WorkspaceProjectResolver,
    private val catalog: WorkspaceMcpResourceCatalog,
    private val onSnapshotChanged: (ResourceRouteSnapshot) -> Unit,
    private val invalidationSink: WorkspaceMcpInvalidationSink,
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
            invalidationSink = invalidationSink,
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
        catalog.updateSnapshot(snapshot)
        diffResources(activeServer, snapshot)
    }

    private val resourceUris = linkedSetOf<String>()
    private val templateUris = linkedSetOf<String>()

    private fun diffResources(activeServer: Server, snapshot: ResourceRouteSnapshot) {
        val nextResourceUris = linkedSetOf<String>()
        val nextTemplateUris = linkedSetOf<String>()

        snapshot.resources.forEach { entry ->
            if (entry.isParameterized) {
                nextTemplateUris += entry.uri
                if (entry.uri !in templateUris) {
                    activeServer.addResourceTemplate(
                        uriTemplate = entry.uri,
                        name = entry.name,
                        description = entry.description,
                        mimeType = entry.mimeType,
                    ) { request, vars ->
                        entry.handler(
                            McpCallContext(
                                WorkspaceMcpCall(
                                    connection = this,
                                    request = request,
                                    parameters = AncestorContext(vars),
                                ),
                            ),
                        )
                    }
                }
            } else {
                val concreteUri = entry.uri.replace("{instanceKey}", workspaceInstanceKey())
                nextResourceUris += concreteUri
                if (concreteUri !in resourceUris) {
                    activeServer.addResource(
                        uri = concreteUri,
                        name = entry.name,
                        description = entry.description,
                        mimeType = entry.mimeType,
                    ) { request ->
                        entry.handler(
                            McpCallContext(
                                WorkspaceMcpCall(
                                    connection = this,
                                    request = request,
                                    parameters = AncestorContext(emptyMap()),
                                ),
                            ),
                        )
                    }
                }
            }
        }

        (resourceUris - nextResourceUris).forEach { activeServer.removeResource(it) }
        (templateUris - nextTemplateUris).forEach { activeServer.removeResourceTemplate(it) }

        resourceUris.clear()
        resourceUris += nextResourceUris
        templateUris.clear()
        templateUris += nextTemplateUris
    }

    fun clearPrimitives() {
        resourceUris.clear()
        templateUris.clear()
    }

    private fun compileSnapshot(): ResourceRouteSnapshot {
        val contributions = synchronized(lock) {
            registrations.values.map { registration ->
                WorkspaceResourceRouteContribution(
                    featureName = registration.featureName,
                    roots = registration.roots,
                )
            }
        }
        return ResourceRouteCompiler.compile(contributions)
    }
}
