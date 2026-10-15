/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

internal class WorkspaceMcpSdkServerServiceTest {

    @Test
    fun `loopback port probe reports occupied ports unavailable`() {
        ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))

            val port = socket.localPort
            assertFalse(isLoopbackPortAvailable(port))
        }
    }

    @Test
    fun `loopback port probe reports released ports available`() {
        val port = ServerSocket().use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            socket.localPort
        }

        assertTrue(isLoopbackPortAvailable(port))
    }
}
