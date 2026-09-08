/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.dcevm.wsl

import com.intellij.testFramework.junit5.TestApplication
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Verifies the path conversion used by the Gradle init script / env vars: for WSL projects the
 * daemon runs inside the distribution, so host drive paths must map to `/mnt/<drive>/...` and
 * same-distribution UNC paths must convert to Linux paths. The local case runs everywhere; the
 * WSL cases self-enable on Windows hosts with a WSL distribution.
 */
@TestApplication
internal class WslGradlePathConversionTest {

    @Test
    fun `local project keeps host path untouched`() {
        val host = Path.of("C:\\work\\project", "lib", "agent.jar")
        Assertions.assertEquals(host.toString(), toDaemonVisiblePath("C:\\work\\project", host))
    }

    @Test
    @EnabledOnWsl
    fun `wsl project maps windows drive path to automount root`() {
        val root = WslTestEnvironment.distributionUncRoot().toString()
        val mapped = toDaemonVisiblePath("$root\\home\\ci\\project", Path.of("C:\\Users\\ci\\hotswap-agent.jar"))
        Assertions.assertEquals("/mnt/c/Users/ci/hotswap-agent.jar", mapped)
    }

    @Test
    @EnabledOnWsl
    fun `unc path inside the same distro converts to linux path`() {
        val jar = WslTestEnvironment.distributionUncRoot().resolve("opt").resolve("lib").resolve("agent.jar")
        val projectPath = "${WslTestEnvironment.distributionUncRoot()}\\home\\ci\\project"
        Assertions.assertEquals("/opt/lib/agent.jar", toDaemonVisiblePath(projectPath, jar))
    }
}
