/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import dev.ghostflyby.mcp.route.*
import io.ktor.resources.*
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

internal class WorkspaceMcpResourceCatalogTest {
    @Test
    fun `exact read route falls back to concrete resource listing`() = runBlocking {
        val catalog = catalog {
            read<ExactResource> { ReadResourceResult(emptyList()) }
        }

        val result = catalog.listResources(fakeClientConnection(), ListResourcesRequest())

        assertEquals(listOf("ij-workspace://test-instance/exact"), result.resources.map { it.uri })
    }

    @Test
    fun `parameterized read route falls back to template listing`() = runBlocking {
        val catalog = catalog {
            read<ParameterizedResource> { ReadResourceResult(emptyList()) }
        }

        val resources = catalog.listResources(fakeClientConnection(), ListResourcesRequest())
        val templates = catalog.listTemplates(fakeClientConnection(), ListResourceTemplatesRequest())

        assertTrue(resources.resources.isEmpty())
        assertEquals(
            listOf("ij-workspace://{instanceKey}/parameterized/{id}"),
            templates.resourceTemplates.map { it.uriTemplate },
        )
    }

    @Test
    fun `manual resource list provider suppresses same path read fallback`() = runBlocking {
        val catalog = catalog {
            read<ExactResource> { ReadResourceResult(emptyList()) }
            listResources<ExactResource> {
                listOf(
                    io.modelcontextprotocol.kotlin.sdk.types.Resource(
                        uri = "ij-workspace://test-instance/custom-exact",
                        name = "exact",
                    ),
                )
            }
        }

        val result = catalog.listResources(fakeClientConnection(), ListResourcesRequest())

        assertEquals(listOf("ij-workspace://test-instance/custom-exact"), result.resources.map { it.uri })
    }

    @Test
    fun `manual empty template list provider suppresses same path read fallback`() = runBlocking {
        val catalog = catalog {
            listTemplates<ParameterizedResource> {
                emptyList()
            }
            read<ParameterizedResource> { ReadResourceResult(emptyList()) }
        }

        val templates = catalog.listTemplates(fakeClientConnection(), ListResourceTemplatesRequest())

        assertTrue(templates.resourceTemplates.isEmpty())
    }

    @Test
    fun `manual list provider does not suppress other list primitive fallback`() = runBlocking {
        val catalog = catalog {
            read<ParameterizedResource> { ReadResourceResult(emptyList()) }
            listResources<ParameterizedResource> {
                emptyList()
            }
        }

        val resources = catalog.listResources(fakeClientConnection(), ListResourcesRequest())
        val templates = catalog.listTemplates(fakeClientConnection(), ListResourceTemplatesRequest())

        assertTrue(resources.resources.isEmpty())
        assertEquals(
            listOf("ij-workspace://{instanceKey}/parameterized/{id}"),
            templates.resourceTemplates.map { it.uriTemplate },
        )
    }

    @Test
    fun `duplicate provider output is deduplicated after read fallback`() = runBlocking {
        val catalog = catalog {
            read<ExactResource> { ReadResourceResult(emptyList()) }
            listResources<ParentResource> {
                listOf(
                    io.modelcontextprotocol.kotlin.sdk.types.Resource(
                        uri = "ij-workspace://test-instance/exact",
                        name = "duplicate",
                    ),
                )
            }
        }

        val result = catalog.listResources(fakeClientConnection(), ListResourcesRequest())

        assertEquals(listOf("ij-workspace://test-instance/exact"), result.resources.map { it.uri })
    }

    private fun catalog(block: ResourceSegmentCollector.() -> Unit): WorkspaceMcpResourceCatalog {
        val collector = ResourceSegmentCollector().apply(block)
        return WorkspaceMcpResourceCatalog(
            projectResolver = ForbiddenProjectProvider,
            instanceKeyProvider = { "test-instance" },
        ).apply {
            updateSnapshot(
                ResourceRouteCompiler.compile(
                    listOf(
                        WorkspaceResourceRouteContribution(
                            featureName = "test",
                            roots = collector.roots,
                            resourceListRoutes = collector.resourceListRoutes,
                            templateListRoutes = collector.templateListRoutes,
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

    private object ForbiddenProjectProvider : WorkspaceProjectProvider {
        override fun openProjects(): List<com.intellij.openapi.project.Project> =
            error("Project provider should not be queried.")

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
