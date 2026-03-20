/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
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

package dev.ghostflyby.mill.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.UI
import com.intellij.openapi.observable.properties.ObservableMutableProperty
import com.intellij.openapi.observable.properties.ObservableProperty
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.observable.util.transform
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.util.Disposer
import dev.ghostflyby.intellij.ui.EditableHintedComboBoxItem
import dev.ghostflyby.mill.Bundle
import dev.ghostflyby.mill.MillConstants
import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.command.MillExecutableDiscovery
import dev.ghostflyby.mill.command.MillExecutableProbeResult
import kotlinx.coroutines.*
import java.nio.file.Path

internal class MillConfigurableViewModel(
    linkedProjectSettings: Collection<MillProjectSettings>,
    parentDisposable: Disposable,
) {
    private val propertyGraph = PropertyGraph("MillConfigurableViewModel")
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val originalSettingsByPath = linkedProjectSettings
        .map(MillProjectSettings::clone)
        .associateBy(MillProjectSettings::getExternalProjectPath)
        .toMutableMap()
    private val projectStatesByPath = originalSettingsByPath.values
        .associate { settings -> settings.externalProjectPath to MillLinkedProjectSettingsState.from(settings) }
        .toMutableMap()
    private val probeJobsByPath = mutableMapOf<String, Job>()
    private val discoveryJobsByPath = mutableMapOf<String, Job>()
    private val probeResultsByPath = mutableMapOf<String, MillCachedExecutableProbe>()
    private val executableDiscoveriesByPath = mutableMapOf<String, MillExecutableDiscovery>()
    private val checkingProjectPaths = linkedSetOf<String>()
    private var isSynchronizing = false
    private var isDisposed = false

    val linkedProjectPaths: List<String> = projectStatesByPath.keys.sorted()
    val hasLinkedProjects: Boolean = linkedProjectPaths.isNotEmpty()
    val hasMultipleLinkedProjects: Boolean = linkedProjectPaths.size > 1

    val selectedProjectPathProperty: ObservableMutableProperty<String> =
        propertyGraph.property(linkedProjectPaths.firstOrNull().orEmpty())
    val selectedProjectDisplayNameProperty: ObservableProperty<String> = selectedProjectPathProperty.transform { path ->
        path.takeUnless(String::isBlank)?.let(::presentableProjectName).orEmpty()
    }
    val executableChoicesProperty: ObservableMutableProperty<List<MillExecutableChoice>> =
        propertyGraph.property(emptyList())
    val executableSelectedChoiceKeyProperty: ObservableMutableProperty<String?> =
        propertyGraph.property(null)
    val executableInputTextProperty: ObservableMutableProperty<String> = propertyGraph.property("")
    val executableSelectedChoiceKeyBindingProperty: ObservableMutableProperty<String?> =
        createBindingProperty(executableSelectedChoiceKeyProperty, ::selectExecutableChoiceByKey)
    val executableInputTextBindingProperty: ObservableMutableProperty<String> =
        createBindingProperty(executableInputTextProperty, ::updateExecutableInput)
    val executableSelectionToolTipProperty: ObservableMutableProperty<String> = propertyGraph.property("")
    val executableVersionTextProperty: ObservableMutableProperty<String> = propertyGraph.property("")
    val executableStatusIsErrorProperty: ObservableMutableProperty<Boolean> = propertyGraph.property(false)
    val useMillMetadataDuringImportProperty: ObservableMutableProperty<Boolean> = propertyGraph.property(
        currentSelectedState()?.useMillMetadataDuringImport ?: true,
    )
    val createPerModuleTaskNodesProperty: ObservableMutableProperty<Boolean> = propertyGraph.property(
        currentSelectedState()?.createPerModuleTaskNodes ?: true,
    )

    init {
        Disposer.register(parentDisposable) {
            isDisposed = true
            scope.cancel()
        }

        selectedProjectPathProperty.afterChange(parentDisposable) {
            loadSelectedProjectState(it)
            refreshExecutableSelector()
            refreshDisplayedProbeStatus()
            it.takeUnless(String::isBlank)?.let { projectPath ->
                scheduleDiscovery(projectPath)
                scheduleProbe(projectPath)
            }
        }
        useMillMetadataDuringImportProperty.afterChange(parentDisposable) {
            if (!isSynchronizing) {
                persistSelectedProjectState()
            }
        }
        createPerModuleTaskNodesProperty.afterChange(parentDisposable) {
            if (!isSynchronizing) {
                persistSelectedProjectState()
            }
        }

        loadSelectedProjectState(selectedProjectPathProperty.get())
        refreshExecutableSelector()
        refreshDisplayedProbeStatus()
        linkedProjectPaths.forEachIndexed { index, projectPath ->
            scheduleDiscovery(projectPath, debounceMillis = if (index == 0) 0 else 100)
            scheduleProbe(projectPath, debounceMillis = if (index == 0) 0 else 150)
        }
    }

    fun resetFrom(linkedProjectSettings: Collection<MillProjectSettings>) {
        originalSettingsByPath.clear()
        linkedProjectSettings.map(MillProjectSettings::clone)
            .forEach { originalSettingsByPath[it.externalProjectPath] = it }

        projectStatesByPath.clear()
        originalSettingsByPath.values.forEach { settings ->
            projectStatesByPath[settings.externalProjectPath] = MillLinkedProjectSettingsState.from(settings)
        }

        probeJobsByPath.values.forEach(Job::cancel)
        probeJobsByPath.clear()
        discoveryJobsByPath.values.forEach(Job::cancel)
        discoveryJobsByPath.clear()
        probeResultsByPath.clear()
        executableDiscoveriesByPath.clear()
        checkingProjectPaths.clear()

        val selectedPath = selectedProjectPathProperty.get()
        val nextSelectedPath = when {
            selectedPath in projectStatesByPath -> selectedPath
            linkedProjectPaths.isNotEmpty() -> linkedProjectPaths.first()
            else -> ""
        }
        selectedProjectPathProperty.set(nextSelectedPath)
        loadSelectedProjectState(nextSelectedPath)
        refreshExecutableSelector()
        refreshDisplayedProbeStatus()
        linkedProjectPaths.forEachIndexed { index, projectPath ->
            scheduleDiscovery(projectPath, debounceMillis = if (index == 0) 0 else 100)
            scheduleProbe(projectPath, debounceMillis = if (index == 0) 0 else 150)
        }
    }

    fun isModified(currentSettings: Collection<MillProjectSettings>): Boolean {
        return snapshotStates(currentSettings) != projectStatesByPath.toSortedMap()
    }

    @Throws(ConfigurationException::class)
    fun validateBeforeApply() {
        projectStatesByPath.values.sortedBy(MillLinkedProjectSettingsState::externalProjectPath).forEach { state ->
            val validationMessage = validationMessageForApply(state) ?: return@forEach
            throw ConfigurationException(
                Bundle.message(
                    "settings.validation.executable.invalid",
                    presentableProjectName(state.externalProjectPath),
                    validationMessage,
                ),
            )
        }
    }

    fun applyTo(settings: MillSettings) {
        val updatedSettings = linkedSetOf<MillProjectSettings>()
        linkedProjectPaths.forEach { projectPath ->
            val state = projectStatesByPath[projectPath] ?: return@forEach
            val baseSettings = originalSettingsByPath[projectPath]?.clone() ?: MillProjectSettings().also {
                it.externalProjectPath = projectPath
            }
            baseSettings.millExecutableSource = state.executableSource
            baseSettings.millExecutablePath = state.manualExecutablePath
            baseSettings.useMillMetadataDuringImport = state.useMillMetadataDuringImport
            baseSettings.createPerModuleTaskNodes = state.createPerModuleTaskNodes
            updatedSettings += baseSettings
        }
        settings.linkedProjectsSettings = updatedSettings
        resetFrom(updatedSettings)
    }

    fun currentManualPathValidationMessage(): String? {
        return selectedProjectPathProperty.get()
            .takeUnless(String::isBlank)
            ?.let(projectStatesByPath::get)
            ?.let(::manualPathValidationMessage)
    }

    fun presentableProjectName(projectPath: String): String {
        return runCatching {
            Path.of(projectPath).fileName?.toString()
        }.getOrNull().takeUnless(String?::isNullOrBlank) ?: projectPath
    }

    fun selectExecutableChoiceByKey(selectedKey: String?) {
        if (isSynchronizing) {
            return
        }
        val selectedChoice = executableChoicesProperty.get().firstOrNull { it.key == selectedKey } ?: return
        applyExecutableSelection(selectedChoice.source, selectedChoice.manualPath)
    }

    fun updateExecutableInput(text: String) {
        if (isSynchronizing) {
            return
        }
        val trimmedText = text.trim()
        val matchedChoice = findExecutableChoiceByInput(trimmedText)
        if (matchedChoice != null) {
            applyExecutableSelection(matchedChoice.source, matchedChoice.manualPath)
            return
        }
        applyExecutableSelection(MillExecutableSource.MANUAL, trimmedText)
    }

    private fun <T> createBindingProperty(
        delegate: ObservableMutableProperty<T>,
        setter: (T) -> Unit,
    ): ObservableMutableProperty<T> {
        return object : ObservableMutableProperty<T> {
            override fun get(): T = delegate.get()

            override fun set(value: T) {
                setter(value)
            }

            override fun afterChange(parentDisposable: Disposable?, listener: (T) -> Unit) {
                delegate.afterChange(parentDisposable, listener)
            }
        }
    }

    private fun currentSelectedState(): MillLinkedProjectSettingsState? {
        return selectedProjectPathProperty.get()
            .takeUnless(String::isBlank)
            ?.let(projectStatesByPath::get)
    }

    private fun loadSelectedProjectState(projectPath: String?) {
        val state = projectPath?.let(projectStatesByPath::get) ?: return
        isSynchronizing = true
        try {
            useMillMetadataDuringImportProperty.set(state.useMillMetadataDuringImport)
            createPerModuleTaskNodesProperty.set(state.createPerModuleTaskNodes)
        } finally {
            isSynchronizing = false
        }
    }

    private fun persistSelectedProjectState() {
        val projectPath = selectedProjectPathProperty.get().takeUnless(String::isBlank) ?: return
        val existingState = projectStatesByPath[projectPath] ?: return
        projectStatesByPath[projectPath] = MillLinkedProjectSettingsState.create(
            externalProjectPath = projectPath,
            executableSource = existingState.executableSource,
            manualExecutablePath = existingState.manualExecutablePath,
            useMillMetadataDuringImport = useMillMetadataDuringImportProperty.get(),
            createPerModuleTaskNodes = createPerModuleTaskNodesProperty.get(),
        )
    }

    private fun applyExecutableSelection(source: MillExecutableSource, manualPath: String) {
        val projectPath = selectedProjectPathProperty.get().takeUnless(String::isBlank) ?: return
        val currentState = projectStatesByPath[projectPath] ?: return
        if (currentState.executableSource == source && currentState.manualExecutablePath == manualPath) {
            return
        }
        projectStatesByPath[projectPath] = MillLinkedProjectSettingsState.create(
            externalProjectPath = projectPath,
            executableSource = source,
            manualExecutablePath = manualPath,
            useMillMetadataDuringImport = currentState.useMillMetadataDuringImport,
            createPerModuleTaskNodes = currentState.createPerModuleTaskNodes,
        )
        refreshExecutableSelector()
        scheduleProbe(projectPath)
    }

    private fun validationMessageForApply(state: MillLinkedProjectSettingsState): String? {
        manualPathValidationMessage(state)?.let { return it }
        if (state.externalProjectPath in checkingProjectPaths) {
            return Bundle.message("settings.validation.executable.checking")
        }
        val cachedProbe = probeResultsByPath[state.externalProjectPath]
        if (cachedProbe == null || cachedProbe.settingsState != state) {
            return Bundle.message("settings.validation.executable.recheck.required")
        }
        if (!cachedProbe.probeResult.isValid) {
            return cleanProbeMessage(cachedProbe.probeResult.errorDetails)
        }
        return null
    }

    private fun manualPathValidationMessage(state: MillLinkedProjectSettingsState): String? {
        if (state.executableSource != MillExecutableSource.MANUAL) {
            return null
        }
        if (state.manualExecutablePath.isBlank()) {
            return Bundle.message("settings.validation.executable.manual.required")
        }
        if (state.manualExecutablePath.any { it == '\n' || it == '\r' }) {
            return Bundle.message("settings.validation.executable.single.path")
        }
        return null
    }

    private fun refreshExecutableSelector() {
        isSynchronizing = true
        try {
            val projectPath = selectedProjectPathProperty.get().takeUnless(String::isBlank)
            if (projectPath == null) {
                executableChoicesProperty.set(emptyList())
                executableSelectedChoiceKeyProperty.set(null)
                executableInputTextProperty.set("")
                executableSelectionToolTipProperty.set("")
                return
            }
            val state = projectStatesByPath[projectPath]
            if (state == null) {
                executableChoicesProperty.set(emptyList())
                executableSelectedChoiceKeyProperty.set(null)
                executableInputTextProperty.set("")
                executableSelectionToolTipProperty.set("")
                return
            }

            val choices = buildExecutableChoices(projectPath, state)
            val selectedChoice = findChoiceForState(state, choices)
            executableChoicesProperty.set(choices)
            executableSelectedChoiceKeyProperty.set(selectedChoice?.key)
            executableInputTextProperty.set(
                selectedChoice?.editorText ?: createManualEditorText(
                    projectPath,
                    state.manualExecutablePath,
                ),
            )
            executableSelectionToolTipProperty.set(
                selectedChoice?.tooltipText ?: createManualTooltip(
                    projectPath,
                    state.manualExecutablePath,
                ),
            )
        } finally {
            isSynchronizing = false
        }
    }

    private fun buildExecutableChoices(
        projectPath: String,
        state: MillLinkedProjectSettingsState,
    ): List<MillExecutableChoice> {
        val discovery = executableDiscoveriesByPath[projectPath]
        val discoveryCompleted = executableDiscoveriesByPath.containsKey(projectPath)
        val projectRoot = Path.of(projectPath)
        val expectedProjectWrapper = projectRoot.resolve(MillConstants.wrapperScriptName).normalize().toString()
        val projectWrapper = discovery?.projectWrapper?.toString()
        val pathExecutables = discovery?.pathExecutables.orEmpty().map(Path::toString)
        val cachedProbe = probeResultsByPath[projectPath]
            ?.takeIf { it.settingsState == state && it.probeResult.isValid }
            ?.probeResult
        val choices = linkedMapOf<String, MillExecutableChoice>()

        val projectChoice = MillExecutableChoice(
            key = "project",
            displayName = Bundle.message("settings.mill.executable.choice.project"),
            detailText = projectWrapper ?: expectedProjectWrapper,
            source = MillExecutableSource.PROJECT_DEFAULT_SCRIPT,
            manualPath = "",
            tooltipText = buildProjectTooltip(
                projectWrapper = projectWrapper,
                expectedProjectWrapper = expectedProjectWrapper,
                pathExecutables = pathExecutables,
                discoveryCompleted = discoveryCompleted,
            ),
            editorTextOverride = Bundle.message("settings.mill.executable.choice.project"),
        )
        choices[projectChoice.key] = projectChoice

        val pathChoice = MillExecutableChoice(
            key = "path",
            displayName = Bundle.message("settings.mill.executable.choice.path"),
            detailText = pathExecutables.firstOrNull()
                ?: cachedProbe?.resolvedExecutable?.takeUnless { it == MillConstants.defaultExecutable },
            source = MillExecutableSource.MANUAL,
            manualPath = MillConstants.defaultExecutable,
            tooltipText = buildPathTooltip(pathExecutables),
            editorTextOverride = Bundle.message("settings.mill.executable.choice.path"),
            inputMatchText = MillConstants.defaultExecutable,
        )
        choices[pathChoice.key] = pathChoice

        pathExecutables.forEach { executablePath ->
            val explicitChoice = createManualChoice(projectRoot, executablePath)
            choices[explicitChoice.key] = explicitChoice
        }

        return choices.values.toList()
    }

    private fun createManualChoice(projectRoot: Path, rawPath: String): MillExecutableChoice {
        val trimmedPath = rawPath.trim()
        val resolvedPath = resolveDisplayPath(projectRoot, trimmedPath)
        val displayName = runCatching { Path.of(trimmedPath).fileName?.toString() }.getOrNull()
            .takeUnless(String?::isNullOrBlank)
            ?: Bundle.message("settings.mill.executable.manual")
        val detailText = resolvedPath ?: trimmedPath.ifBlank { null }
        return MillExecutableChoice(
            key = "manual:$trimmedPath",
            displayName = displayName,
            detailText = detailText,
            source = MillExecutableSource.MANUAL,
            manualPath = trimmedPath,
            tooltipText = createManualTooltip(projectRoot.toString(), trimmedPath),
        )
    }

    private fun createManualEditorText(projectPath: String, manualPath: String): String {
        if (manualPath.isBlank()) {
            return ""
        }
        return createManualChoice(Path.of(projectPath), manualPath).manualPath
    }

    private fun createManualTooltip(projectPath: String, manualPath: String): String {
        if (manualPath.isBlank()) {
            return ""
        }
        val projectRoot = Path.of(projectPath)
        val resolvedPath = resolveDisplayPath(projectRoot, manualPath)
        return when {
            resolvedPath.isNullOrBlank() || resolvedPath == manualPath -> manualPath
            else -> "$manualPath\n$resolvedPath"
        }
    }

    private fun resolveDisplayPath(projectRoot: Path, rawPath: String): String? {
        if (rawPath.isBlank()) {
            return null
        }
        val parsedPath = runCatching { Path.of(rawPath) }.getOrNull() ?: return rawPath
        return if (parsedPath.isAbsolute) {
            parsedPath.normalize().toString()
        } else {
            projectRoot.resolve(parsedPath).normalize().toString()
        }
    }

    private fun findChoiceForState(
        state: MillLinkedProjectSettingsState,
        choices: List<MillExecutableChoice>,
    ): MillExecutableChoice? {
        return when (state.executableSource) {
            MillExecutableSource.PROJECT_DEFAULT_SCRIPT -> choices.firstOrNull { it.source == MillExecutableSource.PROJECT_DEFAULT_SCRIPT }
            MillExecutableSource.MANUAL,
                -> choices.firstOrNull { it.source == MillExecutableSource.MANUAL && it.manualPath == state.manualExecutablePath }
        }
    }

    internal fun findExecutableChoiceByInput(text: String): MillExecutableChoice? {
        if (text.isBlank()) {
            return null
        }
        return executableChoicesProperty.get().firstOrNull { choice ->
            text == choice.inputMatchText ||
                    (choice.source == MillExecutableSource.MANUAL &&
                            choice.inputMatchText == null &&
                            text == choice.manualPath)
        }
    }

    private fun scheduleDiscovery(projectPath: String, debounceMillis: Long = 0) {
        discoveryJobsByPath.remove(projectPath)?.cancel()
        discoveryJobsByPath[projectPath] = scope.launch {
            try {
                if (debounceMillis > 0) {
                    delay(debounceMillis)
                }
                val discovery = withContext(Dispatchers.IO) {
                    MillCommandLineUtil.discoverExecutables(Path.of(projectPath))
                }
                withContext(Dispatchers.UI) {
                    if (!scope.isActive || isDisposed) {
                        return@withContext
                    }
                    executableDiscoveriesByPath[projectPath] = discovery
                    if (selectedProjectPathProperty.get() == projectPath) {
                        refreshExecutableSelector()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    private fun scheduleProbe(projectPath: String, debounceMillis: Long = 300) {
        val state = projectStatesByPath[projectPath] ?: return
        val localValidationMessage = manualPathValidationMessage(state)
        probeJobsByPath.remove(projectPath)?.cancel()

        if (localValidationMessage != null) {
            checkingProjectPaths.remove(projectPath)
            if (selectedProjectPathProperty.get() == projectPath) {
                updateDisplayedProbeStatus(isError = true, versionText = "!")
            }
            return
        }

        checkingProjectPaths += projectPath
        if (selectedProjectPathProperty.get() == projectPath) {
            updateDisplayedProbeStatus(
                isError = false,
                versionText = checkingVersionText(projectPath, state),
            )
        }

        probeJobsByPath[projectPath] = scope.launch {
            try {
                if (debounceMillis > 0) {
                    delay(debounceMillis)
                }
                val probeResult = withContext(Dispatchers.IO) {
                    MillCommandLineUtil.probeExecutable(
                        projectRoot = Path.of(projectPath),
                        executableSource = state.executableSource,
                        executablePath = state.manualExecutablePath,
                    )
                }
                withContext(Dispatchers.Main) {
                    if (!scope.isActive || isDisposed) {
                        return@withContext
                    }
                    if (projectStatesByPath[projectPath] != state) {
                        return@withContext
                    }
                    checkingProjectPaths.remove(projectPath)
                    probeResultsByPath[projectPath] = MillCachedExecutableProbe(state, probeResult)
                    if (selectedProjectPathProperty.get() == projectPath) {
                        refreshExecutableSelector()
                        refreshDisplayedProbeStatus()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            }
        }
    }

    private fun refreshDisplayedProbeStatus() {
        val projectPath = selectedProjectPathProperty.get().takeUnless(String::isBlank)
        if (projectPath == null) {
            updateDisplayedProbeStatus(isError = false, versionText = "")
            return
        }
        val state = projectStatesByPath[projectPath]
        if (state == null) {
            updateDisplayedProbeStatus(isError = false, versionText = "")
            return
        }
        manualPathValidationMessage(state)?.let {
            updateDisplayedProbeStatus(isError = true, versionText = "!")
            return
        }
        if (projectPath in checkingProjectPaths) {
            updateDisplayedProbeStatus(
                isError = false,
                versionText = checkingVersionText(projectPath, state),
            )
            return
        }
        val cachedProbe = probeResultsByPath[projectPath]
        if (cachedProbe != null && cachedProbe.settingsState == state) {
            updateDisplayedProbeStatus(
                isError = !cachedProbe.probeResult.isValid,
                versionText = formatProbeVersion(cachedProbe.probeResult),
            )
            return
        }
        updateDisplayedProbeStatus(isError = false, versionText = "")
    }

    private fun checkingVersionText(projectPath: String, state: MillLinkedProjectSettingsState): String {
        val cachedProbe = probeResultsByPath[projectPath] ?: return ""
        if (cachedProbe.settingsState != state) {
            return ""
        }
        return formatProbeVersion(cachedProbe.probeResult).takeUnless { it == "!" } ?: ""
    }

    private fun updateDisplayedProbeStatus(isError: Boolean, versionText: String) {
        executableStatusIsErrorProperty.set(isError)
        executableVersionTextProperty.set(versionText)
    }

    private fun formatProbeVersion(probeResult: MillExecutableProbeResult): String {
        if (!probeResult.isValid) {
            return "!"
        }
        return probeResult.version?.takeUnless(String::isBlank) ?: "?"
    }

    private fun cleanProbeMessage(message: String?): String {
        val normalized = message.orEmpty()
            .lineSequence()
            .map(String::trim)
            .firstOrNull(String::isNotEmpty)
        return normalized ?: Bundle.message("settings.mill.executable.status.invalid.generic")
    }

    private fun buildProjectTooltip(
        projectWrapper: String?,
        expectedProjectWrapper: String,
        pathExecutables: List<String>,
        discoveryCompleted: Boolean,
    ): String {
        val lines = mutableListOf<String>()
        lines += projectWrapper ?: expectedProjectWrapper
        if (projectWrapper == null && discoveryCompleted) {
            lines += Bundle.message("settings.mill.executable.tooltip.project.missing")
        }
        if (pathExecutables.isNotEmpty()) {
            lines += ""
            lines += pathExecutables
        }
        return lines.joinToString("\n")
    }

    private fun buildPathTooltip(pathExecutables: List<String>): String {
        return pathExecutables.takeUnless(List<String>::isEmpty)?.joinToString("\n")
            ?: Bundle.message("settings.mill.executable.tooltip.path.empty")
    }

    private fun snapshotStates(settings: Collection<MillProjectSettings>): Map<String, MillLinkedProjectSettingsState> {
        return settings
            .map(MillLinkedProjectSettingsState::from)
            .associateBy(MillLinkedProjectSettingsState::externalProjectPath)
            .toSortedMap()
    }
}

private data class MillCachedExecutableProbe(
    val settingsState: MillLinkedProjectSettingsState,
    val probeResult: MillExecutableProbeResult,
)

internal data class MillLinkedProjectSettingsState(
    val externalProjectPath: String,
    val executableSource: MillExecutableSource,
    val manualExecutablePath: String,
    val useMillMetadataDuringImport: Boolean,
    val createPerModuleTaskNodes: Boolean,
) {
    companion object {
        fun from(settings: MillProjectSettings): MillLinkedProjectSettingsState {
            return create(
                externalProjectPath = settings.externalProjectPath,
                executableSource = settings.millExecutableSource,
                manualExecutablePath = settings.millExecutablePath,
                useMillMetadataDuringImport = settings.useMillMetadataDuringImport,
                createPerModuleTaskNodes = settings.createPerModuleTaskNodes,
            )
        }

        fun create(
            externalProjectPath: String,
            executableSource: MillExecutableSource,
            manualExecutablePath: String,
            useMillMetadataDuringImport: Boolean,
            createPerModuleTaskNodes: Boolean,
        ): MillLinkedProjectSettingsState {
            val executableConfiguration =
                MillExecutableConfigurationUtil.normalize(executableSource, manualExecutablePath)
            return MillLinkedProjectSettingsState(
                externalProjectPath = externalProjectPath,
                executableSource = executableConfiguration.source,
                manualExecutablePath = executableConfiguration.manualPath,
                useMillMetadataDuringImport = useMillMetadataDuringImport,
                createPerModuleTaskNodes = createPerModuleTaskNodes,
            )
        }
    }
}

internal data class MillExecutableChoice(
    override val key: String,
    override val displayName: String,
    val detailText: String?,
    val source: MillExecutableSource,
    val manualPath: String,
    val tooltipText: String,
    val editorTextOverride: String? = null,
    val inputMatchText: String? = null,
) : EditableHintedComboBoxItem {
    override val editorText: String
        get() = editorTextOverride ?: when (source) {
            MillExecutableSource.MANUAL -> manualPath
            else -> displayName
        }

    override val trailingHint: String
        get() = when {
            source != MillExecutableSource.MANUAL -> detailText.orEmpty()
            detailText.isNullOrBlank() || detailText == manualPath -> ""
            else -> detailText
        }
}
