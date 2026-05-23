/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import dev.ghostflyby.mcp.route.ResourceListDecision
import dev.ghostflyby.mcp.route.ResourceRouteCompiler
import dev.ghostflyby.mcp.route.ResourceSegmentCollector
import dev.ghostflyby.mcp.route.WorkspaceResourceRouteContribution
import dev.ghostflyby.mcp.route.listResources
import dev.ghostflyby.mcp.route.listTemplates
import dev.ghostflyby.mcp.route.read
import io.ktor.resources.Resource
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

internal class WorkspaceMcpResourceCatalogTest {
    @Test
    fun `exact read route is listed as concrete resource by default`() = runBlocking {
        val catalog = catalog {
            read<ExactResource> { ReadResourceResult(emptyList()) }
        }

        val result = catalog.listResources(fakeClientConnection(), ListResourcesRequest())

        assertEquals(listOf("ij-workspace://test-instance/exact"), result.resources.map { it.uri })
    }

    @Test
    fun `parameterized read route is not listed as concrete resource by default`() = runBlocking {
        val catalog = catalog {
            read<ParameterizedResource> { ReadResourceResult(emptyList()) }
        }

        val result = catalog.listResources(fakeClientConnection(), ListResourcesRequest())

        assertTrue(result.resources.isEmpty())
    }

    @Test
    fun `resource list provider can stop child traversal`() = runBlocking {
        val catalog = catalog {
            listResources<ParentResource> {
                ResourceListDecision(
                    entries = listOf(
                        io.modelcontextprotocol.kotlin.sdk.types.Resource(
                            uri = "ij-workspace://test-instance/custom-parent",
                            name = "parent",
                        ),
                    ),
                    includeChildren = false,
                )
            }
            read<ChildResource> { ReadResourceResult(emptyList()) }
        }

        val result = catalog.listResources(fakeClientConnection(), ListResourcesRequest())

        assertEquals(listOf("ij-workspace://test-instance/custom-parent"), result.resources.map { it.uri })
        assertFalse(result.resources.any { it.uri.endsWith("/child") })
    }

    @Test
    fun `template list provider is independent from resource list`() = runBlocking {
        val catalog = catalog {
            read<ExactResource> { ReadResourceResult(emptyList()) }
            listTemplates<ParameterizedResource> {
                ResourceListDecision(
                    entries = listOf(
                        ResourceTemplate(
                            uriTemplate = "ij-workspace://{instanceKey}/custom/{id}",
                            name = "custom",
                        ),
                    ),
                )
            }
        }

        val resources = catalog.listResources(fakeClientConnection(), ListResourcesRequest())
        val templates = catalog.listTemplates(fakeClientConnection(), ListResourceTemplatesRequest())

        assertEquals(listOf("ij-workspace://test-instance/exact"), resources.resources.map { it.uri })
        assertEquals(listOf("ij-workspace://{instanceKey}/custom/{id}"), templates.resourceTemplates.map { it.uriTemplate })
    }

    private fun catalog(block: ResourceSegmentCollector.() -> Unit): WorkspaceMcpResourceCatalog {
        val collector = ResourceSegmentCollector().apply(block)
        return WorkspaceMcpResourceCatalog(instanceKeyProvider = { "test-instance" }).apply {
            updateSnapshot(
                ResourceRouteCompiler.compile(
                    listOf(
                        WorkspaceResourceRouteContribution(
                            featureName = "test",
                            roots = collector.roots,
                        ),
                    ),
                ),
            )
        }
    }

    private fun fakeClientConnection(): ClientConnection {
        return Proxy.newProxyInstance(
            ClientConnection::class.java.classLoader,
            arrayOf(ClientConnection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getSessionId" -> "test-session"
                "toString" -> "FakeClientConnection(test-session)"
                "hashCode" -> 1
                "equals" -> args?.firstOrNull() === this
                else -> error("Unexpected ClientConnection call in catalog test: ${method.name}")
            }
        } as ClientConnection
    }

    @Serializable
    @Resource("/exact")
    private class ExactResource

    @Serializable
    @Resource("/parameterized/{id}")
    private data class ParameterizedResource(val id: String)

    @Serializable
    @Resource("/parent")
    private class ParentResource

    @Serializable
    @Resource("/parent/child")
    private class ChildResource
}
