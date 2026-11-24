/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.wsl

import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.junit5.TestApplication
import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.settings.MillExecutionTarget
import dev.ghostflyby.mill.settings.MillExecutionTargets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.nio.file.Path

/**
 * Real-distribution integration test: self-skips when no WSL distribution is installed (Linux,
 * macOS, or a bare Windows runner), mirroring EnhancedHotSwapEnabler's `@EnabledOnWsl` rollout
 * pattern without needing the Mill launcher inside the distribution.
 *
 * The lookup paths exercised here spawn `wsl.exe` directly (not through GeneralCommandLine), so
 * this test also pins that WSL execution keeps working on hosts where the platform's EEL daemon
 * refuses to start.
 */
@TestApplication
@EnabledOnOs(OS.WINDOWS)
internal class MillWslExecutionTest {

    @Test
    fun `detects the wsl target for unc project roots`() {
        // Detection parses the UNC root, so it needs no installed distribution.
        val projectRoot = Path.of("""\\wsl.localhost\Ubuntu-24.04\home""")

        val target = MillExecutionTargets.detect(projectRoot)

        assertTrue(target is MillExecutionTarget.Wsl, "expected a WSL target for $projectRoot but got $target")
        assertEquals("Ubuntu-24.04", (target as MillExecutionTarget.Wsl).distribution)
    }

    @Test
    fun `login shell lookup resolves commands inside the distribution`() {
        val distribution = installedDistribution() ?: return
        val target = MillExecutionTarget.Wsl(distribution.id)
        val projectRoot = Path.of("""\\wsl.localhost\${distribution.id}\home""")

        val shellPath = MillCommandLineUtil.findTargetCommandPath(projectRoot, target, "sh")

        assertNotNull(shellPath, "`sh` must resolve inside ${distribution.id}")
        assertTrue(shellPath!!.startsWith("/"), "expected a target-style path but got $shellPath")
        assertNull(
            MillCommandLineUtil.findTargetCommandPath(projectRoot, target, "ghostflyby-missing-command"),
            "an unknown command must not resolve",
        )
        assertEquals(
            shellPath,
            MillCommandLineUtil.resolveTargetExecutable(projectRoot, target, "sh"),
            "bare commands must go through the login-shell lookup",
        )
    }

    private fun installedDistribution() = runCatching {
        service<WslDistributionManager>().installedDistributionsFuture.get()
    }.getOrNull()
        ?.sortedBy { it.id.lowercase() }
        ?.firstOrNull { it.id.contains("ubuntu", ignoreCase = true) }
        ?: run {
            assumeTrue(false, "No WSL distribution installed; skipping Mill WSL integration test")
            null
        }
}
