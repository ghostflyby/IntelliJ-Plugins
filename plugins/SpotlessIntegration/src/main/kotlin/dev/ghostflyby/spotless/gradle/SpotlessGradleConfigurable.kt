/*
 * Copyright (c) 2025 ghostflyby
 * SPDX-FileCopyrightText: 2025 ghostflyby
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

package dev.ghostflyby.spotless.gradle

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.ghostflyby.spotless.Bundle

internal class SpotlessGradleConfigurable(private val project: Project) :
    BoundConfigurable(Bundle.message("spotless.configuration.title.workspace")) {

    private val settings = project.service<SpotlessGradlePersistent>()

    override fun createPanel() = panel {
        row(Bundle.message("spotless.configuration.daemonVersion.label")) {
            textField()
                .bindText(settings::gradleDaemonVersion)
                .comment(Bundle.message("spotless.configuration.daemonVersion.comment"))
        }
        row(Bundle.message("spotless.configuration.daemonJar.label")) {
            @Suppress("UnstableApiUsage")
            textFieldWithBrowseButton(
                project = project,
                fileChooserDescriptor = FileChooserDescriptorFactory.singleFile()
                    .withTitle(Bundle.message("spotless.configuration.daemonJar.title")),
            )
                .bindText(settings::gradleDaemonJar)
                .comment(Bundle.message("spotless.configuration.daemonJar.comment"))
        }
    }
}
