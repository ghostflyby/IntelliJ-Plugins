/*
 * Copyright (c) 2025 ghostflyby <ghostflyby+intellij@outlook.com>
 *
 * This program is free software; you can redistribute it and/or
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

internal const val ENABLE_DCEVM_ENV_KEY = "ijEnableDcevm"
internal const val ENABLE_HOTSWAP_AGENT_ENV_KEY = "ijEnableHotswapAgent"
internal const val HOTSWAP_AGENT_JAR_PATH_ENV_KEY = "ijHotswapAgentJarPath"

internal const val DCEVM_JVM_OPTION_NAME = "AllowEnhancedClassRedefinition"
internal const val JVM_OPTION_DCEVM = "-XX:+AllowEnhancedClassRedefinition"
internal const val JVM_OPTION_DCEVM_ALT = "-XXaltjvm=dcevm"
internal const val JVM_OPTION_EXTERNAL_HOTSWAP_AGENT = "-XX:HotswapAgent=external"

internal sealed interface DCEVMSupport {

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

private val dcevmCheckCache = ConcurrentHashMap<Path, DCEVMSupport>()

internal fun getDcevmSupport(
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


private val l: List<String> = buildList {
    val locations = arrayOf("lib/dcevm", "bin/dcevm", "lib/i386/dcevm", "lib/amd64/dcevm")
    val prefix = arrayOf("", "jre/")
    locations.flatMap {
        prefix.map { p -> "$p$it" }
    }
}

private fun installedAsAltJvm(javaHome: Path): Boolean = l.stream().parallel().anyMatch {
    javaHome.resolve(it).isDirectory()
}
