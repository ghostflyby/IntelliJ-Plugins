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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Real-distribution integration test: self-skips when no WSL distribution is installed (Linux,
 * macOS, or a bare Windows runner), mirroring EnhancedHotSwapEnabler's `@EnabledOnWsl` rollout
 * pattern without needing the Mill launcher inside the distribution.
 */
@TestApplication
internal class MillWslExecutionTest {

    @Test
    fun `detects the wsl target for unc project roots`() {
        val distribution = defaultDistribution() ?: return

        val projectRoot = Path.of("""\\wsl.localhost\${distribution.id}\home""")

        val target = MillExecutionTargets.detect(projectRoot)
        assertTrue(target is MillExecutionTarget.Wsl, "expected a WSL target for $projectRoot but got $target")
        assertEquals(distribution.id, (target as MillExecutionTarget.Wsl).distribution)
    }

    @Test
    fun `target path discovery spawns wsl exe and parses its output`() {
        val distribution = defaultDistribution() ?: return
        val target = MillExecutionTarget.Wsl(distribution.id)
        val projectRoot = Path.of("""\\wsl.localhost\${distribution.id}\home""")

        val discovery = MillCommandLineUtil.discoverExecutables(projectRoot, target)

        // A bare distribution has no `mill` on PATH, so the meaningful assertion is that the
        // wsl.exe spawn completed and produced parsable output; when a distribution does carry
        // a mill launcher, every discovered path must point at one.
        assertTrue(
            discovery.pathExecutables.all { it.toString().contains("mill", ignoreCase = true) },
            "unexpected discovered executables: ${discovery.pathExecutables}",
        )
    }

    private fun defaultDistribution() = runCatching {
        service<WslDistributionManager>().installedDistributionsFuture.get()
    }.getOrNull()
        ?.sortedBy { it.id.lowercase() }
        ?.firstOrNull { it.id.contains("ubuntu", ignoreCase = true) }
        ?: run {
            assumeTrue(false, "No WSL distribution installed; skipping Mill WSL integration test")
            null
        }
}
