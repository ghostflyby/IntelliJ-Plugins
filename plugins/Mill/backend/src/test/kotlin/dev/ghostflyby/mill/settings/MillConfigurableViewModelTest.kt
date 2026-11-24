/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.settings

import com.intellij.openapi.application.UI
import com.intellij.openapi.observable.properties.ObservableProperty
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.disposableFixture
import com.intellij.testFramework.junit5.fixture.projectFixture
import dev.ghostflyby.intellij.ui.EditableHintedComboBox
import dev.ghostflyby.mill.MillConstants
import dev.ghostflyby.mill.command.MillExecutableDiscovery
import dev.ghostflyby.mill.command.MillExecutableProbeResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Test methods are suspend functions when they await completion notifications; the waits use
// [nextValue] or an interruptible latch wait instead of polling. Swing work hops to the UI thread
// via [onUi]. The waiting itself never happens on that thread: property change listeners (and
// with them the view's bindings) are delivered there.
@TestApplication
@Timeout(10)
internal class MillConfigurableViewModelTest {
    private val projectFixture = projectFixture(openAfterCreation = true)
    private val project by projectFixture
    private val disposable by disposableFixture()

    // The models are disposed right after the test body, before the fixture teardown: their
    // scopes then stop probing immediately instead of leaving coroutines in flight while the
    // project is being closed.
    private val modelParent = Disposer.newDisposable("mill-configurable-view-model")

    @org.junit.jupiter.api.AfterEach
    fun disposeModels() {
        Disposer.dispose(modelParent)
    }

    @Test
    fun `selecting project executable choice stores project default source`() {
        val model = createModel()

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
        val model = createModel()

        val pathChoice = model.executableChoicesProperty.get()
            .single { it.inputMatchText == MillConstants.DEFAULT_EXECUTABLE }
        model.executableSelectedChoiceProperty.set(pathChoice)

        val appliedSettings = applyModel(model)
        val appliedProject = appliedSettings.single()
        Assertions.assertEquals(MillExecutableSource.MANUAL, appliedProject.millExecutableSource)
        Assertions.assertEquals(MillConstants.DEFAULT_EXECUTABLE, appliedProject.millExecutablePath)
    }

    @Test
    fun `selecting manual executable choice stores explicit manual path`() {
        val model = createModel()
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
        val model = createModel()
        val projectChoice = model.executableChoicesProperty.get()
            .single { it.source == MillExecutableSource.PROJECT_DEFAULT_SCRIPT }

        Assertions.assertEquals(projectChoice.editorText, model.executableInputTextProperty.get())
        Assertions.assertEquals(projectChoice.editorHintText, model.executableInputLeftHintProperty.get())
    }

    @Test
    suspend fun `typing path choice draft updates left hint without committing settings`() {
        val model = createModel(
            discovery = MillExecutableDiscovery(
                projectWrapper = null,
                pathExecutables = listOf(Path.of("/usr/local/bin/mill")),
            ),
        )
        val leftHint = model.executableInputLeftHintProperty.nextValue("/usr/local/bin/mill")

        model.executableInputTextProperty.set(MillConstants.DEFAULT_EXECUTABLE)

        leftHint.await()
        val appliedProject = applyModel(model).single()
        Assertions.assertEquals(MillExecutableSource.PROJECT_DEFAULT_SCRIPT, appliedProject.millExecutableSource)
        Assertions.assertEquals("", appliedProject.millExecutablePath)
    }

    @Test
    suspend fun `typing executable name draft uses discovered path as left hint`() {
        val model = createModel(
            discovery = MillExecutableDiscovery(
                projectWrapper = null,
                pathExecutables = listOf(Path.of("/usr/local/bin/mill")),
            ),
        )
        val leftHint = model.executableInputLeftHintProperty.nextValue("/usr/local/bin/mill")

        model.executableInputTextProperty.set("mill")

        leftHint.await()
    }

    @Test
    suspend fun `typing unknown executable name draft does not resolve against project root`() {
        val model = createModel()
        val leftHint = model.executableInputLeftHintProperty.nextValue("")

        model.executableInputTextProperty.set("custom-mill")

        leftHint.await()
    }

    @Test
    suspend fun `typing manual path draft updates left hint without committing settings`() {
        val model = createModel()
        val leftHint = model.executableInputLeftHintProperty.nextValue("/tmp/mill-project/tools/mill")

        model.executableInputTextProperty.set("tools/mill")

        leftHint.await()
        val appliedProject = applyModel(model).single()
        Assertions.assertEquals(MillExecutableSource.PROJECT_DEFAULT_SCRIPT, appliedProject.millExecutableSource)
        Assertions.assertEquals("", appliedProject.millExecutablePath)
    }

    @Test
    suspend fun `draft probe success updates version hint without committing settings`() {
        val model = createModel(
            probe = { _, _, executablePath ->
                MillExecutableProbeResult(
                    resolvedExecutable = executablePath,
                    isValid = true,
                    version = "1.2.3",
                    errorDetails = null,
                )
            },
        )

        val version = model.executableVersionTextProperty.nextValue("1.2.3")

        model.executableInputTextProperty.set("/usr/local/bin/mill")
        version.await()

        val appliedProject = applyModel(model).single()
        Assertions.assertEquals(MillExecutableSource.PROJECT_DEFAULT_SCRIPT, appliedProject.millExecutableSource)
        Assertions.assertEquals("", appliedProject.millExecutablePath)
    }

    @Test
    suspend fun `draft probe failure updates version hint to bang`() {
        val model = createModel(
            probe = { _, _, executablePath ->
                MillExecutableProbeResult(
                    resolvedExecutable = executablePath,
                    isValid = false,
                    version = null,
                    errorDetails = "boom",
                )
            },
        )

        val version = model.executableVersionTextProperty.nextValue("!")
        model.executableInputTextProperty.set("/usr/local/bin/mill")
        version.await()
    }

    @Test
    suspend fun `stale draft probe result does not override newer draft version hint`() {
        val slowProbeStarted = CountDownLatch(1)
        val slowProbeCancelled = CountDownLatch(1)
        val model = createModel(
            probe = { _, _, executablePath ->
                if (executablePath.endsWith("first")) {
                    slowProbeStarted.countDown()
                    // Block until the ViewModel cancels the stale draft probe.
                    try {
                        TimeUnit.SECONDS.sleep(10)
                    } catch (_: InterruptedException) {
                        slowProbeCancelled.countDown()
                    }
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

        val newerVersion = model.executableVersionTextProperty.nextValue("2.0.0")
        model.executableInputTextProperty.set("/tmp/first")
        Assertions.assertTrue(
            runInterruptible(Dispatchers.IO) { slowProbeStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS) },
            "the slow draft probe never started",
        )
        model.executableInputTextProperty.set("/tmp/second")
        newerVersion.await()

        // The stale result never lands because its probe is cancelled once a newer draft exists.
        Assertions.assertTrue(
            runInterruptible(Dispatchers.IO) { slowProbeCancelled.await(AWAIT_SECONDS, TimeUnit.SECONDS) },
            "the stale draft probe was not cancelled",
        )
        Assertions.assertEquals("2.0.0", model.executableVersionTextProperty.get())
    }

    @Test
    suspend fun `selecting another executable choice refreshes version hint for that choice`() {
        val model = createModel(
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
        awaitVersion(model, "project", "initial project version hint")

        val pathChoice = model.executableChoicesProperty.get()
            .single { it.inputMatchText == MillConstants.DEFAULT_EXECUTABLE }
        model.executableSelectedChoiceProperty.set(pathChoice)
        awaitVersion(model, "path", "path choice version hint")

        val projectChoice = model.executableChoicesProperty.get()
            .single { it.source == MillExecutableSource.PROJECT_DEFAULT_SCRIPT }
        model.executableSelectedChoiceProperty.set(projectChoice)
        awaitVersion(model, "project", "project choice version hint")
        // Switching choices clears the cached results, so "project" above was produced by the
        // committed probe's finish — nothing is in flight any more; assert that explicitly so a
        // future change cannot end the test with a probe still running.
        awaitCommittedProbesIdle(model)
    }

    @Test
    suspend fun `committed probe success updates version hint when draft matches selected choice`() {
        val model = createModel(
            probe = { _, source, _ ->
                MillExecutableProbeResult(
                    resolvedExecutable = source.name,
                    isValid = true,
                    version = "3.0.0",
                    errorDetails = null,
                )
            },
        )

        model.executableVersionTextProperty.nextValue("3.0.0").await()
        Assertions.assertEquals(
            model.executableSelectedChoiceProperty.get()?.editorText,
            model.executableInputTextProperty.get(),
        )
    }

    @Test
    suspend fun `committed probe does not override active draft version hint`() {
        val committedProbeMayFinish = CountDownLatch(1)
        val committedProbeStarted = CountDownLatch(1)
        val model = createModel(
            probe = { _, _, executablePath ->
                if (executablePath.isBlank()) {
                    committedProbeStarted.countDown()
                    committedProbeMayFinish.await(AWAIT_SECONDS, TimeUnit.SECONDS)
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
                        version = if (executablePath.endsWith("second")) "draft-2" else "draft",
                        errorDetails = null,
                    )
                }
            },
        )

        Assertions.assertTrue(
            runInterruptible(Dispatchers.IO) { committedProbeStarted.await(AWAIT_SECONDS, TimeUnit.SECONDS) },
            "the committed probe never started",
        )
        val draftVersion = model.executableVersionTextProperty.nextValue("draft")
        model.executableInputTextProperty.set("/usr/local/bin/mill")
        draftVersion.await()

        committedProbeMayFinish.countDown()
        // Fence 1: the committed result has been reduced. It changes no visible property (the
        // draft preview wins), so its arrival is awaited on the flow the ViewModel exposes.
        model.committedProbesRunning.first { !it }
        // Fence 2: a draft result requested after the committed one has been rendered. The
        // version text is a conflated StateFlow, so an unchanged intermediate value cannot be
        // awaited; the second draft therefore reports a distinct version.
        val secondDraftVersion = model.executableVersionTextProperty.nextValue("draft-2")
        model.executableInputTextProperty.set("/tmp/second")
        secondDraftVersion.await()

        val appliedProject = applyModel(model).single()
        Assertions.assertEquals(MillExecutableSource.PROJECT_DEFAULT_SCRIPT, appliedProject.millExecutableSource)
        Assertions.assertEquals("", appliedProject.millExecutablePath)
    }

    @Test
    suspend fun `draft probe result does not commit settings state`() {
        val model = createModel(
            probe = { _, _, executablePath ->
                MillExecutableProbeResult(
                    resolvedExecutable = executablePath,
                    isValid = true,
                    version = "1.2.3",
                    errorDetails = null,
                )
            },
        )
        val version = model.executableVersionTextProperty.nextValue("1.2.3")

        model.executableInputTextProperty.set("/usr/local/bin/mill")
        version.await()

        val appliedProject = applyModel(model).single()
        Assertions.assertEquals(MillExecutableSource.PROJECT_DEFAULT_SCRIPT, appliedProject.millExecutableSource)
        Assertions.assertEquals("", appliedProject.millExecutablePath)
    }

    @Test
    suspend fun `editor draft text updates bound right hint without committing settings`() {
        // The draft must report a version of its own: with one version for every probe, the wait
        // below could be satisfied by the committed project-default probe and then race the draft
        // preview, which legitimately blanks the hint while its probe is in flight.
        val draftVersionText = "draft-1.2.3"
        val model = createModel(
            probe = { _, _, executablePath ->
                MillExecutableProbeResult(
                    resolvedExecutable = executablePath,
                    isValid = true,
                    version = if (executablePath == "/usr/local/bin/mill") draftVersionText else "committed",
                    errorDetails = null,
                )
            },
        )
        val selector = createSelector(model)
        // Read the widget inside the notification of the draft version: the view's binding was
        // registered before this listener, so it must already be updated at that point. Reading
        // after an arbitrary wake-up would depend on later probe restarts.
        val hintWhenNotified = CompletableDeferred<String>()
        model.executableVersionTextProperty.afterChange(disposable) { value ->
            if (value == draftVersionText) {
                hintWhenNotified.complete(selector.rightHint)
            }
        }

        onUi { selector.editorTextField.text = "/usr/local/bin/mill" }
        Assertions.assertEquals(draftVersionText, hintWhenNotified.await())

        val appliedProject = applyModel(model).single()
        Assertions.assertEquals(MillExecutableSource.PROJECT_DEFAULT_SCRIPT, appliedProject.millExecutableSource)
        Assertions.assertEquals("", appliedProject.millExecutablePath)
    }

    @Test
    suspend fun `editor draft text updates bound left hint`() {
        val model = createModel(
            discovery = MillExecutableDiscovery(
                projectWrapper = null,
                pathExecutables = listOf(Path.of("/usr/local/bin/mill")),
            ),
        )
        val selector = createSelector(model)
        val nameHint = model.executableInputLeftHintProperty.nextValue("/usr/local/bin/mill")

        onUi { selector.editorTextField.text = "mill" }
        nameHint.await()
        Assertions.assertEquals("/usr/local/bin/mill", onUi { selector.trailingHint })

        val pathHint = model.executableInputLeftHintProperty.nextValue("/tmp/mill-project/tools/mill")
        onUi { selector.editorTextField.text = "tools/mill" }
        pathHint.await()
        Assertions.assertEquals("/tmp/mill-project/tools/mill", onUi { selector.trailingHint })
    }

    @Test
    suspend fun `editor draft text updates left hint after selecting inline manual choice`() {
        val model = createModel()
        val selector = createSelector(model)
        val selectionHint = model.executableInputLeftHintProperty.nextValue("/tmp/mill-project/tools/mill")

        onUi {
            selector.selectedItem = MillExecutableChoice(
                key = "manual:tools/mill",
                displayName = "tools/mill",
                editorHintText = null,
                source = MillExecutableSource.MANUAL,
                manualPath = "tools/mill",
                tooltipText = "tools/mill",
            )
        }
        selectionHint.await()
        Assertions.assertEquals("/tmp/mill-project/tools/mill", onUi { selector.trailingHint })

        val otherPathHint = model.executableInputLeftHintProperty.nextValue("/tmp/mill-project/other/mill")
        onUi { selector.editorTextField.text = "other/mill" }
        otherPathHint.await()
        Assertions.assertEquals("/tmp/mill-project/other/mill", onUi { selector.trailingHint })
    }

    @Test
    suspend fun `selector choice changes update bound right hint`() {
        val model = createModel(
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
        val selector = createSelector(model)
        model.executableVersionTextProperty.nextValue("project").await()
        Assertions.assertEquals("project", onUi { selector.rightHint })

        val pathVersion = model.executableVersionTextProperty.nextValue("path")
        onUi {
            selector.selectedItem = model.executableChoicesProperty.get()
                .single { it.inputMatchText == MillConstants.DEFAULT_EXECUTABLE }
        }
        pathVersion.await()
        Assertions.assertEquals("path", onUi { selector.rightHint })

        val projectVersion = model.executableVersionTextProperty.nextValue("project")
        onUi {
            selector.selectedItem = model.executableChoicesProperty.get()
                .single { it.source == MillExecutableSource.PROJECT_DEFAULT_SCRIPT }
        }
        projectVersion.await()
        Assertions.assertEquals("project", onUi { selector.rightHint })
    }

    @Test
    suspend fun `selecting another project syncs draft text and hints to that project choice`() {
        val firstProjectPath = "/tmp/first-mill-project"
        val secondProjectPath = "/tmp/second-mill-project"
        val firstSettings = MillProjectSettings().also {
            it.externalProjectPath = firstProjectPath
        }
        val secondSettings = MillProjectSettings().also {
            it.externalProjectPath = secondProjectPath
            it.millExecutableSource = MillExecutableSource.MANUAL
            it.millExecutablePath = MillConstants.DEFAULT_EXECUTABLE
        }
        val model = MillConfigurableViewModel(
            linkedProjectSettings = listOf(firstSettings, secondSettings),
            parentDisposable = modelParent,
            discoverExecutables = { projectPath ->
                when (projectPath.toString()) {
                    firstProjectPath -> MillExecutableDiscovery(
                        projectWrapper = Path.of(firstProjectPath, MillConstants.WRAPPER_SCRIPT_NAME),
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
            draftProbeDebounce = Duration.ZERO,
        )

        val firstVersion = model.executableVersionTextProperty.nextValue("first")
        val secondVersion = model.executableVersionTextProperty.nextValue("second")
        val secondProjectHint = model.executableInputLeftHintProperty.nextValue("/opt/mill-second/bin/mill")
        // The selected choice lags the project switch: it is derived asynchronously from the new
        // project's state, so await it explicitly instead of inferring it from another property.
        val secondProjectChoice = model.executableSelectedChoiceProperty.nextValueWhere {
            it != null && it.source == MillExecutableSource.MANUAL && it.manualPath == MillConstants.DEFAULT_EXECUTABLE
        }
        firstVersion.await()

        model.selectedProjectPathProperty.set(secondProjectPath)

        secondVersion.await()
        secondProjectHint.await()
        Assertions.assertEquals(secondProjectChoice.await()?.editorText, model.executableInputTextProperty.get())
    }

    private fun createModel(
        projectPath: String = "/tmp/mill-project",
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
            parentDisposable = modelParent,
            discoverExecutables = { discovery },
            probeExecutable = probe,
            draftProbeDebounce = Duration.ZERO,
        )
    }

    private fun applyModel(model: MillConfigurableViewModel): Set<MillProjectSettings> {
        val settings = MillSettings(project)
        model.applyTo(settings)
        return settings.linkedProjectsSettings.toSet()
    }

    /**
     * A completion notification for `property` reaching [expected]. Create it *before* the action
     * that causes the change: the listener then cannot miss the change, and by the time the
     * deferred completes, listeners registered earlier (the view's bindings) have already run.
     * A value that is already present completes immediately.
     */
    private fun <T> ObservableProperty<T>.nextValue(expected: T): Deferred<T> = nextValueWhere { it == expected }

    /** Same as [nextValue], for a value that is not known up front. */
    private fun <T> ObservableProperty<T>.nextValueWhere(predicate: (T) -> Boolean): Deferred<T> {
        val deferred = CompletableDeferred<T>()
        afterChange(disposable) { value ->
            if (predicate(value)) {
                deferred.complete(value)
            }
        }
        if (predicate(get())) {
            deferred.complete(get())
        }
        return deferred
    }

    /**
     * Awaits the model's own version flow. Unlike the observable property (whose notifications
     * run on the UI thread), this synchronizes on the value the ViewModel produces, so a
     * headless run does not depend on how quickly the UI queue is serviced. Values rest between
     * the phases of a test, and a fresh `first` collector cannot miss its start value.
     */
    private suspend fun awaitVersion(model: MillConfigurableViewModel, expected: String, step: String) {
        try {
            withTimeout(AWAIT_SECONDS.seconds) { model.executableVersionTextValues.first { it == expected } }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError(
                "$step: version `$expected` not reached within ${AWAIT_SECONDS}s " +
                        "(current `${model.executableVersionTextValues.value}`)",
                timeout,
            )
        }
    }

    private suspend fun awaitCommittedProbesIdle(model: MillConfigurableViewModel) {
        try {
            withTimeout(AWAIT_SECONDS.seconds) { model.committedProbesRunning.first { !it } }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("committed probes still running after ${AWAIT_SECONDS}s", timeout)
        }
    }

    /**
     * Runs Swing work on the UI thread. It runs as a root coroutine carrying nothing but the
     * dispatcher: inheriting the test's context would install the project's thread context on
     * the UI thread for the duration, which made the platform's project-leak check fail
     * intermittently after these tests.
     */
    private suspend fun <T> onUi(action: () -> T): T = CoroutineScope(Dispatchers.UI).async { action() }.await()

    private suspend fun createSelector(model: MillConfigurableViewModel): EditableHintedComboBox<MillExecutableChoice> {
        val panel = onUi { millConfigurableView(project, model) }
        return onUi {
            @Suppress("UNCHECKED_CAST")
            findComponent(panel) { it is EditableHintedComboBox<*> } as EditableHintedComboBox<MillExecutableChoice>
        }
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

    private companion object {
        private const val AWAIT_SECONDS = 3L
    }
}
