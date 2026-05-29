/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.sdk.tools.PerfTool
import io.ktor.resources.Resource
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.ClientOptions
import io.modelcontextprotocol.kotlin.sdk.testing.ChannelTransport
import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue

@OptIn(ExperimentalMcpApi::class)
internal class WorkspaceMcpServerCoreTest {
    private val connections = mutableListOf<McpConnection>()

    @AfterEach
    fun closeConnections() {
        runBlocking {
            connections.reversed().forEach { it.close() }
            connections.clear()
        }
    }

    @Test
    fun `core registers and unregisters feature primitives`() = runBlocking {
        val connection = openCoreClient(initialFeatures = emptyList())

        connection.core.register(ExactFeature("dynamic"))

        assertTrue(connection.core.server.resources.containsKey("ij-workspace://test-instance/exact"))
        assertTrue(connection.core.server.tools.containsKey("do_perf"))

        connection.core.unregister("dynamic")

        assertFalse(connection.core.server.resources.containsKey("ij-workspace://test-instance/exact"))
        assertFalse(connection.core.server.tools.containsKey("do_perf"))
    }

    @Test
    fun `resource updates are sent only to subscribed sessions`() = runBlocking {
        val subscribed = openCoreClient()
        val notSubscribed = openCoreClient()
        val subscribedUpdates = ConcurrentLinkedQueue<ResourceUpdatedNotification>()
        val otherUpdates = ConcurrentLinkedQueue<ResourceUpdatedNotification>()
        subscribed.client.setNotificationHandler(
            Method.Defined.NotificationsResourcesUpdated,
        ) { notification: ResourceUpdatedNotification ->
            subscribedUpdates.add(notification)
            CompletableDeferred(Unit)
        }
        notSubscribed.client.setNotificationHandler(
            Method.Defined.NotificationsResourcesUpdated,
        ) { notification: ResourceUpdatedNotification ->
            otherUpdates.add(notification)
            CompletableDeferred(Unit)
        }

        subscribed.client.subscribeResource(
            SubscribeRequest(SubscribeRequestParams(uri = "ij-workspace://test-instance/vfs/file:///tmp/a.txt")),
        )
        subscribed.stateFlows.resourceContentChanged(setOf("ij-workspace://test-instance/vfs/file:///tmp/a.txt"))

        eventually {
            assertEquals(1, subscribedUpdates.size)
            assertTrue(otherUpdates.isEmpty())
        }
    }

    @Test
    fun `roots changed records session state and emits list changed`() = runBlocking {
        val connection = openCoreClient(
            clientOptions = ClientOptions(
                capabilities = ClientCapabilities(roots = ClientCapabilities.Roots(listChanged = true)),
            ),
        )
        val notifications = ConcurrentLinkedQueue<ResourceListChangedNotification>()
        connection.client.setNotificationHandler(
            Method.Defined.NotificationsResourcesListChanged,
        ) { notification: ResourceListChangedNotification ->
            notifications.add(notification)
            CompletableDeferred(Unit)
        }

        connection.client.sendRootsListChanged()

        eventually {
            assertTrue(connection.core.roots.value.changedGenerationsBySession.isNotEmpty())
            assertTrue(notifications.isNotEmpty())
        }
    }

    private suspend fun openCoreClient(
        initialFeatures: List<WorkspaceMcpFeature> = listOf(ExactFeature("test")),
        clientOptions: ClientOptions = ClientOptions(),
    ): McpConnection {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stateFlows = WorkspaceMcpStateFlows()
        val core = WorkspaceMcpServerCore(
            parentScope = scope,
            projectResolver = ForbiddenProjectProvider,
            serverInfo = Implementation(name = "workspace-mcp-core-test", version = "0.0.0"),
            instructions = "",
            initialFeatures = initialFeatures,
            stateFlows = stateFlows,
            instanceKeyProvider = { "test-instance" },
        )
        val (clientTransport, serverTransport) = ChannelTransport.createLinkedPair()
        val client = Client(
            clientInfo = Implementation(name = "workspace-mcp-core-test-client", version = "0.0.0"),
            options = clientOptions,
        )
        coroutineScope {
            listOf(
                launch { client.connect(clientTransport) },
                launch { core.server.createSession(serverTransport) },
            ).joinAll()
        }
        return McpConnection(client, core, stateFlows, scope).also { connections += it }
    }

    private data class McpConnection(
        val client: Client,
        val core: WorkspaceMcpServerCore,
        val stateFlows: WorkspaceMcpStateFlows,
        val scope: CoroutineScope,
    ) {
        suspend fun close() {
            runCatching { client.close() }
            runCatching { core.close() }
            scope.cancel()
        }
    }

    private class ExactFeature(
        override val featureName: String,
    ) : WorkspaceMcpFeature {
        override fun WorkspaceMcpFeatureRegistrationContext.register() {
            read<ExactResource> {
                ReadResourceResult(listOf(TextResourceContents(uri = call.request.params.uri, text = "ok")))
            }
            registerToolClass<PerfTool>()
        }
    }

    private object ForbiddenProjectProvider : WorkspaceProjectProvider {
        override fun openProjects(): List<Project> = error("Project provider should not be queried.")

        override suspend fun resolve(
            projectKey: String?,
            projectPath: String?,
            rawVfsUrl: String?,
            relativePath: String?,
            rootsCandidates: List<String>?,
        ): WorkspaceProjectResolution = error("Project provider should not be queried.")
    }

    @Serializable
    @Resource("/exact")
    private class ExactResource

    private suspend fun eventually(
        timeoutMillis: Long = 5_000,
        intervalMillis: Long = 25,
        assertion: () -> Unit,
    ) {
        val started = System.currentTimeMillis()
        var lastError: AssertionError? = null
        while (System.currentTimeMillis() - started < timeoutMillis) {
            try {
                assertion()
                return
            } catch (error: AssertionError) {
                lastError = error
                delay(intervalMillis)
            }
        }
        throw lastError ?: AssertionError("Condition was not satisfied")
    }
}
