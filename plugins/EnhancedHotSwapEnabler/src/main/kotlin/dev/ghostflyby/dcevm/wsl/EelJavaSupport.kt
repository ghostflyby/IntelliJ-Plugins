/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")
// EEL (Path.getEelDescriptor / LocalEelDescriptor / asEelPath / toEelApi / EelExecApi) and WSL
// path APIs (WslPath / WSLDistribution.patchCommandLine) are still Experimental / evolving in
// 2026.1. Two platform facts drive the explicit routing below:
//  - WslEelProvider.getEelDescriptor is gated by WslIjentAvailabilityService
//    .useIjentForWslNioFileSystem(), which is a per-product build constant in real IDEs, so WSL
//    UNC paths may resolve to LocalEelDescriptor;
//  - GeneralCommandLine's implicit EEL routing honors the same gate, so it cannot be relied upon
//    for WSL executables; and WSLDistribution.doPatchCommandLine resolves the distro shell path
//    through runBlockingCancellable, which requires a ProgressIndicator on the current thread.
// Therefore: EEL exec first (non-local descriptor), then explicit WSL distribution patching under
// an empty progress indicator, then a plain local process. Track platform API changes here.

package dev.ghostflyby.dcevm.wsl

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.Computable
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.asEelPath
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.toEelApi
import com.intellij.platform.eel.spawnProcess
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * Runs `<javaExecutable> -XX:+PrintFlagsFinal -version` inside the environment hosting the
 * executable and returns its stdout lines.
 *
 * Routing order:
 *  1. EEL exec for non-local descriptors (dev containers; WSL when EEL NIO is enabled);
 *  2. WSL distribution patching for WSL UNC executables (platform picks IJent or wsl.exe);
 *  3. plain local process otherwise.
 *
 * Blocking on background I/O; must not be called from the EDT. The caller caches the result
 * (see `getDcevmSupport`), so the first call per JDK pays the cost.
 */
internal fun javaOptionLines(javaExecutable: String): Sequence<String> {
    val path = Path.of(javaExecutable)

    val descriptor = path.getEelDescriptor()
    if (descriptor !== LocalEelDescriptor) {
        // Plain runBlocking: the flags check is short-lived. The empty progress indicator is
        // installed for nested runBlockingCancellable calls inside the EEL machinery.
        return withEmptyProgress {
            val process = runBlocking {
                descriptor.toEelApi()
                    .exec
                    .spawnProcess(path.asEelPath().toString())
                    .args("-XX:+PrintFlagsFinal", "-version")
                    .eelIt()
            }
            process.convertToJavaProcess()
                .inputStream
                .bufferedReader()
                .readLines()
                .asSequence()
        }
    }

    val wsl = WslPath.parseWindowsUncPath(javaExecutable)
    if (wsl != null) {
        // Force the wsl.exe launch: the IJent launch path requires a cancellable context
        return withEmptyProgress {
            val commandLine = wsl.distribution.patchCommandLine(
                GeneralCommandLine(wsl.linuxPath, "-XX:+PrintFlagsFinal", "-version"),
                null,
                WSLCommandLineOptions().setLaunchWithWslExe(true),
            )
            commandLine.createProcess()
                .inputStream
                .bufferedReader()
                .use { it.readLines().asSequence() }
        }
    }

    return GeneralCommandLine(javaExecutable, "-XX:+PrintFlagsFinal", "-version")
        .createProcess()
        .inputStream
        .bufferedReader()
        .use { it.readLines().asSequence() }
}

private fun <T> withEmptyProgress(block: () -> T): T =
    ProgressManager.getInstance().runProcess(Computable(block), EmptyProgressIndicator())
