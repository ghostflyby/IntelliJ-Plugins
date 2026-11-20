/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import dev.ghostflyby.spotless.SpotlessDaemonRegistry.Companion.daemonStartupTimeout
import dev.ghostflyby.spotless.SpotlessFormatResult.*
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider.Endpoint
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private val HTTP_GET: HttpMethod = HttpMethod.parse("GET")
private val HTTP_POST: HttpMethod = HttpMethod.parse("POST")

internal class SpotlessDaemonTransportException(
    operation: String,
    endpoint: Endpoint,
    cause: Throwable? = null,
) : RuntimeException("Spotless daemon $operation failed: $endpoint", cause)

internal class SpotlessDaemonClient(
    internal var http: HttpClient = HttpClient(CIO),
) {
    suspend fun healthCheck(endpoint: Endpoint): Boolean = try {
        val response = http.request {
            method = HTTP_GET
            configureEndpoint(endpoint)
            url {
                protocol = URLProtocol.HTTP
                encodedPath = "/"
            }
        }
        response.status == HttpStatusCode.OK
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        false
    }

    suspend fun awaitReady(
        endpoint: Endpoint,
    ) {
        val ready = withTimeoutOrNull(daemonStartupTimeout) {
            while (!healthCheck(endpoint)) {
                delay(readinessRetryDelay)
            }
            true
        } ?: false
        currentCoroutineContext().ensureActive()
        if (!ready) {
            throw SpotlessDaemonTransportException("readiness check", endpoint)
        }
    }

    suspend fun stop(endpoint: Endpoint) {
        http.request {
            method = HTTP_POST
            configureEndpoint(endpoint)
            url {
                protocol = URLProtocol.HTTP
                encodedPath = "/stop"
            }
        }
    }

    suspend fun steps(
        endpoint: Endpoint,
        path: Path,
    ): List<String>? {
        val response = requestOrThrow("steps request", endpoint) {
            method = HTTP_GET
            configureEndpoint(endpoint)
            url {
                protocol = URLProtocol.HTTP
                encodedPath = "/steps"
            }
            parameter("path", path.normalize().absolutePathString())
        }
        return when (response.status) {
            HttpStatusCode.OK -> response.bodyAsTextOrThrow("steps response", endpoint)
                .lineSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .toList()

            HttpStatusCode.NotFound -> null
            else -> error(
                "Unexpected steps response status: ${response.status}\n" +
                        response.bodyAsTextOrThrow("steps response", endpoint),
            )
        }
    }

    suspend fun format(
        endpoint: Endpoint,
        path: Path,
        content: CharSequence,
        skipSteps: List<String> = emptyList(),
    ): SpotlessFormatResult {
        val response = requestOrThrow("format request", endpoint) {
            method = HTTP_POST
            configureEndpoint(endpoint)
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
            setBody(content.toString())
        }
        return when (response.status) {
            HttpStatusCode.OK -> {
                val formatted = response.bodyAsTextOrThrow("format response", endpoint)
                if (formatted.isEmpty()) {
                    Clean
                } else {
                    Dirty(formatted)
                }
            }

            HttpStatusCode.NotFound -> NotCovered
            HttpStatusCode.InternalServerError -> Error(response.bodyAsTextOrThrow("format response", endpoint))
            else -> Error(
                "Unexpected response status: ${response.status}\n" +
                        response.bodyAsTextOrThrow("format response", endpoint),
            )
        }
    }

    fun close() {
        http.close()
    }

    private suspend fun requestOrThrow(
        operation: String,
        endpoint: Endpoint,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpResponse = try {
        http.request(block)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw SpotlessDaemonTransportException(operation, endpoint, error)
    }

    private suspend fun HttpResponse.bodyAsTextOrThrow(
        operation: String,
        endpoint: Endpoint,
    ): String = try {
        bodyAsText()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        throw SpotlessDaemonTransportException(operation, endpoint, error)
    }

    private fun HttpRequestBuilder.configureEndpoint(endpoint: Endpoint) {
        when (endpoint) {
            is Endpoint.Localhost -> {
                url.host = "localhost"
                url.port = endpoint.port.toInt()
            }

            is Endpoint.UnixSocket -> unixSocket(endpoint.path.toString())
        }
    }

    private companion object {
        val readinessRetryDelay: Duration = 100.milliseconds
    }
}
