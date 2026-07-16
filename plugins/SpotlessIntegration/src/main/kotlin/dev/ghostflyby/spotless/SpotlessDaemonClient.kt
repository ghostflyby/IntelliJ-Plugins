/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import dev.ghostflyby.spotless.SpotlessFormatResult.*
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.nio.file.Path
import kotlin.io.path.absolutePathString

private val HTTP_GET: HttpMethod = HttpMethod.parse("GET")
private val HTTP_POST: HttpMethod = HttpMethod.parse("POST")

internal class SpotlessDaemonClient(
    internal var http: HttpClient = HttpClient(CIO),
) {
    suspend fun healthCheck(host: SpotlessDaemonHost): Boolean =
        runCatching {
            http.request {
                method = HTTP_GET
                configureHost(host)
                url {
                    protocol = URLProtocol.HTTP
                    encodedPath = "/"
                }
            }
        }.map { response ->
            response.status == HttpStatusCode.OK
        }.getOrElse { false }

    suspend fun stop(host: SpotlessDaemonHost) {
        http.request {
            method = HTTP_POST
            configureHost(host)
            url {
                protocol = URLProtocol.HTTP
                encodedPath = "/stop"
            }
        }
    }

    suspend fun steps(
        host: SpotlessDaemonHost,
        path: Path,
    ): List<String>? {
        val response = http.request {
            method = HTTP_GET
            configureHost(host)
            url {
                protocol = URLProtocol.HTTP
                encodedPath = "/steps"
            }
            parameter("path", path.normalize().absolutePathString())
        }
        return when (response.status) {
            HttpStatusCode.OK -> response.bodyAsText()
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()

            HttpStatusCode.NotFound -> null
            else -> error("Unexpected steps response status: ${response.status}\n${response.bodyAsText()}")
        }
    }

    suspend fun format(
        host: SpotlessDaemonHost,
        path: Path,
        content: CharSequence,
        skipSteps: List<String> = emptyList(),
    ): SpotlessFormatResult {
        val response = http.request {
            method = HTTP_POST
            configureHost(host)
            url {
                protocol = URLProtocol.HTTP
                encodedPath = "/"
            }
            parameter("path", path.normalize().absolutePathString())
            if (content.isEmpty()) {
                parameter("dryrun", "")
            }
            skipSteps.forEach { step ->
                parameter("skipStep", step)
            }
            contentType(ContentType.Text.Plain)
            setBody(content)
        }
        return when (response.status) {
            HttpStatusCode.OK -> {
                val formatted = response.bodyAsText()
                if (formatted.isEmpty()) {
                    Clean
                } else {
                    Dirty(formatted)
                }
            }

            HttpStatusCode.NotFound -> NotCovered
            HttpStatusCode.InternalServerError -> Error(response.bodyAsText())
            else -> Error("Unexpected response status: ${response.status}\n${response.bodyAsText()}")
        }
    }

    fun close() {
        http.close()
    }

    private fun HttpRequestBuilder.configureHost(endpoint: SpotlessDaemonHost) {
        when (endpoint) {
            is SpotlessDaemonHost.Localhost -> {
                url.host = "localhost"
                url.port = endpoint.port
            }

            is SpotlessDaemonHost.Unix -> unixSocket(endpoint.path.toString())
        }
    }
}
