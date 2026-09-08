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
import java.nio.file.Files
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

    private const val WSL_UNC_ROOT = "\\\\wsl.localhost"

    /**
     * Installed distribution names: enumerates the WSL UNC root first, falling back to
     * `wsl.exe -l -q` (the bare-server UNC listing is not supported by every redirector);
     * empty when WSL is absent.
     */
    fun installedDistributions(): List<String> {
        val viaUncRoot = runCatching {
            Files.newDirectoryStream(Path.of(WSL_UNC_ROOT)).use { stream ->
                stream.asSequence().map { it.fileName.toString() }.filter { it.isNotBlank() }.sorted().toList()
            }
        }.getOrDefault(emptyList())
        if (viaUncRoot.isNotEmpty()) {
            return viaUncRoot
        }
        return wslListOutput()
    }

    /**
     * Human-readable summary of the discovery state, for condition messages — distinguishes a
     * failed UNC enumeration from a WSL host without distributions.
     */
    fun describeDiscovery(): String {
        val uncError = runCatching {
            Files.newDirectoryStream(Path.of(WSL_UNC_ROOT)).use { stream ->
                stream.asSequence().map { it.fileName.toString() }.toList()
            }
        }.exceptionOrNull()
        val distributions = installedDistributions()
        return when {
            distributions.isNotEmpty() -> "WSL distributions: ${distributions.joinToString()}"
            uncError != null -> "UNC root listing failed ($uncError) and the wsl.exe fallback found none"
            else -> "UNC root listed no distributions and the wsl.exe fallback found none"
        }
    }

    private fun wslListOutput(): List<String> =
        runCatching {
            val bytes = ProcessBuilder("wsl.exe", "-l", "-q")
                .redirectErrorStream(true)
                .start()
                .inputStream
                .readBytes()
            // `wsl.exe -l` historically writes UTF-16LE (with or without BOM); newer builds use UTF-8
            val text = when {
                bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
                    String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
                bytes.size >= 2 && bytes[1] == 0.toByte() ->
                    String(bytes, Charsets.UTF_16LE)
                else ->
                    String(bytes, Charsets.UTF_8)
            }
            text.lineSequence()
                .map { it.trim().replace("\u0000", "") }
                .filter { it.isNotEmpty() && !it.contains(' ') }
                .sorted()
                .toList()
        }.getOrDefault(emptyList())

    /**
     * The distribution tests run against: an Ubuntu-based one when installed (utility distros
     * such as `docker-desktop` otherwise sort first alphabetically), else the alphabetically
     * first installed one.
     */
    fun defaultDistribution(): String? =
        installedDistributions().firstOrNull { it.contains("ubuntu", ignoreCase = true) }
            ?: installedDistributions().firstOrNull()

    fun requireDistribution(): String =
        requireNotNull(defaultDistribution()) { "No WSL distribution is installed" }

    private fun distribution(): WSLDistribution =
        WslPath.parseWindowsUncPath("$WSL_UNC_ROOT\\${requireDistribution()}\\tmp").let { parsed ->
            requireNotNull(parsed?.distribution) { "Cannot resolve the WSL distribution" }
        }

    /** `\\wsl.localhost\<detected distro>` UNC root of the distribution */
    fun distributionUncRoot(): Path = Path.of(WSL_UNC_ROOT, requireDistribution())

    /** `\\wsl.localhost\<detected distro>\tmp` */
    fun tmpRoot(): Path = distributionUncRoot().resolve("tmp")

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
