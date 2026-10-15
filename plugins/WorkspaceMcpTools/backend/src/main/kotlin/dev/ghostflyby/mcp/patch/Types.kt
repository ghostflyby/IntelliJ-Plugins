/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.patch

import java.nio.file.Path

internal data class ProjectPatchPath(
    val relativePath: String,
    val nioPath: Path,
    val url: String,
)
