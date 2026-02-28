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

import groovy.json.JsonSlurper
import org.gradle.api.Plugin
import org.gradle.api.invocation.Gradle
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.withType


public class IntelliJDcevmGradlePlugin : Plugin<Gradle> {
    override fun apply(target: Gradle): Unit = target.allprojects {
        val manualTasks = providers.environmentVariable(DCEVM_MANUAL_TASKS_KEY)
            .map { JsonSlurper().parse(it.toCharArray()) as Collection<*> }

        val enableDcevm = providers.environmentVariable(ENABLE_DCEVM_ENV_KEY).orElse("false").map { it.toBoolean() }
        val enableHotswapAgent =
            providers.environmentVariable(ENABLE_HOTSWAP_AGENT_ENV_KEY).orElse("false").map { it.toBoolean() }
        // see org.jetbrains.plugins.gradle.service.task.GradleTaskManagerExtensionDebuggerBridge.Companion
        // use environment variable to avoid doFirst ordering issues
        val debuggerEnabled = providers.environmentVariable("DEBUGGER_ENABLED").orElse("false").map { it.toBoolean() }
        val hotswapAgentJarPath = providers.environmentVariable(HOTSWAP_AGENT_JAR_PATH_ENV_KEY).orElse("[]")
        tasks.withType<JavaExec>().configureEach {


            doFirst {
                val taskNames = manualTasks.get()
                if (path !in taskNames && name !in taskNames) return@doFirst

                val dcevmSupportProvider = javaLauncher.map { launcher ->
                    val javaHome = launcher.metadata.installationPath.asFile.toPath()
                    getDcevmSupport(javaHome) { exe ->
                        ProcessBuilder().command(
                            exe, "-XX:+PrintFlagsFinal", "-version",
                        ).start().inputStream.bufferedReader().use { it.readLines().asSequence() }
                    }
                }


                val support = dcevmSupportProvider.get()

                if (!debuggerEnabled.get()
                    || !enableDcevm.get()
                    || support is DCEVMSupport.None
                ) return@doFirst

                if (support is DCEVMSupport.NeedsArgs)
                    jvmArgs(support.args)

                if (enableHotswapAgent.get()) {
                    // Always add external
                    val jar = hotswapAgentJarPath.get()
                    if (jar.isNotBlank()) {
                        jvmArgs(
                            missingHotswapAgentAddOpensJvmArgs(
                                allJvmArgs,
                                javaLauncher.get().metadata.languageVersion.canCompileOrRun(9),
                            ),
                        )
                        if (support !is DCEVMSupport.NeedsArgs) {
                            jvmArgs(JVM_OPTION_EXTERNAL_HOTSWAP_AGENT)
                        }
                        jvmArgs("-javaagent:$jar")
                        logger.lifecycle("HotswapAgent enabled for task $name")
                    }
                }
            }
        }
    }
}
