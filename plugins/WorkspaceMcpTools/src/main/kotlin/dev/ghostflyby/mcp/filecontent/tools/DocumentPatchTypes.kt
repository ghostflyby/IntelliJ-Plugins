/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent.tools

import kotlinx.schema.Schema
import kotlinx.serialization.Serializable

@Schema
@Serializable
internal data class DocumentSdkWriteResult(
    val textLength: Int,
    val lineCount: Int,
    val modificationStamp: Long,
)

@Schema
@Serializable
internal data class DocumentPatchResult(
    val succeeded: List<PatchFileSuccess>,
    val failed: List<PatchFileError>,
)

@Schema
@Serializable
internal data class PatchFileSuccess(
    val path: String,
    val operation: String,
    val result: DocumentSdkWriteResult? = null,
)

@Schema
@Serializable
internal data class PatchFileError(
    val path: String,
    val reason: String,
)
