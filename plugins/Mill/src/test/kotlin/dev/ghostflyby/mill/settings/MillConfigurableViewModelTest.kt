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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
    fun `typing executable name draft uses discovered path as left hint`() {
        val projectPath = "/tmp/mill-project"
        val model = createModel(
            projectPath = projectPath,
            discovery = MillExecutableDiscovery(
                projectWrapper = null,
                pathExecutables = listOf(Path.of("/usr/local/bin/mill")),
            ),
        )

        model.executableInputTextProperty.set("mill")

        Assertions.assertEquals("/usr/local/bin/mill", model.executableInputLeftHintProperty.get())
    }

    @Test
    fun `typing unknown executable name draft does not resolve against project root`() {
        val projectPath = "/tmp/mill-project"
        val model = createModel(projectPath)

        model.executableInputTextProperty.set("custom-mill")

        Assertions.assertEquals("", model.executableInputLeftHintProperty.get())
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
    fun `selecting another executable choice refreshes version hint for that choice`() {
        val model = createModel(
            projectPath = "/tmp/mill-project",
            discovery = MillExecutableDiscovery(
                projectWrapper = null,
                pathExecutables = listOf(Path.of("/usr/local/bin/mill")),
            ),
            probe = { _, _, executablePath ->
                MillExecutableProbeResult(
                    resolvedExecutable = executablePath,
                    isValid = true,
                    version = if (executablePath.isBlank()) "project" else "path",
                    errorDetails = null,
                )
            },
        )

        waitUntil { model.executableVersionTextProperty.get() == "project" }

        val pathChoice = model.executableChoicesProperty.get()
            .single { it.inputMatchText == MillConstants.defaultExecutable }
        model.executableSelectedChoiceProperty.set(pathChoice)
        waitUntil { model.executableVersionTextProperty.get() == "path" }

        val projectChoice = model.executableChoicesProperty.get()
            .single { it.source == MillExecutableSource.PROJECT_DEFAULT_SCRIPT }
        model.executableSelectedChoiceProperty.set(projectChoice)
        waitUntil { model.executableVersionTextProperty.get() == "project" }
    }

    @Test
    fun `committed probe success updates version hint when draft matches selected choice`() {
        val model = createModel(
            projectPath = "/tmp/mill-project",
            probe = { _, source, _ ->
                MillExecutableProbeResult(
                    resolvedExecutable = source.name,
                    isValid = true,
                    version = "3.0.0",
                    errorDetails = null,
                )
            },
        )

        waitUntil { model.executableVersionTextProperty.get() == "3.0.0" }
        Assertions.assertEquals(
            model.executableSelectedChoiceProperty.get()?.editorText,
            model.executableInputTextProperty.get(),
        )
    }

    @Test
    fun `committed probe does not override active draft version hint`() {
        val committedProbeMayFinish = CountDownLatch(1)
        val committedProbeStarted = CountDownLatch(1)
        val model = createModel(
            projectPath = "/tmp/mill-project",
            probe = { _, _, executablePath ->
                if (executablePath.isBlank()) {
                    committedProbeStarted.countDown()
                    Assertions.assertTrue(committedProbeMayFinish.await(3, TimeUnit.SECONDS))
                    MillExecutableProbeResult(
                        resolvedExecutable = "project",
                        isValid = true,
                        version = "committed",
                        errorDetails = null,
                    )
                } else {
                    MillExecutableProbeResult(
                        resolvedExecutable = executablePath,
                        isValid = true,
                        version = "draft",
                        errorDetails = null,
                    )
                }
            },
        )

        Assertions.assertTrue(committedProbeStarted.await(3, TimeUnit.SECONDS))
        model.executableInputTextProperty.set("/usr/local/bin/mill")
        waitUntil { model.executableVersionTextProperty.get() == "draft" }

        committedProbeMayFinish.countDown()
        Thread.sleep(200)

        Assertions.assertEquals("draft", model.executableVersionTextProperty.get())
        val appliedProject = applyModel(model).single()
        Assertions.assertEquals(MillExecutableSource.PROJECT_DEFAULT_SCRIPT, appliedProject.millExecutableSource)
        Assertions.assertEquals("", appliedProject.millExecutablePath)
    }

    @Test
    fun `draft probe result does not commit settings state`() {
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

        model.executableInputTextProperty.set("/usr/local/bin/mill")
        waitUntil { model.executableVersionTextProperty.get() == "1.2.3" }

        val appliedProject = applyModel(model).single()
        Assertions.assertEquals(MillExecutableSource.PROJECT_DEFAULT_SCRIPT, appliedProject.millExecutableSource)
        Assertions.assertEquals("", appliedProject.millExecutablePath)
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

    @Test
    fun `editor draft text updates bound left hint`() {
        val model = createModel(
            projectPath = "/tmp/mill-project",
            discovery = MillExecutableDiscovery(
                projectWrapper = null,
                pathExecutables = listOf(Path.of("/usr/local/bin/mill")),
            ),
        )
        val panel = millConfigurableView(project, model)

        @Suppress("UNCHECKED_CAST")
        val selector = findComponent(panel) { it is EditableHintedComboBox<*> }
                as EditableHintedComboBox<MillExecutableChoice>

        selector.editorTextField.text = "mill"
        waitUntil { selector.leftHint == "/usr/local/bin/mill" }

        selector.editorTextField.text = "tools/mill"
        waitUntil { selector.leftHint == "/tmp/mill-project/tools/mill" }
    }

    @Test
    fun `editor draft text updates left hint after selecting inline manual choice`() {
        val model = createModel(projectPath = "/tmp/mill-project")
        val panel = millConfigurableView(project, model)

        @Suppress("UNCHECKED_CAST")
        val selector = findComponent(panel) { it is EditableHintedComboBox<*> }
                as EditableHintedComboBox<MillExecutableChoice>

        selector.selectedItem = MillExecutableChoice(
            key = "manual:tools/mill",
            displayName = "tools/mill",
            editorHintText = null,
            source = MillExecutableSource.MANUAL,
            manualPath = "tools/mill",
            tooltipText = "tools/mill",
        )
        waitUntil { selector.leftHint == "/tmp/mill-project/tools/mill" }

        selector.editorTextField.text = "other/mill"
        waitUntil { selector.leftHint == "/tmp/mill-project/other/mill" }
    }

    @Test
    fun `selector choice changes update bound right hint`() {
        val model = createModel(
            projectPath = "/tmp/mill-project",
            discovery = MillExecutableDiscovery(
                projectWrapper = null,
                pathExecutables = listOf(Path.of("/usr/local/bin/mill")),
            ),
            probe = { _, _, executablePath ->
                MillExecutableProbeResult(
                    resolvedExecutable = executablePath,
                    isValid = true,
                    version = if (executablePath.isBlank()) "project" else "path",
                    errorDetails = null,
                )
            },
        )
        val panel = millConfigurableView(project, model)

        @Suppress("UNCHECKED_CAST")
        val selector = findComponent(panel) { it is EditableHintedComboBox<*> }
                as EditableHintedComboBox<MillExecutableChoice>

        waitUntil { selector.rightHint == "project" }
        selector.selectedItem = model.executableChoicesProperty.get()
            .single { it.inputMatchText == MillConstants.defaultExecutable }
        waitUntil { selector.rightHint == "path" }
        selector.selectedItem = model.executableChoicesProperty.get()
            .single { it.source == MillExecutableSource.PROJECT_DEFAULT_SCRIPT }
        waitUntil { selector.rightHint == "project" }
    }

    @Test
    fun `selecting another project syncs draft text and hints to that project choice`() {
        val firstProjectPath = "/tmp/first-mill-project"
        val secondProjectPath = "/tmp/second-mill-project"
        val firstSettings = MillProjectSettings().also {
            it.externalProjectPath = firstProjectPath
        }
        val secondSettings = MillProjectSettings().also {
            it.externalProjectPath = secondProjectPath
            it.millExecutableSource = MillExecutableSource.MANUAL
            it.millExecutablePath = MillConstants.defaultExecutable
        }
        val model = MillConfigurableViewModel(
            linkedProjectSettings = listOf(firstSettings, secondSettings),
            parentDisposable = disposable,
            discoverExecutables = { projectPath ->
                when (projectPath.toString()) {
                    firstProjectPath -> MillExecutableDiscovery(
                        projectWrapper = Path.of(firstProjectPath, MillConstants.wrapperScriptName),
                        pathExecutables = listOf(Path.of("/usr/local/bin/mill-first")),
                    )

                    secondProjectPath -> MillExecutableDiscovery(
                        projectWrapper = null,
                        pathExecutables = listOf(Path.of("/opt/mill-second/bin/mill")),
                    )

                    else -> MillExecutableDiscovery(projectWrapper = null, pathExecutables = emptyList())
                }
            },
            probeExecutable = { projectPath, _, executablePath ->
                MillExecutableProbeResult(
                    resolvedExecutable = executablePath,
                    isValid = true,
                    version = if (projectPath.toString() == firstProjectPath) "first" else "second",
                    errorDetails = null,
                )
            },
            draftProbeDebounceMillis = 0,
        )

        waitUntil { model.executableVersionTextProperty.get() == "first" }

        model.selectedProjectPathProperty.set(secondProjectPath)

        waitUntil { model.executableVersionTextProperty.get() == "second" }
        Assertions.assertEquals(
            model.executableSelectedChoiceProperty.get()?.editorText,
            model.executableInputTextProperty.get(),
        )
        Assertions.assertEquals("/opt/mill-second/bin/mill", model.executableInputLeftHintProperty.get())
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
