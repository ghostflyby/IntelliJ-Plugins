/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")
// This file is the EEL/WSL execution transport for common's core detection
// (dev.ghostflyby.dcevm.getDcevmSupport): EEL exec (Experimental) for local, WSL and container
// JDKs, with WSLDistribution.executeOnWsl as the fallback for the product-gated WSL layer. The
// EEL fs / MultiRoutingFileSystem layers are deliberately NOT used — fs is @ApiStatus.Internal.
// WslPath is referenced for the gated-layer detection only. Track platform API changes here.

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
import dev.ghostflyby.dcevm.DCEVMSupport
import dev.ghostflyby.dcevm.getDcevmSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

private val logger = Logger.getInstance("dev.ghostflyby.dcevm.eel")

private const val PRINT_FLAGS_TIMEOUT_MS = 60_000

/**
 * Detects the DCEVM support level of the JDK at [javaHome] by running
 * `<javaHome>/bin/java -XX:+PrintFlagsFinal -version` inside the environment hosting the JDK —
 * local machine, WSL distribution or dev container. Delegates the detection core to common's
 * suspend [getDcevmSupport]; this file only provides the execution transport.
 *
 * Suspend by design: callers must invoke it from a coroutine and never block the dispatching
 * thread. The WSL fallback blocks an IO dispatcher thread through the platform's synchronous
 * WSL execution API.
 */
internal suspend fun detectDcevmSupport(javaHome: Path): DCEVMSupport =
    getDcevmSupport(javaHome, optionLines = { javaExecutable -> optionLines(javaExecutable) })

private suspend fun optionLines(javaExecutable: String): Sequence<String> {
    val executablePath = Path.of(javaExecutable)
    val descriptor = executablePath.getEelDescriptor()
    return when {
        descriptor !== LocalEelDescriptor -> execViaEel(descriptor, executablePath.asEelPath().toString())
        else -> {
            val wsl = WslPath.parseWindowsUncPath(javaExecutable)
            if (wsl != null) {
                // The EEL WSL layer is product-gated and unavailable here — fall back to the
                // platform's WSL execution instead of CreateProcess-ing the Linux ELF locally.
                withContext(Dispatchers.IO) {
                    execViaWslDistribution(wsl.distribution, wsl.linuxPath)
                }
            } else {
                execViaEel(LocalEelDescriptor, javaExecutable)
            }
        }
    }
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
