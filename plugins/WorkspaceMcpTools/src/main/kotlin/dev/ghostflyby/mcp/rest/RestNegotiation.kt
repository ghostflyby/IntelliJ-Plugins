/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.*
import io.ktor.serialization.kotlinx.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import kotlinx.serialization.json.Json

internal val MarkdownContentType: ContentType = ContentType("text", "markdown").withCharset(Charsets.UTF_8)
internal val XMarkdownContentType: ContentType = ContentType("text", "x-markdown").withCharset(Charsets.UTF_8)
private val PlainTextContentType: ContentType = ContentType.Text.Plain.withCharset(Charsets.UTF_8)

private val RestJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun Application.installWorkspaceRestContentNegotiation() {
    install(ContentNegotiation) {
        val converter = RestNegotiatedResponseConverter(
            jsonConverter = KotlinxSerializationConverter(RestJson),
        )
        register(MarkdownContentType, converter)
        register(XMarkdownContentType, converter)
        register(PlainTextContentType, converter)
        register(ContentType.Application.Json, converter)
        json(RestJson)
    }
}

internal data class RestNegotiatedResponse(
    val jsonValue: Any?,
    val markdownBody: String? = null,
    val plainBody: String? = null,
    val jsonText: String? = null,
    val jsonTypeInfo: TypeInfo? = null,
)

internal inline fun <reified T : Any> negotiatedMarkdown(
    jsonValue: T,
    markdownBody: String,
): RestNegotiatedResponse = RestNegotiatedResponse(
    jsonValue = jsonValue,
    markdownBody = markdownBody,
    jsonTypeInfo = typeInfo<T>(),
)

internal fun negotiatedText(
    jsonText: String,
    textBody: String,
    markdown: Boolean = false,
): RestNegotiatedResponse = RestNegotiatedResponse(
    jsonValue = jsonText,
    markdownBody = textBody.takeIf { markdown },
    plainBody = textBody.takeUnless { markdown },
    jsonText = jsonText,
)

internal inline fun <reified T : Any> negotiatedError(
    jsonValue: T,
    textBody: String,
): RestNegotiatedResponse = RestNegotiatedResponse(
    jsonValue = jsonValue,
    plainBody = textBody,
    jsonTypeInfo = typeInfo<T>(),
)

internal suspend fun ApplicationCall.respondNegotiated(
    response: RestNegotiatedResponse,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respond(status, response)
}

private class RestNegotiatedResponseConverter(
    private val jsonConverter: ContentConverter,
) : ContentConverter {

    override suspend fun serialize(
        contentType: ContentType,
        charset: Charset,
        typeInfo: TypeInfo,
        value: Any?,
    ): OutgoingContent? {
        val response = value as? RestNegotiatedResponse ?: return null
        return when {
            contentType.match(ContentType.Application.Json) -> response.serializeJson(contentType, charset)
            contentType.match(MarkdownContentType) || contentType.match(XMarkdownContentType) ->
                response.markdownBody?.let { TextContent(it, contentType.withCharset(charset)) }

            contentType.match(ContentType.Text.Plain) ->
                (response.plainBody ?: response.markdownBody)?.let { TextContent(it, contentType.withCharset(charset)) }

            else -> null
        }
    }

    private suspend fun RestNegotiatedResponse.serializeJson(
        contentType: ContentType,
        charset: Charset,
    ): OutgoingContent? {
        jsonText?.let { return TextContent(it, contentType.withCharset(charset)) }
        val payload = jsonValue ?: return null
        return jsonConverter.serialize(
            contentType = contentType,
            charset = charset,
            typeInfo = jsonTypeInfo ?: TypeInfo(payload::class),
            value = payload,
        )
    }

    override suspend fun deserialize(charset: Charset, typeInfo: TypeInfo, content: ByteReadChannel): Any? = null
}
