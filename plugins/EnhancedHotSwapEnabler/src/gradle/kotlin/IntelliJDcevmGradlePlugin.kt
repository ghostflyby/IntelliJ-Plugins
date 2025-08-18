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

import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.withType


internal class IntelliJDcevmGradlePlugin : Plugin<Gradle> {
    override fun apply(target: Gradle) = target.allprojects {
        val enableDcevm = providers.environmentVariable(ENABLE_DCEVM_ENV_KEY).map { it.toBoolean() }
        val enableHotswapAgent = providers.environmentVariable(ENABLE_HOTSWAP_AGENT_ENV_KEY).map { it.toBoolean() }
        // see org.jetbrains.plugins.gradle.service.task.GradleTaskManagerExtensionDebuggerBridge.Companion
        // use environment variable to avoid doFirst ordering issues
        val debuggerEnabled = providers.environmentVariable("DEBUGGER_ENABLED").map { it.toBoolean() }
        tasks.withType<JavaExec>().configureEach {

            val dcevmSupportProvider = javaLauncher.map { launcher ->
                val javaHome = launcher.metadata.installationPath.asFile.toPath()
                getDcevmSupport(javaHome) { exe ->
                    providers.exec {
                        commandLine(exe, "-XX:+PrintFlagsFinal", "--version")
                    }.standardOutput.asText.get().splitToSequence("\n")
                }
            }


            doFirst {
                if (!debuggerEnabled.get() || !enableDcevm.get()) return@doFirst
                val support = dcevmSupportProvider.get()
                if (support is DCEVMSupport.NeedsArgs)
                    jvmArgs(support.args)
                logger.lifecycle("DCEVM enabled for task $name")
                if (enableHotswapAgent.get()) {
                    jvmArgs(JVM_OPTION_EXTERNAL_HOTSWAP_AGENT)
                    logger.lifecycle("HotswapAgent enabled for task $name")
                }
            }
        }
    }
}