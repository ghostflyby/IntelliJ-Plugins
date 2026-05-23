/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

import dev.ghostflyby.mcp.route.resources.ProjectFileResource
import dev.ghostflyby.mcp.route.resources.ProjectResource
import dev.ghostflyby.mcp.route.resources.ServerInfoResource
import dev.ghostflyby.mcp.route.resources.VfsResource
import io.ktor.resources.*
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test

internal class ResourceRouteSnapshotTest {
    @Test
    fun `server info matches literal route`() {
        val snapshot = testSnapshot()
        val match = snapshot.matchUri("ij-workspace://iu-63341/server/info")
        assertNotNull(match)
        assertEquals("iu-63341", match?.params?.get("instanceKey"))
    }

    @Test
    fun `no-query route does not match URI with query string`() {
        val snapshot = testSnapshot()
        assertNull(snapshot.matchUri("ij-workspace://iu-63341/server/info?x=1"))
    }

    @Test
    fun `files template matches project relative path`() {
        val snapshot = testSnapshot()
        val result = fileMatcher(snapshot).match(workspaceFileUri())
        assertNotNull(result)
        assertEquals(IK, result!!.variables["instanceKey"])
        assertEquals(PK, result.variables["projectKey"])
        assertEquals("src/main/Foo.kt", result.variables["relativePath"])
    }

    @Test
    fun `vfs template preserves raw tail`() {
        val rawVfsUrl = "jar:///tmp/with space/lib.jar!/pkg/with!bang.kt"
        val result = vfsMatcher(testSnapshot()).match(workspaceVfsUri(rawVfsUrl))
        assertNotNull(result)
        assertEquals(rawVfsUrl, result!!.variables["rawVfsUrl"])
    }

    @Test
    fun `vfs template preserves raw tail containing question mark`() {
        val rawVfsUrl = "file:///tmp/workspace/file.kt?line=10&column=2"
        val result = vfsMatcher(testSnapshot()).match(workspaceVfsUri(rawVfsUrl))
        assertNotNull(result)
        assertEquals(rawVfsUrl, result!!.variables["rawVfsUrl"])
    }

    @Test
    fun `vfs template captures key-only meta query after raw tail`() {
        val rawVfsUrl = "file:///tmp/workspace/file?name.kt"
        val result = vfsMatcher(testSnapshot()).match(workspaceVfsUri(rawVfsUrl) + "?meta")
        assertNotNull(result)
        assertEquals(rawVfsUrl, result!!.variables["rawVfsUrl"])
        assertEquals("", result.variables["meta"])
    }

    @Test
    fun `vfs template captures meta and content query after raw tail`() {
        val rawVfsUrl = "file:///tmp/workspace/file?name.kt"
        val result = vfsMatcher(testSnapshot()).match(workspaceVfsUri(rawVfsUrl) + "?meta=length,readonly&content")
        assertNotNull(result)
        assertEquals(rawVfsUrl, result!!.variables["rawVfsUrl"])
        assertEquals("length,readonly", result.variables["meta"])
        assertEquals("", result.variables["content"])
    }

    @Test
    fun `project route does not match deeper file uri`() {
        val snapshot = testSnapshot()
        val projectTemplate = ResourceTemplate(
            uriTemplate = "ij-workspace://{instanceKey}/projects/{projectKey}",
            name = "projectKey",
        )
        val matcher = SegmentTreeTemplateMatcher(projectTemplate, ResourceRouteSnapshotRef(snapshot))
        assertNull(matcher.match(workspaceFileUri()))
    }

    @Test
    fun `single segment parameter does not capture extra path segments`() {
        val snapshot = projectOnlySnapshot()
        assertNull(snapshot.matchUri(workspaceFileUri()))
    }

    @Test
    fun `project route matches from parent resource`() {
        val routeMatch = testSnapshot().matchUri(workspaceFileUri())
        assertEquals(PK, routeMatch?.params?.get("projectKey"))
    }

    @Test
    fun `optional query route matches without query string`() {
        val match = optionalQuerySnapshot().matchUri("ij-workspace://iu-63341/search")
        assertNotNull(match)
        assertEquals("iu-63341", match?.params?.get("instanceKey"))
        assertNull(match?.params?.get("query"))
    }

    @Test
    fun `optional query route matches with query string`() {
        val match = optionalQuerySnapshot().matchUri("ij-workspace://iu-63341/search?query=hello")
        assertNotNull(match)
        assertEquals("hello", match?.params?.get("query"))
    }

    @Test
    fun `no-query and optional-query routes coexist on same path`() {
        val snapshot = coexistingQuerySnapshot()
        val noQuery = snapshot.matchUri("ij-workspace://iu-63341/target")
        assertNotNull(noQuery)
        assertNull(noQuery?.params?.get("q"))

        val withQuery = snapshot.matchUri("ij-workspace://iu-63341/target?q=hello")
        assertNotNull(withQuery)
        assertEquals("hello", withQuery?.params?.get("q"))
    }

    @Test
    fun `literal match has higher priority than param`() {
        val snapshot = testSnapshot()
        val match = snapshot.matchUri("ij-workspace://iu-63341/projects/special")
        assertNotNull(match)
        assertEquals("special", match?.params?.get("projectKey"))
    }

    private fun testSnapshot(): ResourceRouteSnapshot {
        val core = ResourceSegmentCollector()
        core.read<ServerInfoResource> { ReadResourceResult(emptyList()) }
        core.read<ProjectResource> { ReadResourceResult(emptyList()) }
        core.listTemplates<ProjectResource>()

        val fileContent = ResourceSegmentCollector()
        fileContent.read<VfsResource> { ReadResourceResult(emptyList()) }
        fileContent.listTemplates<VfsResource>()
        fileContent.read<ProjectFileResource> { ReadResourceResult(emptyList()) }
        fileContent.listTemplates<ProjectFileResource>()

        return ResourceRouteCompiler.compile(
            listOf(
                WorkspaceResourceRouteContribution(
                    featureName = "core",
                    roots = core.roots,
                ),
                WorkspaceResourceRouteContribution(
                    featureName = "file-content",
                    roots = fileContent.roots,
                ),
            ),
        )
    }

    private fun projectOnlySnapshot(): ResourceRouteSnapshot {
        val collector = ResourceSegmentCollector()
        collector.read<ProjectResource> { ReadResourceResult(emptyList()) }
        collector.listTemplates<ProjectResource>()
        return compile("project-only", collector)
    }

    private fun optionalQuerySnapshot(): ResourceRouteSnapshot {
        val collector = ResourceSegmentCollector()
        collector.read<SearchResource> { ReadResourceResult(emptyList()) }
        collector.listTemplates<SearchResource>()
        return compile("search", collector)
    }

    private fun coexistingQuerySnapshot(): ResourceRouteSnapshot {
        val collector = ResourceSegmentCollector()
        collector.read<TargetResource> { ReadResourceResult(emptyList()) }
        collector.listTemplates<TargetResource>()
        collector.read<TargetWithQueryResource> { ReadResourceResult(emptyList()) }
        collector.listTemplates<TargetWithQueryResource>()
        return compile("target", collector)
    }

    private fun compile(featureName: String, collector: ResourceSegmentCollector): ResourceRouteSnapshot {
        return ResourceRouteCompiler.compile(
            listOf(
                WorkspaceResourceRouteContribution(
                    featureName = featureName,
                    roots = collector.roots,
                ),
            ),
        )
    }

    private fun fileMatcher(snapshot: ResourceRouteSnapshot): SegmentTreeTemplateMatcher {
        return SegmentTreeTemplateMatcher(
            ResourceTemplate(
                uriTemplate = "ij-workspace://{instanceKey}/projects/{projectKey}/files/{relativePath}{?meta,content}",
                name = "relativePath",
            ),
            ResourceRouteSnapshotRef(snapshot),
        )
    }

    private fun vfsMatcher(snapshot: ResourceRouteSnapshot): SegmentTreeTemplateMatcher {
        return SegmentTreeTemplateMatcher(
            ResourceTemplate(
                uriTemplate = "ij-workspace://{instanceKey}/vfs/{rawVfsUrl}{?meta,content}",
                name = "rawVfsUrl",
            ),
            ResourceRouteSnapshotRef(snapshot),
        )
    }

    private fun workspaceFileUri(): String =
        "ij-workspace://$IK/projects/$PK/files/src/main/Foo.kt"

    private fun workspaceVfsUri(raw: String = "file:///tmp/workspace/file.txt"): String =
        "ij-workspace://$IK/vfs/$raw"

    @Serializable
    @Resource("/search")
    private data class SearchResource(
        val query: String? = null,
    )

    @Serializable
    @Resource("/target")
    private class TargetResource

    @Serializable
    @Resource("/target")
    private data class TargetWithQueryResource(
        val q: String? = null,
    )

    private companion object {
        const val IK = "iu-63341"
        const val PK = "my-project-a1b2"
    }
}
