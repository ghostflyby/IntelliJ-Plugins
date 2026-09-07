/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.dcevm.config

import com.intellij.execution.Executor
import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemRunConfiguration
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.util.UserDataHolder
import com.intellij.util.xmlb.XmlSerializer
import dev.ghostflyby.dcevm.Bundle
import dev.ghostflyby.dcevm.DCEVMSupport
import dev.ghostflyby.dcevm.JVM_OPTION_EXTERNAL_HOTSWAP_AGENT
import dev.ghostflyby.dcevm.agent.BundledHotSwapAgentJarPath
import dev.ghostflyby.dcevm.agent.JDWP_AGENTLIB_OPTION
import dev.ghostflyby.dcevm.agent.fixedJvmParameter
import dev.ghostflyby.dcevm.agent.hotswapAgentParameter
import dev.ghostflyby.dcevm.getDcevmSupport
import dev.ghostflyby.dcevm.missingHotswapAgentAddOpensJvmArgs
import dev.ghostflyby.dcevm.wsl.javaOptionLines
import org.jdom.Element
import java.nio.file.Path
import javax.swing.JComponent

// do not change qualified name to avoid breaking existing configurations
@Suppress("UnstableApiUsage")
// JavaTargetDependentParameters / JavaTargetParameter / TargetPaths are Experimental APIs in
// 2026.1: the target-agnostic VM parameter injection surface. Track platform stabilization here.
internal class HotswapRunConfigurationExtension : RunConfigurationExtension() {

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean =
        true

    override fun readExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        val child = element.getChild(configKey) ?: return
        val deserialized = try {
            XmlSerializer.deserialize(child, HotswapConfigState::class.java)
        } catch (_: Throwable) {
            null
        }
        runConfiguration.hotswapState = deserialized
    }

    override fun writeExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        val state = runConfiguration.hotswapState ?: return
        val child = element.getOrCreateChild(configKey)
        XmlSerializer.serializeInto(state, child)
    }

    override fun cleanUserData(runConfigurationBase: RunConfigurationBase<*>) {
        runConfigurationBase.hotswapState = null
    }

    override fun <P : RunConfigurationBase<*>> createEditor(configuration: P): SettingsEditor<P?> {
        return object : SettingsEditor<P?>() {
            private var model = HotswapConfigViewModel()
            private val ui = hotswapConfigView(model)

            override fun resetEditorFrom(s: P) {
                val st = s.hotswapState
                if (st != null) model.setFrom(st)
            }

            override fun applyEditorTo(s: P) {
                val newState = HotswapConfigState().setFrom(model)
                s.hotswapState = newState
            }

            override fun createEditor(): JComponent = ui

        }
    }

    override fun getEditorTitle(): String = Bundle.message("configuration.section.name")

    /**
     * Injects through the 4-arg overload so patching happens before the command line is built —
     * also for WSL/container target runs, where [com.intellij.execution.runners.JavaProgramPatcher]
     * runs after the TargetedCommandLine and its changes would be dropped.
     * Gradle run configurations are handled by the init-script path in
     * [dev.ghostflyby.dcevm.DCEVMGradleManagerExtension] and are skipped here.
     */
    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?,
        executor: Executor,
    ) {
        patchHotswapParameters(configuration, params, executor.id)
    }

    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        patchHotswapParameters(configuration, params, executorId = null)
    }

    private fun patchHotswapParameters(
        configuration: RunConfigurationBase<*>,
        params: JavaParameters,
        executorId: String?,
    ) {
        if (configuration is ExternalSystemRunConfiguration) return

        val effective = effectiveHotSwapConfig(
            configuration as? UserDataHolder,
            configuration.project,
        )
        if (!effective.enable) return

        val isDebugRun = executorId == DefaultDebugExecutor.EXECUTOR_ID ||
            params.vmParametersList.parameters.any { it.startsWith(JDWP_AGENTLIB_OPTION) }
        if (!isDebugRun) return

        val jdk = params.jdk ?: return
        val javaHome = jdk.homePath ?: return

        val support = getDcevmSupport(
            Path.of(javaHome),
            optionLinesProvider = ::javaOptionLines,
        )

        val targetParameters = params.targetDependentParameters
        if (support is DCEVMSupport.NeedsArgs) {
            for (arg in support.args) {
                targetParameters.addParameter { fixedJvmParameter(arg) }
            }
        }
        if (!effective.enableHotswapAgent) return

        val isJava9OrHigher = JavaSdk.getInstance()
            .getVersion(jdk)
            ?.isAtLeast(JavaSdkVersion.JDK_1_9) == true
        for (arg in missingHotswapAgentAddOpensJvmArgs(
            params.vmParametersList.parameters,
            isJava9OrHigher,
        )) {
            targetParameters.addParameter { fixedJvmParameter(arg) }
        }
        if (support !is DCEVMSupport.NeedsArgs) {
            targetParameters.addParameter { fixedJvmParameter(JVM_OPTION_EXTERNAL_HOTSWAP_AGENT) }
        }
        targetParameters.addParameter {
            hotswapAgentParameter(BundledHotSwapAgentJarPath.toAbsolutePath().toString())
        }
    }

}
