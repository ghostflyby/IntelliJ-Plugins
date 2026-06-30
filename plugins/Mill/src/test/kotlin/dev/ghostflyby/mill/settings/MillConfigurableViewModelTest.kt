/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.settings

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.disposableFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.ghostflyby.mill.MillConstants
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

@TestApplication
internal class MillConfigurableViewModelTest {
    private val projectFixture = projectFixture(openAfterCreation = true)
    private val project by projectFixture
    private val disposable by disposableFixture()

    @Test
    fun `selecting project executable choice stores project default source`() {
        val projectPath = "/tmp/mill-project"
        val model = createModel(projectPath)

        val projectChoice = model.executableChoicesProperty.get()
            .single { it.source == MillExecutableSource.PROJECT_DEFAULT_SCRIPT }
        model.executableSelectedChoiceProperty.set(projectChoice)

        val appliedSettings = applyModel(model)
        val appliedProject = appliedSettings.single()
        Assertions.assertEquals(MillExecutableSource.PROJECT_DEFAULT_SCRIPT, appliedProject.millExecutableSource)
        Assertions.assertEquals("", appliedProject.millExecutablePath)
    }

    @Test
    fun `selecting path executable choice stores default manual command`() {
        val projectPath = "/tmp/mill-project"
        val model = createModel(projectPath)

        val pathChoice = model.executableChoicesProperty.get()
            .single { it.inputMatchText == MillConstants.defaultExecutable }
        model.executableSelectedChoiceProperty.set(pathChoice)

        val appliedSettings = applyModel(model)
        val appliedProject = appliedSettings.single()
        Assertions.assertEquals(MillExecutableSource.MANUAL, appliedProject.millExecutableSource)
        Assertions.assertEquals(MillConstants.defaultExecutable, appliedProject.millExecutablePath)
    }

    @Test
    fun `selecting manual executable choice stores explicit manual path`() {
        val projectPath = "/tmp/mill-project"
        val model = createModel(projectPath)
        val manualChoice = MillExecutableChoice(
            key = "manual:/usr/local/bin/mill",
            displayName = "mill",
            editorHintText = "/usr/local/bin/mill",
            source = MillExecutableSource.MANUAL,
            manualPath = "/usr/local/bin/mill",
            tooltipText = "/usr/local/bin/mill",
        )

        model.executableSelectedChoiceProperty.set(manualChoice)

        val appliedSettings = applyModel(model)
        val appliedProject = appliedSettings.single()
        Assertions.assertEquals(MillExecutableSource.MANUAL, appliedProject.millExecutableSource)
        Assertions.assertEquals("/usr/local/bin/mill", appliedProject.millExecutablePath)
    }

    private fun createModel(projectPath: String): MillConfigurableViewModel {
        val settings = MillProjectSettings().also {
            it.externalProjectPath = projectPath
        }
        return MillConfigurableViewModel(listOf(settings), disposable)
    }

    private fun applyModel(model: MillConfigurableViewModel): Set<MillProjectSettings> {
        val settings = MillSettings(project)
        model.applyTo(settings)
        return settings.linkedProjectsSettings.toSet()
    }
}
