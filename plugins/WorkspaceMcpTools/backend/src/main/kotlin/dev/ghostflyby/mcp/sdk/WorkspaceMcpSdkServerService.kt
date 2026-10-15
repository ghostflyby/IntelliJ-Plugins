/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import dev.ghostflyby.mcp.pluginVersion
import dev.ghostflyby.mcp.rest.WorkspaceRestApplicationContext
import dev.ghostflyby.mcp.rest.installWorkspaceRestApi
import io.ktor.server.cio.*
import io.ktor.server.engine.*
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
        val context = WorkspaceRestApplicationContext(
            port = port,
            instanceKey = workspaceInstanceKey(port),
            version = pluginVersion,
        )
        return scope.embeddedServer(CIO, host = LOOPBACK_HOST, port = port) {
            installWorkspaceRestApi(context)
        }
    }

    private companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
        private const val START_PORT = 63441
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
