/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import org.junit.Assert.*
import org.junit.Test

internal class ResourceRouteSnapshotTest {
    // -- RoutePattern parser tests --

    @Test
    fun `parse literal route`() {
        val pattern = RoutePattern.parse("server/info")
        assertEquals("server/info", pattern.original)
        assertEquals(2, pattern.pathTokens.size)
        assertTrue(pattern.pathTokens[0] is LiteralToken)
        assertEquals("server", (pattern.pathTokens[0] as LiteralToken).text)
        assertEquals("info", (pattern.pathTokens[1] as LiteralToken).text)
        assertFalse(pattern.hasReservedParam)
    }

    @Test
    fun `parse parameterized route`() {
        val pattern = RoutePattern.parse("projects/{projectKey}")
        assertEquals(2, pattern.pathTokens.size)
        assertTrue(pattern.pathTokens[0] is LiteralToken)
        assertTrue(pattern.pathTokens[1] is ParamToken)
        assertEquals("projectKey", (pattern.pathTokens[1] as ParamToken).name)
        assertEquals(listOf("projectKey"), pattern.paramNames)
    }

    @Test
    fun `parse reserved param route`() {
        val pattern = RoutePattern.parse("files/{+relativePath}")
        assertEquals(2, pattern.pathTokens.size)
        assertTrue(pattern.pathTokens[0] is LiteralToken)
        assertTrue(pattern.pathTokens[1] is ReservedParamToken)
        assertEquals("relativePath", (pattern.pathTokens[1] as ReservedParamToken).name)
        assertTrue(pattern.hasReservedParam)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reserved param must be last`() {
        RoutePattern.parse("files/{+tail}/extra")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `legacy star prefixed tail param is not accepted`() {
        RoutePattern.parse("files/{*tail}")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty param name fails`() {
        RoutePattern.parse("projects/{}")
    }


    @Test
    fun `parse optional query params`() {
        val pattern = RoutePattern.parse("search{?query}")
        assertEquals(1, pattern.pathTokens.size)
        assertEquals("search", (pattern.pathTokens[0] as LiteralToken).text)
        assertEquals(1, pattern.queryTokens.size)
        with(pattern.queryTokens[0]) {
            assertEquals("query", key)
            assertEquals("query", paramName)
            assertTrue(optional)
            assertNull(literalValue)
        }
    }

    @Test
    fun `parse query params`() {
        val pattern = RoutePattern.parse("search?query={query}&limit=10")
        assertEquals(1, pattern.pathTokens.size)
        assertTrue(pattern.pathTokens[0] is LiteralToken)
        assertEquals("search", (pattern.pathTokens[0] as LiteralToken).text)
        assertEquals(2, pattern.queryTokens.size)
        with(pattern.queryTokens[0]) {
            assertEquals("query", key)
            assertEquals("query", paramName)
            assertNull(literalValue)
        }
        with(pattern.queryTokens[1]) {
            assertEquals("limit", key)
            assertNull(paramName)
            assertEquals("10", literalValue)
        }
    }

    // -- Matcher tests via compiled snapshot --

    @Test
    fun `no-query route does not match URI with query string`() {
        val snapshot = testSnapshot()
        assertNull(snapshot.segmentMatch("ij-workspace://iu-63341/server/info?x=1"))
    }

    @Test
    fun `server info matches literal route`() {
        val snapshot = testSnapshot()
        val uri = "ij-workspace://iu-63341/server/info"
        val match = snapshot.segmentMatch(uri)
        assertNotNull(match)
        assertEquals("iu-63341", match?.ancestors?.get("instanceKey"))
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
        val uri = workspaceVfsUri(rawVfsUrl)
        val result = vfsMatcher(testSnapshot()).match(uri)
        assertNotNull(result)
        assertEquals(rawVfsUrl, result!!.variables["rawVfsUrl"])
    }

    @Test
    fun `vfs template preserves raw tail containing question mark`() {
        val rawVfsUrl = "file:///tmp/workspace/file.kt?line=10&column=2"
        val uri = workspaceVfsUri(rawVfsUrl)
        val result = vfsMatcher(testSnapshot()).match(uri)
        assertNotNull(result)
        assertEquals(rawVfsUrl, result!!.variables["rawVfsUrl"])
    }

    @Test
    fun `project route does not match deeper vfs uri`() {
        val snapshot = testSnapshot()
        val projectTemplate = ResourceTemplate(
            uriTemplate = "ij-workspace://{instanceKey}/projects/{projectKey}",
            name = "projectKey",
        )
        val matcher = SegmentTreeTemplateMatcher(projectTemplate, ResourceRouteSnapshotRef(snapshot))
        val vfsUri = workspaceVfsUri("file:///tmp/workspace/file.txt")
        assertNull(matcher.match(vfsUri))
    }

    @Test
    fun `project route matches from anchor`() {
        val snapshot = testSnapshot()
        val uri = workspaceFileUri()
        val routeMatch = snapshot.segmentMatch(uri)
        assertEquals(PK, routeMatch?.ancestors?.get("projectKey"))
    }

    @Test
    fun `search query matches with query params`() {
        val snap = withSearchQuerySnapshot()
        val match = snap.segmentMatch("ij-workspace://iu-63341/search?query=abc&limit=10")
        assertNotNull(match)
        assertEquals("iu-63341", match?.ancestors?.get("instanceKey"))
        assertEquals("abc", match?.ancestors?.get("query"))
    }

    @Test
    fun `search query with wrong literal does not match`() {
        val snap = withSearchQuerySnapshot()
        assertNull(snap.segmentMatch("ij-workspace://iu-63341/search?query=abc&limit=11"))
    }

    @Test
    fun `search query with missing required param does not match`() {
        val snap = withSearchQuerySnapshot()
        assertNull(snap.segmentMatch("ij-workspace://iu-63341/search?limit=10"))
    }

    @Test
    fun `optional query route matches without query string`() {
        val snap = withOptionalQuerySnapshot()
        val match = snap.segmentMatch("ij-workspace://iu-63341/search")
        assertNotNull(match)
        assertEquals("iu-63341", match?.ancestors?.get("instanceKey"))
        assertNull(match?.ancestors?.get("query"))
    }

    @Test
    fun `optional query route matches with query string`() {
        val snap = withOptionalQuerySnapshot()
        val match = snap.segmentMatch("ij-workspace://iu-63341/search?query=hello")
        assertNotNull(match)
        assertEquals("hello", match?.ancestors?.get("query"))
    }

    @Test
    fun `search query with no query string does not match`() {
        val snap = withSearchQuerySnapshot()
        assertNull(snap.segmentMatch("ij-workspace://iu-63341/search"))
    }

    @Test
    fun `reserved expansion route can also capture query params`() {
        val snap = withReservedAndQuerySnapshot()
        val match = snap.segmentMatch("ij-workspace://iu-63341/raw/file:///tmp/a.kt?line=10?format=text")
        assertNotNull(match)
        assertEquals("file:///tmp/a.kt?line=10", match?.ancestors?.get("rawVfsUrl"))
        assertEquals("text", match?.ancestors?.get("format"))
    }

    @Test
    fun `reserved expansion route keeps question mark in vfs file name before query params`() {
        val snap = withReservedAndQuerySnapshot()
        val rawVfsUrl = "file:///tmp/workspace/file?name.kt"
        val match = snap.segmentMatch("ij-workspace://iu-63341/raw/$rawVfsUrl?format=text")
        assertNotNull(match)
        assertEquals(rawVfsUrl, match?.ancestors?.get("rawVfsUrl"))
        assertEquals("text", match?.ancestors?.get("format"))
    }

    @Test
    fun `literal match has higher priority than param`() {
        val snapshot = testSnapshot()
        val uri = "ij-workspace://iu-63341/projects/special"
        val match = snapshot.segmentMatch(uri)
        assertNotNull(match)
        assertEquals("special", match?.ancestors?.get("projectKey"))
    }

    // -- Helpers --

    private fun testSnapshot(): ResourceRouteSnapshot {
        val c1 = ResourceSegmentCollector()
        c1.route("server/info") {
            read { ReadResourceResult(emptyList()) }
        }
        c1.route("projects/{projectKey}", anchor = RouteAnchor("projectKey")) {
            read { ReadResourceResult(emptyList()) }
            listTemplates()
        }

        val c2 = ResourceSegmentCollector()
        c2.under(RouteAnchor("projectKey")) {
            route("files/{+relativePath}") {
                read { ReadResourceResult(emptyList()) }
                listTemplates()
            }
            route("vfs/{+rawVfsUrl}") {
                read { ReadResourceResult(emptyList()) }
                listTemplates()
            }
        }

        return ResourceRouteCompiler.compile(
            listOf(
                WorkspaceResourceRouteContribution(
                    featureName = "core",
                    roots = c1.roots,
                    pendingAnchors = c1.pendingAnchors,
                ),
                WorkspaceResourceRouteContribution(
                    featureName = "vfs",
                    roots = c2.roots,
                    pendingAnchors = c2.pendingAnchors,
                ),
            ),
        )
    }

    
    private fun withOptionalQuerySnapshot(): ResourceRouteSnapshot {
        val c = ResourceSegmentCollector()
        c.route("search{?query}") {
            read { ReadResourceResult(emptyList()) }
            listTemplates()
        }
        return ResourceRouteCompiler.compile(
            listOf(
                WorkspaceResourceRouteContribution(
                    featureName = "opt",
                    roots = c.roots,
                    pendingAnchors = c.pendingAnchors,
                ),
            ),
        )
    }

    
    @Test
    fun `no-query and optional-query routes coexist on same path`() {
        val snap = withCoexistingQuerySnapshot()
        // No query URI → matches the no-query route
        val match1 = snap.segmentMatch("ij-workspace://iu-63341/target")
        assertNotNull(match1)
        assertNull(match1?.ancestors?.get("q"))
        // With query URI → matches the optional-query route
        val match2 = snap.segmentMatch("ij-workspace://iu-63341/target?q=hello")
        assertNotNull(match2)
        assertEquals("hello", match2?.ancestors?.get("q"))
    }

    // -- Helpers --

    
    
    @Test
    fun `optional-only query route matches URI without query`() {
        val snap = withSoloOptionalQuerySnapshot()
        val match = snap.segmentMatch("ij-workspace://iu-63341/search")
        assertNotNull(match)
        // q is optional and not present
        assertNull(match?.ancestors?.get("q"))
    }

    @Test
    fun `optional-only query route matches URI with query`() {
        val snap = withSoloOptionalQuerySnapshot()
        val match = snap.segmentMatch("ij-workspace://iu-63341/search?q=hello")
        assertNotNull(match)
        assertEquals("hello", match?.ancestors?.get("q"))
    }

    // -- Helpers --

    
    private fun withSoloOptionalQuerySnapshot(): ResourceRouteSnapshot {
        val c = ResourceSegmentCollector()
        c.route("search{?q}") {
            read { ReadResourceResult(emptyList()) }
            listTemplates()
        }
        return ResourceRouteCompiler.compile(
            listOf(
                WorkspaceResourceRouteContribution(
                    featureName = "test",
                    roots = c.roots,
                    pendingAnchors = c.pendingAnchors,
                ),
            ),
        )
    }

    private fun withCoexistingQuerySnapshot(): ResourceRouteSnapshot {
        val c = ResourceSegmentCollector()
        c.route("target") {
            read { ReadResourceResult(emptyList()) }
            listTemplates()
        }
        c.route("target{?q}") {
            read { ReadResourceResult(emptyList()) }
            listTemplates()
        }
        return ResourceRouteCompiler.compile(
            listOf(
                WorkspaceResourceRouteContribution(
                    featureName = "test",
                    roots = c.roots,
                    pendingAnchors = c.pendingAnchors,
                ),
            ),
        )
    }

    private fun withSearchQuerySnapshot(): ResourceRouteSnapshot {
        val c = ResourceSegmentCollector()
        c.route("search?query={query}&limit=10") {
            read { ReadResourceResult(emptyList()) }
            listTemplates()
        }
        return ResourceRouteCompiler.compile(
            listOf(
                WorkspaceResourceRouteContribution(
                    featureName = "search",
                    roots = c.roots,
                    pendingAnchors = c.pendingAnchors,
                ),
            ),
        )
    }

    private fun withReservedAndQuerySnapshot(): ResourceRouteSnapshot {
        val c = ResourceSegmentCollector()
        c.route("raw/{+rawVfsUrl}?format={format}") {
            read { ReadResourceResult(emptyList()) }
            listTemplates()
        }
        return ResourceRouteCompiler.compile(
            listOf(
                WorkspaceResourceRouteContribution(
                    featureName = "raw",
                    roots = c.roots,
                    pendingAnchors = c.pendingAnchors,
                ),
            ),
        )
    }

    private fun fileMatcher(snapshot: ResourceRouteSnapshot): SegmentTreeTemplateMatcher {
        return SegmentTreeTemplateMatcher(
            ResourceTemplate(
                uriTemplate = "ij-workspace://{instanceKey}/projects/{projectKey}/files/{+relativePath}",
                name = "relativePath",
            ),
            ResourceRouteSnapshotRef(snapshot),
        )
    }

    private fun vfsMatcher(snapshot: ResourceRouteSnapshot): SegmentTreeTemplateMatcher {
        return SegmentTreeTemplateMatcher(
            ResourceTemplate(
                uriTemplate = "ij-workspace://{instanceKey}/projects/{projectKey}/vfs/{+rawVfsUrl}",
                name = "rawVfsUrl",
            ),
            ResourceRouteSnapshotRef(snapshot),
        )
    }

    private fun workspaceFileUri(): String =
        "ij-workspace://$IK/projects/$PK/files/src/main/Foo.kt"

    private fun workspaceVfsUri(raw: String = "file:///tmp/workspace/file.txt"): String =
        "ij-workspace://$IK/projects/$PK/vfs/$raw"

    private companion object {
        const val IK = "iu-63341"
        const val PK = "my-project-a1b2"
    }
}
