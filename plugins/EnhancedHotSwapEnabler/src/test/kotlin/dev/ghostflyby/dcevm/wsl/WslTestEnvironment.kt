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
import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.runBlockingCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.html.CommandType
import java.nio.file.Path

/**
 * Environment for WSL tests. Tests self-enable through [EnabledOnWsl] (JUnit's `EnabledOnOs`
 * family): the distribution is discovered from the WSL UNC root `\\wsl.localhost` — no distro
 * name property or tag switch involved.
 *
 * All in-distro operations run through [execInDistro] (the platform's patched WSL command line),
 * never through raw `wsl.exe` calls mixed with 9P file operations.
 */
internal object WslTestEnvironment {

    fun installedDistributions(): List<WSLDistribution> {
        return runBlocking {
            service<WslDistributionManager>().installedDistributionsFuture.await()
        }
    }

    /**
     * The distribution tests run against: an Ubuntu-based one when installed (utility distros
     * such as `docker-desktop` otherwise sort first alphabetically), else the alphabetically
     * first installed one.
     */
    fun distribution(): WSLDistribution =
        installedDistributions().firstOrNull { it.id.contains("ubuntu", ignoreCase = true) }
            ?: installedDistributions().first()


    /** `\\wsl.localhost\<detected distro>` UNC root of the distribution */
    fun distributionUncRoot(): Path = distribution().getUNCRootPath()

    /** `\\wsl.localhost\<detected distro>\tmp` */
    fun tmpRoot(): Path = distributionUncRoot().resolve("tmp")

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
        val commandLine = runBlockingCancellable {

            distribution().patchCommandLine(
                GeneralCommandLine("/bin/sh", "-c", command).withRedirectErrorStream(true),
                null,
                // force the wsl.exe launch: the IJent launch path requires a cancellable
                // context, which plain test threads do not have
                WSLCommandLineOptions().setLaunchWithWslExe(true),
            )
        }
        val process = commandLine.createProcess()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        check(exitCode == 0) { "in-distro command failed (exit=$exitCode): ${CommandType.command}\n$output" }
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
