package dev.ghostflyby.mcp.sdk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

internal class WorkspaceMcpSdkServerServiceTest {
    @Test
    fun `port range scans ten ports from configured start`() {
        assertEquals(63341..63350, workspaceMcpServerPortRange(63341))
    }

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
