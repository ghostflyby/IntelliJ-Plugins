/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import dev.ghostflyby.mcp.rest.markdown.MarkdownDocumentRenderer
import dev.ghostflyby.mcp.rest.markdown.TextBody
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import kotlinx.serialization.json.Json

internal val MarkdownContentType: ContentType = ContentType("text", "markdown").withCharset(Charsets.UTF_8)
internal val XMarkdownContentType: ContentType = ContentType("text", "x-markdown").withCharset(Charsets.UTF_8)
private val PlainTextContentType: ContentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8)

internal val RestJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun Application.installWorkspaceRestContentNegotiation() {
    install(ContentNegotiation) {
        val model = MarkdownModelConverter()
        // Markdown is registered first so Accept: */* (or no Accept) defaults to markdown.
        register(MarkdownContentType, model)
        register(XMarkdownContentType, model)
        register(PlainTextContentType, model)
        json(RestJson)
    }
}

/**
 * Renders response values for the textual content types. [TextBody] values render themselves
 * (identical for plain and Markdown); other models render as a Markdown document via
 * [MarkdownDocumentRenderer]. Raw [Map]/[CharSequence] values and non-[TextBody] models on
 * text/plain return null, falling through to the JSON converter.
 */
private class MarkdownModelConverter : ContentConverter {
    override suspend fun serialize(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?,
    ): OutgoingContent? {
        if (value == null) return null
        val isMarkdown = contentType.match(MarkdownContentType) || contentType.match(XMarkdownContentType)
        val isPlain = contentType.match(ContentType.Text.Plain)
        if (!isMarkdown && !isPlain) return null
        if (value is TextBody) {
            return TextContent(value.renderTextBody(), contentType.withCharset(charset))
        }
        // Raw maps/strings keep their JSON form; models have no plain-text form.
        if (value is Map<*, *> || value is CharSequence || !isMarkdown) return null
        val markdown = MarkdownDocumentRenderer.render(value, typeInfo.kotlinType?.arguments?.firstOrNull()?.type)
        return TextContent(markdown, contentType.withCharset(charset))
    }

    override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any? = null
}
