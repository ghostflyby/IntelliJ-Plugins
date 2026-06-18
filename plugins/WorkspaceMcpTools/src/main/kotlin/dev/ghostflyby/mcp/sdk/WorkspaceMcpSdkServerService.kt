/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import dev.ghostflyby.mcp.rest.installWorkspaceRestContentNegotiation
import dev.ghostflyby.mcp.rest.restApi
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

@Service(Service.Level.APP)
internal class WorkspaceMcpSdkServerService(
    scope: CoroutineScope,
) {
    private val logger = logger<WorkspaceMcpSdkServerService>()

    init {
        scope.launch {
            val startedServer = startServerWithFallback()
            try {
                @Suppress("HttpUrlsUsage")
                logger.info("Workspace MCP REST server started at http://$LOOPBACK_HOST:${startedServer.port}/api/v1")
                awaitCancellation()
            } finally {
                startedServer.engine.stopSuspend()
            }
        }
    }

    private suspend fun startServerWithFallback(): StartedWorkspaceMcpServer {
        val settings = service<WorkspaceMcpSdkServerSettings>()
        val startPort = settings.port
        for (port in workspaceMcpServerPortRange(startPort)) {
            if (!isLoopbackPortAvailable(port)) {
                logger.warn("Port $port is in use, trying ${port + 1}")
                continue
            }

            val engine = createServer(port)
            try {
                engine.startSuspend(wait = false)
                if (port != startPort) {
                    settings.port = port
                }
                return StartedWorkspaceMcpServer(engine, port)
            } catch (cause: Throwable) {
                engine.stopSuspend()
                if (cause.findBindException() == null) {
                    throw cause
                }
                logger.warn("Port $port is in use, trying ${port + 1}")
            }
        }
        error("All ports $startPort-${startPort + WorkspaceMcpServerPortFallbackCount - 1} are in use")
    }

    private fun createServer(port: Int): EmbeddedServer<*, *> {
        return embeddedServer(CIO, host = LOOPBACK_HOST, port = port) {
            installWorkspaceRestContentNegotiation()
            install(Resources)
            routing { restApi() }
        }
    }

    private companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
    }
}

internal const val WorkspaceMcpServerPortFallbackCount: Int = 10

internal fun workspaceMcpServerPortRange(startPort: Int): IntRange {
    return startPort until startPort + WorkspaceMcpServerPortFallbackCount
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

private data class StartedWorkspaceMcpServer(
    val engine: EmbeddedServer<*, *>,
    val port: Int,
)
