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

import com.intellij.execution.Executor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.runners.JavaProgramPatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.io.path.Path

internal class DCEVMProgramPatcher(private val scope: CoroutineScope) : JavaProgramPatcher() {
    override fun patchJavaParameters(
        executor: Executor,
        configuration: RunProfile,
        javaParameters: JavaParameters,
    ) {
        if (javaParameters.vmParametersList.parameters.none { it.startsWith("-agentlib:jdwp") }) return
        val effective = effectiveHotSwapConfig(
            configuration,
            (configuration as? com.intellij.execution.configurations.RunConfigurationBase<*>)?.project
        )
        if (!effective.enable) return
        val javaHome = javaParameters.jdk?.homePath ?: return

        val support = getDcevmSupport(
            Path(javaHome),
            { scope.launch(Dispatchers.Default) { it.run() } }
        ) {
            GeneralCommandLine(
                it,
                "-XX:+PrintFlagsFinal",
                "--version"
            ).createProcess().inputStream.bufferedReader().use { reader ->
                reader.readLines().asSequence()
            }
        }

        if (support is DCEVMSupport.NeedsArgs) {
            javaParameters.vmParametersList.addAll(support.args)
        }
        if (effective.enableHotswapAgent) {
            if (javaParameters.vmParametersList.parameters.none { it == JVM_OPTION_EXTERNAL_HOTSWAP_AGENT }) {
                javaParameters.vmParametersList.add(JVM_OPTION_EXTERNAL_HOTSWAP_AGENT)
            }
        }

    }
}
