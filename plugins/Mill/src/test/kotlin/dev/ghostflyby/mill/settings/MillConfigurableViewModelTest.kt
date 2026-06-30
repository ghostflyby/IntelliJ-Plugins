/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.settings

import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.disposableFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.ghostflyby.intellij.ui.EditableHintedComboBox
import dev.ghostflyby.mill.MillConstants
import dev.ghostflyby.mill.command.MillExecutableDiscovery
import dev.ghostflyby.mill.command.MillExecutableProbeResult
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.file.Path
import javax.swing.SwingUtilities

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

    @Test
    fun `selected choice initializes executable draft text and left hint`() {
        val projectPath = "/tmp/mill-project"
        val model = createModel(projectPath)
        val projectChoice = model.executableChoicesProperty.get()
            .single { it.source == MillExecutableSource.PROJECT_DEFAULT_SCRIPT }

        Assertions.assertEquals(projectChoice.editorText, model.executableInputTextProperty.get())
        Assertions.assertEquals(projectChoice.editorHintText, model.executableInputLeftHintProperty.get())
    }

    @Test
    fun `typing path choice draft updates left hint without committing settings`() {
        val projectPath = "/tmp/mill-project"
        val model = createModel(
            projectPath = projectPath,
            discovery = MillExecutableDiscovery(
                projectWrapper = null,
                pathExecutables = listOf(Path.of("/usr/local/bin/mill")),
            ),
        )

        model.executableInputTextProperty.set(MillConstants.defaultExecutable)

        Assertions.assertEquals("/usr/local/bin/mill", model.executableInputLeftHintProperty.get())
        val appliedProject = applyModel(model).single()
        Assertions.assertEquals(MillExecutableSource.PROJECT_DEFAULT_SCRIPT, appliedProject.millExecutableSource)
        Assertions.assertEquals("", appliedProject.millExecutablePath)
    }

    @Test
    fun `typing manual path draft updates left hint without committing settings`() {
        val projectPath = "/tmp/mill-project"
        val model = createModel(projectPath)

        model.executableInputTextProperty.set("tools/mill")

        Assertions.assertEquals("/tmp/mill-project/tools/mill", model.executableInputLeftHintProperty.get())
        val appliedProject = applyModel(model).single()
        Assertions.assertEquals(MillExecutableSource.PROJECT_DEFAULT_SCRIPT, appliedProject.millExecutableSource)
        Assertions.assertEquals("", appliedProject.millExecutablePath)
    }

    @Test
    fun `draft probe success updates version hint without committing settings`() {
        val projectPath = "/tmp/mill-project"
        val model = createModel(
            projectPath = projectPath,
            probe = { _, _, executablePath ->
                MillExecutableProbeResult(
                    resolvedExecutable = executablePath,
                    isValid = true,
                    version = "1.2.3",
                    errorDetails = null,
                )
            },
        )

        model.executableInputTextProperty.set("/usr/local/bin/mill")
        waitUntil { model.executableVersionTextProperty.get() == "1.2.3" }

        val appliedProject = applyModel(model).single()
        Assertions.assertEquals(MillExecutableSource.PROJECT_DEFAULT_SCRIPT, appliedProject.millExecutableSource)
        Assertions.assertEquals("", appliedProject.millExecutablePath)
    }

    @Test
    fun `draft probe failure updates version hint to bang`() {
        val model = createModel(
            projectPath = "/tmp/mill-project",
            probe = { _, _, executablePath ->
                MillExecutableProbeResult(
                    resolvedExecutable = executablePath,
                    isValid = false,
                    version = null,
                    errorDetails = "boom",
                )
            },
        )

        model.executableInputTextProperty.set("/usr/local/bin/mill")
        waitUntil { model.executableVersionTextProperty.get() == "!" }
    }

    @Test
    fun `stale draft probe result does not override newer draft version hint`() {
        val model = createModel(
            projectPath = "/tmp/mill-project",
            probe = { _, _, executablePath ->
                if (executablePath.endsWith("first")) {
                    Thread.sleep(150)
                    MillExecutableProbeResult(
                        resolvedExecutable = executablePath,
                        isValid = true,
                        version = "1.0.0",
                        errorDetails = null,
                    )
                } else {
                    MillExecutableProbeResult(
                        resolvedExecutable = executablePath,
                        isValid = true,
                        version = "2.0.0",
                        errorDetails = null,
                    )
                }
            },
        )

        model.executableInputTextProperty.set("/tmp/first")
        model.executableInputTextProperty.set("/tmp/second")
        waitUntil { model.executableVersionTextProperty.get() == "2.0.0" }
        Thread.sleep(200)

        Assertions.assertEquals("2.0.0", model.executableVersionTextProperty.get())
    }

    @Test
    fun `editor draft text updates bound right hint without committing settings`() {
        val model = createModel(
            projectPath = "/tmp/mill-project",
            probe = { _, _, executablePath ->
                MillExecutableProbeResult(
                    resolvedExecutable = executablePath,
                    isValid = true,
                    version = "1.2.3",
                    errorDetails = null,
                )
            },
        )
        val panel = millConfigurableView(project, model)

        @Suppress("UNCHECKED_CAST")
        val selector = findComponent(panel) { it is EditableHintedComboBox<*> }
                as EditableHintedComboBox<MillExecutableChoice>

        selector.editorTextField.text = "/usr/local/bin/mill"
        waitUntil { selector.rightHint == "1.2.3" }

        val appliedProject = applyModel(model).single()
        Assertions.assertEquals(MillExecutableSource.PROJECT_DEFAULT_SCRIPT, appliedProject.millExecutableSource)
        Assertions.assertEquals("", appliedProject.millExecutablePath)
    }

    private fun createModel(
        projectPath: String,
        discovery: MillExecutableDiscovery = MillExecutableDiscovery(
            projectWrapper = null,
            pathExecutables = emptyList(),
        ),
        probe: (Path, MillExecutableSource, String) -> MillExecutableProbeResult = { _, _, executablePath ->
            MillExecutableProbeResult(
                resolvedExecutable = executablePath,
                isValid = true,
                version = "1.0.0",
                errorDetails = null,
            )
        },
    ): MillConfigurableViewModel {
        val settings = MillProjectSettings().also {
            it.externalProjectPath = projectPath
        }
        return MillConfigurableViewModel(
            linkedProjectSettings = listOf(settings),
            parentDisposable = disposable,
            discoverExecutables = { discovery },
            probeExecutable = probe,
            draftProbeDebounceMillis = 0,
        )
    }

    private fun applyModel(model: MillConfigurableViewModel): Set<MillProjectSettings> {
        val settings = MillSettings(project)
        model.applyTo(settings)
        return settings.linkedProjectsSettings.toSet()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            var satisfied = false
            SwingUtilities.invokeAndWait {
                satisfied = condition()
            }
            if (satisfied) {
                return
            }
            Thread.sleep(25)
        }
        Assertions.fail<Unit>("Condition was not satisfied before timeout")
    }

    private fun <T> findComponent(
        component: java.awt.Component,
        predicate: (java.awt.Component) -> Boolean,
    ): T {
        if (predicate(component)) {
            @Suppress("UNCHECKED_CAST")
            return component as T
        }
        if (component is java.awt.Container) {
            for (child in component.components) {
                runCatching {
                    return findComponent(child, predicate)
                }
            }
        }
        error("Component not found")
    }
}
