/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

import dev.ghostflyby.mcp.core.CoreResourceFeature
import dev.ghostflyby.mcp.resource.workspaceFileUri
import dev.ghostflyby.mcp.resource.workspaceVfsUri
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

internal class ResourceRouteSnapshotTest {
    @Test
    fun `files template matches project relative path and semantic project segment`() {
        val snapshot = testSnapshot()
        val uri = workspaceFileUri(IK, PK, "src/main/Foo.kt")
        val result = fileMatcher(snapshot).match(uri)

        assertNotNull(result)
        assertEquals(IK, result!!.variables["instanceKey"])
        assertEquals(PK, result.variables["projectKey"])
        assertEquals("src/main/Foo.kt", result.variables["relativePath"])

        val routeMatch = snapshot.segmentMatch(uri)
        assertEquals(PK, routeMatch?.ancestors?.get(CoreResourceFeature.PROJECT_SEGMENT))
    }

    @Test
    fun `vfs template preserves raw tail with slash bearing inner url`() {
        val rawVfsUrl = "jar:///tmp/with space/lib.jar!/pkg/with!bang.kt"
        val uri = workspaceVfsUri(IK, PK, rawVfsUrl)
        val result = vfsMatcher(testSnapshot()).match(uri)

        assertNotNull(result)
        assertEquals(rawVfsUrl, result!!.variables["rawVfsUrl"])
    }

    @Test
    fun `project template does not match deeper vfs uri`() {
        val snapshot = testSnapshot()
        val projectTemplate = ResourceTemplate(
            uriTemplate = "ij-workspace://{instanceKey}/projects/{projectKey}",
            name = "projectKey",
        )
        val matcher = SegmentTreeTemplateMatcher(projectTemplate, ResourceRouteSnapshotRef(snapshot))

        assertNull(matcher.match(workspaceVfsUri(IK, PK, "file:///tmp/workspace/file.txt")))
    }

    private fun testSnapshot(): ResourceRouteSnapshot {
        val collector = ResourceSegmentCollector()
        collector.segment("projects") {
            parameter(
                paramName = "projectKey",
                id = CoreResourceFeature.PROJECT_SEGMENT,
                extensible = true,
            ) {
                resource { ReadResourceResult(emptyList()) }
                template()
            }
        }
        val vfsCollector = ResourceSegmentCollector()
        vfsCollector.under(CoreResourceFeature.PROJECT_SEGMENT) {
            segment("files") {
                parameter("relativePath") {
                    resource { ReadResourceResult(emptyList()) }
                    template()
                }
            }
            segment("vfs") {
                parameter("rawVfsUrl") {
                    resource { ReadResourceResult(emptyList()) }
                    template()
                }
            }
        }
        return ResourceRouteCompiler.compile(
            listOf(
                WorkspaceResourceRouteContribution(
                    featureName = "core",
                    roots = collector.roots,
                    pendingAnchors = collector.pendingAnchors,
                ),
                WorkspaceResourceRouteContribution(
                    featureName = "vfs",
                    roots = vfsCollector.roots,
                    pendingAnchors = vfsCollector.pendingAnchors,
                ),
            ),
        )
    }

    private fun fileMatcher(snapshot: ResourceRouteSnapshot): SegmentTreeTemplateMatcher {
        return SegmentTreeTemplateMatcher(
            ResourceTemplate(
                uriTemplate = "ij-workspace://{instanceKey}/projects/{projectKey}/files/{relativePath}",
                name = "relativePath",
            ),
            ResourceRouteSnapshotRef(snapshot),
        )
    }

    private fun vfsMatcher(snapshot: ResourceRouteSnapshot): SegmentTreeTemplateMatcher {
        return SegmentTreeTemplateMatcher(
            ResourceTemplate(
                uriTemplate = "ij-workspace://{instanceKey}/projects/{projectKey}/vfs/{rawVfsUrl}",
                name = "rawVfsUrl",
            ),
            ResourceRouteSnapshotRef(snapshot),
        )
    }

    private companion object {
        const val IK = "iu-63341"
        const val PK = "my-project-a1b2"
    }
}
