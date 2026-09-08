/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")
// WslPath / WSLDistribution.patchCommandLine / WSLCommandLineOptions are Experimental / evolving
// platform APIs in 2026.1; track them here. In-distro setup deliberately goes through the same
// patched-command-line mechanism as production code — mixing it with raw `wsl.exe` calls or
// 9P-created files has proven racy (files created via \\wsl.localhost were not immediately
// visible to `wsl chmod`).

package dev.ghostflyby.dcevm.wsl

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import com.intellij.util.system.OS
import org.junit.jupiter.api.Assumptions
import java.nio.file.Files
import java.nio.file.Path

/**
 * Environment for WSL functional tests: the distribution name is forwarded from the CI WSL job
 * (or local WSL development) via `-PwslDistro=<distro>` as the `wsl.distro` system property by the
 * `repo.intellij-lib` convention plugin; tests skip themselves via [assumeAvailable] when unset.
 *
 * All in-distro operations run through [execInDistro] (the platform's patched WSL command line),
 * never through raw `wsl.exe` calls mixed with 9P file operations.
 */
internal object WslTestEnvironment {

    val distro: String?
        get() = System.getProperty("wsl.distro")?.takeIf { it.isNotBlank() }

    fun requireDistro(): String = requireNotNull(distro) { "wsl.distro is not set" }

    fun assumeAvailable() {
        Assumptions.assumeTrue(OS.CURRENT == OS.Windows) { "WSL tests require a Windows host" }
        Assumptions.assumeTrue(!distro.isNullOrBlank()) { "wsl.distro is not set; WSL tests are skipped" }
    }

    private fun distribution(): WSLDistribution =
        requireNotNull(WslPath.parseWindowsUncPath("\\\\wsl.localhost\\${requireDistro()}\\tmp")?.distribution) { "Cannot resolve WSL distribution $distro" }

    /** `\\wsl.localhost\<distro>\tmp`; falls back to a placeholder name when distro is unset (tests assume-skip first) */
    fun tmpRoot(): Path = Path.of("\\\\wsl.localhost", distro ?: "unset", "tmp")

    fun newIsolatedDir(prefix: String): Path = Files.createTempDirectory(tmpRoot(), prefix)

    /** Converts a `\\wsl.localhost\<distro>\...` UNC path into its in-distribution Linux path */
    fun toLinuxPath(wslUncPath: Path): String {
        val wsl = WslPath.parseWindowsUncPath(wslUncPath.toString())
            ?: error("Not a WSL UNC path: $wslUncPath")
        return wsl.linuxPath
    }

    /** Runs [command] via `/bin/sh -c` inside the distribution and fails on a non-zero exit code */
    fun execInDistro(command: String) {
        // patchCommandLine resolves the distro shell path via runBlockingCancellable, which
        // requires a ProgressIndicator on the current thread — install an empty one for the
        // plain test thread
        val commandLine = ProgressManager.getInstance().runProcess(
            Computable {
                distribution().patchCommandLine(
                    GeneralCommandLine("/bin/sh", "-c", command).withRedirectErrorStream(true),
                    null,
                    // force the wsl.exe launch: the IJent launch path requires a cancellable
                    // context, which plain test threads do not have
                    WSLCommandLineOptions().setLaunchWithWslExe(true),
                )
            },
            EmptyProgressIndicator(),
        )
        val process = commandLine.createProcess()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "in-distro command failed (exit=$exitCode): $command\n$output" }
    }

    /**
     * Generates a fake `bin/java` under [jdkHome] (a POSIX script echoing one PrintFlagsFinal-style
     * line) entirely inside the distribution: `mkdir`/`printf`/`chmod` run in one `sh -c`, so the
     * script and its executable bit never depend on 9P write visibility.
     */
    fun createFakeJdk(jdkHome: Path, printFlagsFinalLine: String) {
        val linuxDir = toLinuxPath(jdkHome)
        execInDistro(
            "mkdir -p '$linuxDir/bin' && " +
                "printf '%s\\n' '#!/bin/sh' 'echo \"$printFlagsFinalLine\"' > '$linuxDir/bin/java' && " +
                "chmod +x '$linuxDir/bin/java'",
        )
    }

    /** Creates the `lib/dcevm/` directory layout inside the distribution (simulating a DCEVM alt-jvm installation) */
    fun createAltJvmLayout(jdkHome: Path) {
        val linuxDir = toLinuxPath(jdkHome)
        execInDistro("mkdir -p '$linuxDir/lib/dcevm'")
    }
}
