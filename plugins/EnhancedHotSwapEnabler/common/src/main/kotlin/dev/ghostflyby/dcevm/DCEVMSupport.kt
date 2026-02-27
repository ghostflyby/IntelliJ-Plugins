/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.dcevm

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ForkJoinPool
import kotlin.io.path.isDirectory

public const val DCEVM_MANUAL_TASKS_KEY = "ijDcevmManualTasks"
public const val ENABLE_DCEVM_ENV_KEY = "ijEnableDcevm"
public const val ENABLE_HOTSWAP_AGENT_ENV_KEY = "ijEnableHotswapAgent"
public const val HOTSWAP_AGENT_JAR_PATH_ENV_KEY = "ijHotswapAgentJarPath"

public const val DCEVM_JVM_OPTION_NAME = "AllowEnhancedClassRedefinition"
public const val JVM_OPTION_DCEVM = "-XX:+AllowEnhancedClassRedefinition"
public const val JVM_OPTION_DCEVM_ALT = "-XXaltjvm=dcevm"
public const val JVM_OPTION_EXTERNAL_HOTSWAP_AGENT = "-XX:HotswapAgent=external"
private const val JVM_OPTION_ADD_OPENS = "--add-opens"

private val HOTSWAP_AGENT_ADD_OPENS_TARGETS = listOf(
    "java.base/java.lang=ALL-UNNAMED",
    "java.base/jdk.internal.loader=ALL-UNNAMED",
    "java.base/java.io=ALL-UNNAMED",
    "java.desktop/java.beans=ALL-UNNAMED",
    "java.desktop/com.sun.beans=ALL-UNNAMED",
    "java.desktop/com.sun.beans.introspect=ALL-UNNAMED",
    "java.desktop/com.sun.beans.util=ALL-UNNAMED",
)

public sealed interface DCEVMSupport {

    interface NeedsArgs : DCEVMSupport {
        val args: List<String>
    }

    object None : DCEVMSupport
    object Auto : DCEVMSupport
    object RequiresArg : NeedsArgs {
        override val args = listOf(JVM_OPTION_DCEVM)
    }

    object AltJvm : NeedsArgs {
        override val args = listOf(JVM_OPTION_DCEVM_ALT)
    }
}

public fun missingHotswapAgentAddOpensJvmArgs(
    existingArgs: Collection<String>,
    isJava9OrHigher: Boolean,
): List<String> {
    if (!isJava9OrHigher) return emptyList()
    val args = existingArgs.toList()
    return HOTSWAP_AGENT_ADD_OPENS_TARGETS
        .filterNot { target -> hasAddOpensJvmArg(args, target) }
        .map { target -> "$JVM_OPTION_ADD_OPENS=$target" }
}

private fun hasAddOpensJvmArg(existingArgs: List<String>, target: String): Boolean {
    if ("$JVM_OPTION_ADD_OPENS=$target" in existingArgs) return true
    for (index in 0 until existingArgs.lastIndex) {
        if (existingArgs[index] == JVM_OPTION_ADD_OPENS && existingArgs[index + 1] == target) {
            return true
        }
    }
    return false
}

private val dcevmCheckCache = ConcurrentHashMap<Path, DCEVMSupport>()

public fun getDcevmSupport(
    javaHome: Path,
    execute: (Runnable) -> Unit = ForkJoinPool.commonPool()::execute,
    optionLinesProvider: (javaExecutable: String) -> Sequence<String>,
): DCEVMSupport {
    val result = dcevmCheckCache[javaHome]
    return if (result == null) {
        getDcevmSupport(javaHome, optionLinesProvider).also {
            dcevmCheckCache[javaHome] = it
        }
    } else {
        execute {
            dcevmCheckCache[javaHome] = getDcevmSupport(javaHome, optionLinesProvider)
        }
        result
    }
}

private fun getDcevmSupport(
    javaHome: Path,
    optionLinesProvider: (javaExecutable: String) -> Sequence<String>,
): DCEVMSupport = if (installedAsAltJvm(javaHome)) {
    DCEVMSupport.AltJvm
} else
    optionLinesProvider(javaHome.resolve("bin/java").toString()).firstOrNull {
        it.contains(DCEVM_JVM_OPTION_NAME)
    }?.run {
        when {
            contains("true") -> DCEVMSupport.Auto
            contains("false") -> DCEVMSupport.RequiresArg
            else -> DCEVMSupport.None
        }
    } ?: DCEVMSupport.None


private val l = run {
    val locations = arrayOf("lib/dcevm", "bin/dcevm", "lib/i386/dcevm", "lib/amd64/dcevm")
    val prefix = arrayOf("", "jre/")
    locations.flatMap {
        prefix.map { p -> "$p$it" }
    }
}

private fun installedAsAltJvm(javaHome: Path): Boolean = l.stream().parallel().anyMatch {
    javaHome.resolve(it).isDirectory()
}
