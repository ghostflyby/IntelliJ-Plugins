/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*

internal val MarkdownContentType: ContentType = ContentType("text", "markdown").withCharset(Charsets.UTF_8)

internal fun ApplicationCall.wantsJson(): Boolean {
    val acceptHeaders = request.headers.getAll(HttpHeaders.Accept) ?: return false
    return acceptHeaders
        .asSequence()
        .flatMap { it.split(',') }
        .map { it.substringBefore(';').trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { raw -> runCatching { ContentType.parse(raw) }.getOrNull() }
        .any { it.match(ContentType.Application.Json) }
}

internal suspend fun ApplicationCall.respondNegotiatedText(
    jsonText: String,
    textBody: String,
    textContentType: ContentType = MarkdownContentType,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    if (wantsJson()) {
        respondText(jsonText, ContentType.Application.Json, status)
    } else {
        respondText(textBody, textContentType, status)
    }
}

internal suspend inline fun <reified T : Any> ApplicationCall.respondNegotiated(
    jsonValue: T,
    textBody: String,
    textContentType: ContentType = MarkdownContentType,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    if (wantsJson()) {
        respond(status, jsonValue)
    } else {
        respondText(textBody, textContentType, status)
    }
}

internal suspend inline fun <reified T : Any> ApplicationCall.respondNegotiatedError(
    status: HttpStatusCode,
    jsonValue: T,
    textBody: String,
) {
    if (wantsJson()) {
        respond(status, jsonValue)
    } else {
        respondText(textBody, ContentType.Text.Plain.withCharset(Charsets.UTF_8), status)
    }
}
