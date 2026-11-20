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
import io.ktor.server.plugins.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.contentCharset
import io.ktor.server.request.receiveChannel
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KClass

internal val MarkdownContentType: ContentType = ContentType("text", "markdown").withCharset(Charsets.UTF_8)
internal val XMarkdownContentType: ContentType = ContentType("text", "x-markdown").withCharset(Charsets.UTF_8)
private val PlainTextContentType: ContentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8)

/**
 * Local replacement for [io.ktor.server.request.receiveText]: that Ktor function is public inline
 * and its body compiles a call to the deprecated `HttpHeaders.getContentType` getter into every
 * caller, which the plugin verifier attributes to this plugin and fails the build on (the
 * DEPRECATED_API_USAGES failure level, Ktor 3.4 `HttpHeaders` const migration). Reading the channel
 * directly keeps the deprecated symbol out of our bytecode. The literal in the error message mirrors
 * the upstream text without referencing the deprecated constant.
 */
internal suspend fun ApplicationCall.receiveBodyText(): String {
    return try {
        receiveChannel().readRemaining().readText(request.contentCharset() ?: Charsets.UTF_8)
    } catch (cause: BadContentTypeFormatException) {
        throw BadRequestException("Illegal Content-Type format: ${request.headers["Content-Type"]}", cause)
    }
}

internal val RestJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
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
        val kotlinType = typeInfo.kotlinType ?: return null
        val tree = RestJson.encodeToJsonElement(RestJson.serializersModule.serializer(kotlinType), value)
        val elementType = kotlinType.arguments.firstOrNull()?.type?.classifier as? KClass<*>
        val markdown = MarkdownDocumentRenderer.render(tree, value::class, elementType)
        return TextContent(markdown, contentType.withCharset(charset))
    }

    override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any? = null
}
