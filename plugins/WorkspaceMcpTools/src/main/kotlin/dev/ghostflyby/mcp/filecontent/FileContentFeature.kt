package dev.ghostflyby.mcp.filecontent

import dev.ghostflyby.mcp.server.WorkspaceMcpFeature
import dev.ghostflyby.mcp.server.WorkspaceMcpFeatureRegistrationContext

/**
 * File content feature: resource reads migrated to REST API at /api/v1/.
 * File writes migrated to REST API. No MCP tools remain.
 */
internal class FileContentFeature : WorkspaceMcpFeature {
    override val featureName: String = "file-content"
    override fun WorkspaceMcpFeatureRegistrationContext.register() {
    }
}
