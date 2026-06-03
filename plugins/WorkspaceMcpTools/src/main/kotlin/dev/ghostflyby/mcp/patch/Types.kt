package dev.ghostflyby.mcp.patch

import java.nio.file.Path

internal data class ProjectPatchPath(
    val relativePath: String,
    val nioPath: Path,
    val url: String,
)
