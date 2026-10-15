/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest.markdown

/**
 * A response type that owns its plain-text/markdown rendering. The content converter emits
 * [renderTextBody] for every textual content type (text/plain, text/markdown, text/x-markdown),
 * while JSON is still derived from the type's `@Serializable` shape.
 */
internal interface TextBody {
    fun renderTextBody(): String
}
