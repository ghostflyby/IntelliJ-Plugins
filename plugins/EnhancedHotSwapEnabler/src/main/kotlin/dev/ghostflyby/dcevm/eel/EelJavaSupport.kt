/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")
// EEL exec (com.intellij.platform.eel, @ApiStatus.Experimental) is the single process surface
// here: one code path serves local, WSL and container JDKs through their EelApi. The EEL fs /
// MultiRoutingFileSystem layers are deliberately NOT used — fs is @ApiStatus.Internal and the
// routing provider is product-gated in 2026.1. WslPath is referenced only for diagnostics.

package dev.ghostflyby.dcevm.eel

import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.diagnostic.Logger
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.asEelPath
import com.intellij.platform.eel.provider.getEelDescriptor
import com.intellij.platform.eel.provider.toEelApi
import com.intellij.platform.eel.spawnProcess
import dev.ghostflyby.dcevm.DCEVM_JVM_OPTION_NAME
import dev.ghostflyby.dcevm.DCEVMSupport
import dev.ghostflyby.dcevm.isDcevmInstalledAsAltJvm
import java.nio.file.Path

private val logger = Logger.getInstance("dev.ghostflyby.dcevm.eel")

/**
 * Detects the DCEVM support level of the JDK at [javaHome] by running
 * `<javaHome>/bin/java -XX:+PrintFlagsFinal -version` inside the environment hosting the JDK —
 * local machine, WSL distribution or dev container — through a single EEL exec code path.
 *
 * Suspend by design: callers must invoke it from a coroutine and never block the dispatching
 * thread (no `runBlocking`, no progress-indicator tricks — EEL exec suspends cleanly).
 * The EEL exec surface is `@ApiStatus.Experimental` in 2026.1.
 */
internal suspend fun detectDcevmSupport(javaHome: Path): DCEVMSupport {
    if (isDcevmInstalledAsAltJvm(javaHome)) {
        return DCEVMSupport.AltJvm
    }

    val javaExecutable = javaHome.resolve("bin/java")
    val descriptor = javaExecutable.getEelDescriptor()
    if (descriptor === LocalEelDescriptor && WslPath.parseWindowsUncPath(javaExecutable.toString()) != null) {
        // The path is a WSL UNC but the EEL WSL layer is product-gated off here: executing the
        // Linux ELF locally would fail with CreateProcess error=193. Degrade instead.
        logger.warn("EEL routing is unavailable for $javaExecutable; skipping DCEVM detection")
        return DCEVMSupport.None
    }

    val eelApi = descriptor.toEelApi()
    val exe = if (descriptor === LocalEelDescriptor) {
        javaExecutable.toString()
    } else {
        javaExecutable.asEelPath().toString()
    }
    val process = eelApi.exec
        .spawnProcess(exe)
        .args("-XX:+PrintFlagsFinal", "-version")
        .eelIt()
    val lines = process.convertToJavaProcess()
        .inputStream
        .bufferedReader()
        .readLines()
        .asSequence()
    return lines.firstOrNull { it.contains(DCEVM_JVM_OPTION_NAME) }?.let { line ->
        when {
            line.contains("true") -> DCEVMSupport.Auto
            line.contains("false") -> DCEVMSupport.RequiresArg
            else -> DCEVMSupport.None
        }
    } ?: DCEVMSupport.None
}
