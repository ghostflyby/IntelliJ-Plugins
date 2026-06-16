/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import java.net.BindException
import dev.ghostflyby.mcp.rest.installWorkspaceRestContentNegotiation
import dev.ghostflyby.mcp.rest.restApi
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Service(Service.Level.APP)
internal class WorkspaceMcpSdkServerService(
    private val scope: CoroutineScope,
) {
    private val logger = logger<WorkspaceMcpSdkServerService>()

    init {
        if (!ApplicationManager.getApplication().isUnitTestMode) {
            scope.launch {
                val port = startServerWithFallback()
                @Suppress("HttpUrlsUsage")
                logger.info("Workspace MCP REST server started at http://$LOOPBACK_HOST:$port/api/v1")
            }
        }
    }

    private suspend fun startServerWithFallback(): Int {
        val settings = service<WorkspaceMcpSdkServerSettings>()
        var port = settings.port
        while (port < settings.port + 10) {
            try {
                embeddedServer(CIO, host = LOOPBACK_HOST, port = port) {
                    installWorkspaceRestContentNegotiation()
                    install(Resources)
                    routing { restApi() }
                }.startSuspend(wait = true)
                if (port != settings.port) {
                    @OptIn(com.intellij.util.xmlb.SettingsInternalApi::class)
                    settings.state = settings.state.copy(port = port)
                }
                return port
            } catch (e: BindException) {
                logger.warn("Port $port is in use, trying ${port + 1}")
                port++
            }
        }
        error("All ports ${settings.port}-${settings.port + 9} are in use")
    }

    private companion object {
        private const val LOOPBACK_HOST = "127.0.0.1"
    }
}
