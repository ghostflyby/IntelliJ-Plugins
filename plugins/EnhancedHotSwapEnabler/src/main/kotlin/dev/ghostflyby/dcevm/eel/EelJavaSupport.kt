/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")
// EEL exec (com.intellij.platform.eel, @ApiStatus.Experimental) is the primary process surface
// here: one code path serves local, WSL and container JDKs through their EelApi. The EEL fs /
// MultiRoutingFileSystem layers are deliberately NOT used — fs is @ApiStatus.Internal and the
// routing provider is product-gated in 2026.1, so WSL UNC paths may resolve to
// LocalEelDescriptor; those fall back to WSLDistribution.executeOnWsl (the platform's WSL
// execution mechanism, which internally picks IJent or wsl.exe). WslPath is used for that
// detection only. Track platform API changes in this file.

package dev.ghostflyby.dcevm.eel

import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.asEelPath
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.toEelApi
import com.intellij.platform.eel.spawnProcess
import dev.ghostflyby.dcevm.DCEVM_JVM_OPTION_NAME
import dev.ghostflyby.dcevm.DCEVMSupport
import dev.ghostflyby.dcevm.isDcevmInstalledAsAltJvm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

private val logger = Logger.getInstance("dev.ghostflyby.dcevm.eel")

private const val PRINT_FLAGS_TIMEOUT_MS = 60_000

/**
 * Detects the DCEVM support level of the JDK at [javaHome] by running
 * `<javaHome>/bin/java -XX:+PrintFlagsFinal -version` inside the environment hosting the JDK —
 * local machine, WSL distribution or dev container.
 *
 * Suspend by design: callers must invoke it from a coroutine and never block the dispatching
 * thread (no `runBlocking`, no progress-indicator tricks). The WSL fallback blocks an IO
 * dispatcher thread through the platform's synchronous WSL execution API.
 * The EEL exec surface is `@ApiStatus.Experimental` in 2026.1.
 */
internal suspend fun detectDcevmSupport(javaHome: Path): DCEVMSupport {
    if (isDcevmInstalledAsAltJvm(javaHome)) {
        return DCEVMSupport.AltJvm
    }

    val javaExecutable = javaHome.resolve("bin/java")
    val descriptor = javaExecutable.getEelDescriptor()
    val lines: Sequence<String> = when {
        descriptor !== LocalEelDescriptor -> execViaEel(descriptor, javaExecutable.asEelPath().toString())
        else -> {
            val wsl = WslPath.parseWindowsUncPath(javaExecutable.toString())
            if (wsl != null) {
                // The EEL WSL layer is product-gated and unavailable here — fall back to the
                // platform's WSL execution instead of CreateProcess-ing the Linux ELF locally.
                withContext(Dispatchers.IO) {
                    execViaWslDistribution(wsl.distribution, wsl.linuxPath)
                }
            } else {
                execViaEel(LocalEelDescriptor, javaExecutable.toString())
            }
        }
    }
    return classifyFlagsOutput(lines)
}

private suspend fun execViaEel(descriptor: EelDescriptor, exe: String): Sequence<String> {
    val process = descriptor.toEelApi()
        .exec
        .spawnProcess(exe)
        .args("-XX:+PrintFlagsFinal", "-version")
        .eelIt()
    return process.convertToJavaProcess()
        .inputStream
        .bufferedReader()
        .readLines()
        .asSequence()
}

private fun execViaWslDistribution(distribution: WSLDistribution, linuxJavaExecutable: String): Sequence<String> {
    val output = distribution.executeOnWsl(
        listOf(linuxJavaExecutable, "-XX:+PrintFlagsFinal", "-version"),
        WSLCommandLineOptions().setLaunchWithWslExe(true),
        PRINT_FLAGS_TIMEOUT_MS,
        null,
    )
    if (output.exitCode != 0) {
        logger.warn("WSL flags check failed (exit=${output.exitCode}): ${output.stderr}")
    }
    return output.stdoutLines.asSequence()
}

private fun classifyFlagsOutput(lines: Sequence<String>): DCEVMSupport =
    lines.firstOrNull { it.contains(DCEVM_JVM_OPTION_NAME) }?.let { line ->
        when {
            line.contains("true") -> DCEVMSupport.Auto
            line.contains("false") -> DCEVMSupport.RequiresArg
            else -> DCEVMSupport.None
        }
    } ?: DCEVMSupport.None
