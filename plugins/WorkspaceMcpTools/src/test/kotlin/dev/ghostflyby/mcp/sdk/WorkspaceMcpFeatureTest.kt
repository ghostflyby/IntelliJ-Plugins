/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class WorkspaceMcpFeatureTest {

    private val testContext = WorkspaceMcpFeatureContext(
        projectResolver = object : WorkspaceProjectProvider {
            override fun openProjects(): List<Project> = emptyList()
        },
        readResource = { _, _ -> error("readResource should not be called in unit tests") },
    )

    @Test
    fun `feature computeListableResources returns expected resources`() = runBlocking {
        val feature = TestFeature("alpha")
        val resources = feature.computeListableResources(testContext)
        assertEquals(1, resources.size)
        assertEquals("test:alpha:res-1", resources.single().uri)
        assertEquals("Test alpha resource", resources.single().name)
    }

    @Test
    fun `features aggregate distinct resources across features`() = runBlocking {
        val features = listOf(TestFeature("x"), TestFeature("y"))
        val all = features.flatMap { it.computeListableResources(testContext) }.distinctBy { it.uri }
        assertEquals(2, all.size)
        assertTrue(all.any { it.uri == "test:x:res-1" })
        assertTrue(all.any { it.uri == "test:y:res-1" })
    }

    @Test
    fun `empty features produce empty list`() = runBlocking {
        val all = emptyList<WorkspaceMcpFeature>().flatMap { it.computeListableResources(testContext) }
        assertTrue(all.isEmpty())
    }

    private class TestFeature(
        private val id: String,
    ) : WorkspaceMcpFeature {
        override val featureName: String = "test-$id"

        override suspend fun computeListableResources(
            context: WorkspaceMcpFeatureContext,
        ): List<WorkspaceListableResource> {
            return listOf(
                WorkspaceListableResource(
                    uri = "test:$id:res-1",
                    name = "Test $id resource",
                    description = "Test resource for $id",
                    mimeType = "text/plain",
                ),
            )
        }

        override fun register(context: WorkspaceMcpFeatureRegistrationContext): WorkspaceMcpFeatureRegistration {
            // Registration logic tested in integration
            return context.buildRegistration()
        }
    }
}
