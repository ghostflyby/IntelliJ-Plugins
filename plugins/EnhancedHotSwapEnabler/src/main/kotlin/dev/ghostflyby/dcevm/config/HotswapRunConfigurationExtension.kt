/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.dcevm.config

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.options.SettingsEditor
import com.intellij.util.xmlb.XmlSerializer
import dev.ghostflyby.dcevm.Bundle
import org.jdom.Element
import javax.swing.JComponent

// do not change qualified name to avoid breaking existing configurations
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
     * Qodana reports EmptyMethod, but super doesn't provide a default and
     * we don't need any operations.
     */
    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?,
    ) {
        return
    }

}

