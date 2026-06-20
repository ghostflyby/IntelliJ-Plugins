/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import dev.ghostflyby.mcp.rest.installWorkspaceRestContentNegotiation
import dev.ghostflyby.mcp.rest.restApi
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

@Service(Service.Level.APP)
internal class WorkspaceMcpSdkServerService(
    private val scope: CoroutineScope,
) {
    private val logger = logger<WorkspaceMcpSdkServerService>()
//    private var server: EmbeddedServer<*, *>? = null

    init {
        scope.launch {
            runServerWithFallback()
        }
    }

    var port = START_PORT

    private suspend fun runServerWithFallback() {
        for (port in START_PORT..<START_PORT + 100) {
            if (!isLoopbackPortAvailable(port)) {
                logger.warn("Port $port is in use, trying ${port + 1}")
                continue
            }

            this.port = port
            val engine = createServer(port)
            try {
                @Suppress("HttpUrlsUsage")
                logger.info("Workspace MCP REST server starting at http://$LOOPBACK_HOST:$port/api/v1")
                engine.startSuspend(wait = true)
                return
            } catch (cause: Throwable) {
                if (cause.findBindException() == null) {
                    throw cause
                }
                logger.warn("Port $port is in use, trying ${port + 1}")
            } finally {
                engine.stop(500, 1000)
            }
        }
    }

    private fun createServer(port: Int): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        return scope.embeddedServer(CIO, host = LOOPBACK_HOST, port = port) {
            installWorkspaceRestContentNegotiation()
            install(Resources)
            routing { restApi() }
        }
    }

    private companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val START_PORT = 63341
    }
}

internal fun isLoopbackPortAvailable(port: Int): Boolean {
    return try {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            true
        }
    } catch (_: BindException) {
        false
    }
}

private fun Throwable.findBindException(): BindException? {
    var current: Throwable? = this
    while (current != null) {
        if (current is BindException) return current
        current = current.cause
    }
    return null
}
