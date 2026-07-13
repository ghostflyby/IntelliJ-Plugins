/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless.gradle

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.ghostflyby.spotless.Bundle

internal class SpotlessGradleConfigurable(private val project: Project) :
    BoundConfigurable(Bundle.message("spotless.configuration.title.workspace")) {

    private val model = SettingsModel()

    override fun createPanel(): DialogPanel {
        resetModel()
        return panel {
            row(Bundle.message("spotless.configuration.daemonVersion.label")) {
                textField()
                    .bindText(model::gradleDaemonVersion)
                    .resizableColumn()
                    .align(Align.FILL)

            }.rowComment(Bundle.message("spotless.configuration.daemonVersion.comment"))
            row(Bundle.message("spotless.configuration.daemonJar.label")) {
                @Suppress("UnstableApiUsage")
                textFieldWithBrowseButton(
                    project = project,
                    fileChooserDescriptor = FileChooserDescriptorFactory.singleFile()
                        .withTitle(Bundle.message("spotless.configuration.daemonJar.title")),
                )
                    .bindText(model::gradleDaemonJar)
                    .resizableColumn()
                    .align(Align.FILL)

            }.rowComment(Bundle.message("spotless.configuration.daemonJar.comment"))
        }
    }

    override fun apply() {
        super.apply()
        val settings = project.service<SpotlessGradleSettings>()
        settings.gradleDaemonVersion = model.gradleDaemonVersion
        settings.gradleDaemonJar = model.gradleDaemonJar
    }

    override fun reset() {
        resetModel()
        super.reset()
    }

    private fun resetModel() {
        val settings = project.service<SpotlessGradleSettings>()
        model.gradleDaemonVersion = settings.gradleDaemonVersion
        model.gradleDaemonJar = settings.gradleDaemonJar
    }

    private data class SettingsModel(
        var gradleDaemonVersion: String = "",
        var gradleDaemonJar: String = "",
    )
}
