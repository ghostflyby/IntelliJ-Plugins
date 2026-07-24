/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.settings

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.UI
import com.intellij.openapi.observable.properties.ObservableMutableProperty
import com.intellij.openapi.observable.properties.ObservableProperty
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.util.Disposer
import dev.ghostflyby.mill.Bundle
import dev.ghostflyby.mill.MillConstants
import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.command.MillExecutableDiscovery
import dev.ghostflyby.mill.command.MillExecutableProbeResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal class MillConfigurableViewModel(
    linkedProjectSettings: Collection<MillProjectSettings>,
    parentDisposable: Disposable,
    private val discoverExecutables: (Path) -> MillExecutableDiscovery = MillCommandLineUtil::discoverExecutables,
    private val probeExecutable: (Path, MillExecutableSource, String) -> MillExecutableProbeResult = MillCommandLineUtil::probeExecutable,
    private val draftProbeDebounceMillis: Long = 300,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _originalSettingsByPath = MutableStateFlow(originalSettingsMap(linkedProjectSettings))
    private val _projectStatesByPath = MutableStateFlow(projectStatesFrom(_originalSettingsByPath.value))
    private val _resetVersion = MutableStateFlow(0)
    private val _selectedProjectPath =
        MutableStateFlow(_projectStatesByPath.value.keys.minOrNull().orEmpty())
    private val initialExecutableChoices = currentExecutableChoices()
    private val initialExecutableChoice = currentSelectedExecutableChoice(initialExecutableChoices)
    private val _executableInputText = MutableStateFlow(initialExecutableChoice?.editorText.orEmpty())

    val linkedProjectPaths: List<String> get() = _projectStatesByPath.value.keys.sorted()
    val hasLinkedProjects: Boolean get() = linkedProjectPaths.isNotEmpty()
    val hasMultipleLinkedProjects: Boolean get() = linkedProjectPaths.size > 1

    private val selectedProjectStateFlow: StateFlow<MillLinkedProjectSettingsState?> =
        combine(_projectStatesByPath, _selectedProjectPath) { statesByPath, path ->
            path.takeUnless(String::isBlank)?.let(statesByPath::get)
        }.stateIn(scope, SharingStarted.Eagerly, currentSelectedState())

    private val selectedProjectDisplayNameFlow: StateFlow<String> = _selectedProjectPath
        .map { path -> path.takeUnless(String::isBlank)?.let(::presentableProjectName).orEmpty() }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            _selectedProjectPath.value.takeUnless(String::isBlank)?.let(::presentableProjectName).orEmpty(),
        )

    private val discoveryResultsFlow: StateFlow<Map<String, MillExecutableDiscovery>> =
        _projectStatesByPath
            .map { states -> states.keys.sorted() }
            .distinctUntilChanged()
            .flatMapLatest { projectPaths ->
                if (projectPaths.isEmpty()) {
                    flowOf(emptyMap<String, MillExecutableDiscovery>())
                } else {
                    val discoveryFlows: Iterable<Flow<Pair<String, MillExecutableDiscovery>>> =
                        projectPaths.map { projectPath ->
                            flow<Pair<String, MillExecutableDiscovery>> {
                                val discovery = runInterruptible(Dispatchers.IO) {
                                    discoverExecutables(Path.of(projectPath))
                                }
                                emit(projectPath to discovery)
                            }
                        }
                    discoveryFlows.merge()
                        .runningFold(emptyMap<String, MillExecutableDiscovery>()) { discoveries, discovered ->
                            discoveries + (discovered.first to discovered.second)
                        }
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private val projectStatesSetFlow: Flow<Set<MillLinkedProjectSettingsState>> =
        combine(_projectStatesByPath, _resetVersion) { statesByPath, resetVersion ->
            resetVersion to statesByPath.values.toSet()
        }
            .distinctUntilChanged()
            .map { it.second }

    private val committedProbeEventsFlow: Flow<MillCommittedProbeEvent> = merge(
        _resetVersion.drop(1).map { MillCommittedProbeEvent.Clear as MillCommittedProbeEvent },
        projectStatesSetFlow.map { MillCommittedProbeEvent.ActiveStates(it) },
        projectStatesSetFlow.flatMapLatest { states ->
            val probeFlows: List<Flow<MillCommittedProbeEvent>> = states.mapNotNull { state ->
                if (manualPathValidationMessage(state) != null) {
                    null
                } else {
                    flow {
                        emit(MillCommittedProbeEvent.Started(state))
                        delay(300.milliseconds)
                        val result = runInterruptible(Dispatchers.IO) {
                            probeExecutable(
                                Path.of(state.externalProjectPath),
                                state.executableSource,
                                state.manualExecutablePath,
                            )
                        }
                        emit(MillCommittedProbeEvent.Finished(state, result))
                    }
                }
            }
            if (probeFlows.isEmpty()) {
                emptyFlow<MillCommittedProbeEvent>()
            } else {
                probeFlows.merge()
            }
        },
    )

    private val committedProbeStateFlow: StateFlow<MillCommittedProbeState> =
        committedProbeEventsFlow
            .runningFold(MillCommittedProbeState(), ::reduceCommittedProbeState)
            .stateIn(scope, SharingStarted.Eagerly, MillCommittedProbeState())

    private val executableChoicesFlow: StateFlow<List<MillExecutableChoice>> =
        combine(
            _selectedProjectPath,
            selectedProjectStateFlow,
            discoveryResultsFlow,
            committedProbeStateFlow,
        ) { projectPath, state, discoveriesByPath, probeState ->
            if (projectPath.isBlank() || state == null) {
                emptyList()
            } else {
                buildExecutableChoices(projectPath, state, discoveriesByPath[projectPath], probeState.results)
            }
        }.stateIn(scope, SharingStarted.Eagerly, initialExecutableChoices)

    private val executableSelectedChoiceFlow: StateFlow<MillExecutableChoice?> =
        combine(selectedProjectStateFlow, executableChoicesFlow) { state, choices ->
            state?.let { findChoiceForState(it, choices) }
        }.stateIn(scope, SharingStarted.Eagerly, initialExecutableChoice)

    private val executablePreviewStateFlow: StateFlow<MillLinkedProjectSettingsState?> =
        combine(
            selectedProjectStateFlow,
            _executableInputText,
            executableChoicesFlow,
            executableSelectedChoiceFlow,
        ) { selectedProjectState, inputText, choices, selectedChoice ->
            executableDraftState(selectedProjectState, inputText, choices, selectedChoice)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            executableDraftState(
                currentSelectedState(),
                _executableInputText.value,
                initialExecutableChoices,
                initialExecutableChoice,
            ),
        )

    private val executableInputLeftHintFlow: StateFlow<String> =
        combine(
            _selectedProjectPath,
            _executableInputText,
            executableChoicesFlow,
            executableSelectedChoiceFlow,
            discoveryResultsFlow,
        ) { projectPath, inputText, choices, selectedChoice, discoveriesByPath ->
            leftHintForExecutableInput(projectPath, inputText, choices, selectedChoice, discoveriesByPath[projectPath])
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            leftHintForExecutableInput(
                _selectedProjectPath.value,
                _executableInputText.value,
                initialExecutableChoices,
                initialExecutableChoice,
                null,
            ),
        )

    private val draftProbeResultFlow: StateFlow<MillCachedExecutableProbe?> =
        executablePreviewStateFlow
            .transformLatest { draftState ->
                emit(null)
                if (draftState == null ||
                    draftState == selectedProjectStateFlow.value ||
                    manualPathValidationMessage(draftState) != null
                ) {
                    return@transformLatest
                }
                if (draftProbeDebounceMillis > 0) {
                    delay(draftProbeDebounceMillis)
                }
                val result = runInterruptible(Dispatchers.IO) {
                    probeExecutable(
                        Path.of(draftState.externalProjectPath),
                        draftState.executableSource,
                        draftState.manualExecutablePath,
                    )
                }
                emit(MillCachedExecutableProbe(draftState, result))
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    private val executableVersionTextFlow: StateFlow<String> =
        combine(
            executablePreviewStateFlow,
            committedProbeStateFlow,
            draftProbeResultFlow,
        ) { previewState, probeState, draftProbe ->
            versionTextForPreview(previewState, probeState, draftProbe)
        }.stateIn(scope, SharingStarted.Eagerly, "")

    private val executableValidationMessageFlow: StateFlow<String?> =
        combine(selectedProjectStateFlow, committedProbeStateFlow) { selectedState, probeState ->
            executableValidationMessage(selectedState, probeState)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private val selectedProjectUseMillMetadataFlow: StateFlow<Boolean> = selectedProjectStateFlow
        .map { state -> state?.useMillMetadataDuringImport ?: true }
        .stateIn(scope, SharingStarted.Eagerly, currentSelectedState()?.useMillMetadataDuringImport ?: true)

    private val selectedProjectCreatePerModuleTasksFlow: StateFlow<Boolean> = selectedProjectStateFlow
        .map { state -> state?.createPerModuleTaskNodes ?: true }
        .stateIn(scope, SharingStarted.Eagerly, currentSelectedState()?.createPerModuleTaskNodes ?: true)

    val selectedProjectPathProperty: ObservableMutableProperty<String> =
        _selectedProjectPath.toReducerObservable(
            scope = scope,
            setter = ::selectProjectPath,
        )
    val selectedProjectDisplayNameProperty: ObservableProperty<String> =
        selectedProjectDisplayNameFlow.toObservable(scope)
    val executableChoicesProperty: ObservableProperty<List<MillExecutableChoice>> =
        executableChoicesFlow.toObservable(scope)
    val executableSelectedChoiceProperty: ObservableMutableProperty<MillExecutableChoice?> =
        executableSelectedChoiceFlow.toReducerObservable(
            scope = scope,
            setter = ::selectExecutableChoice,
        )
    val executableInputTextProperty: ObservableMutableProperty<String> = _executableInputText.toObservable(scope)
    val executableInputLeftHintProperty: ObservableProperty<String> = executableInputLeftHintFlow.toObservable(scope)
    val executableSelectionToolTipProperty: ObservableProperty<String> = combine(
        _selectedProjectPath,
        selectedProjectStateFlow,
        executableSelectedChoiceFlow,
    ) { projectPath, state, selectedChoice ->
        selectedChoice?.tooltipText ?: state?.let { createManualTooltip(projectPath, it.manualExecutablePath) }
            .orEmpty()
    }.stateIn(scope, SharingStarted.Eagerly, initialExecutableChoice?.tooltipText.orEmpty()).toObservable(scope)
    val executableVersionTextProperty: ObservableProperty<String> = executableVersionTextFlow.toObservable(scope)
    val executableValidationMessageProperty: ObservableProperty<String?> =
        executableValidationMessageFlow.toObservable(scope)
    val useMillMetadataDuringImportProperty: ObservableMutableProperty<Boolean> =
        selectedProjectUseMillMetadataFlow.toReducerObservable(
            scope = scope,
            setter = { updateSelectedProjectFlags(useMillMetadataDuringImport = it) },
        )
    val createPerModuleTaskNodesProperty: ObservableMutableProperty<Boolean> =
        selectedProjectCreatePerModuleTasksFlow.toReducerObservable(
            scope = scope,
            setter = { updateSelectedProjectFlags(createPerModuleTaskNodes = it) },
        )

    init {
        Disposer.register(parentDisposable) {
            scope.cancel()
        }
    }

    fun resetFrom(linkedProjectSettings: Collection<MillProjectSettings>) {
        val nextOriginalSettingsByPath = originalSettingsMap(linkedProjectSettings)
        val nextProjectStatesByPath = projectStatesFrom(nextOriginalSettingsByPath)

        _originalSettingsByPath.value = nextOriginalSettingsByPath
        _projectStatesByPath.value = nextProjectStatesByPath
        _resetVersion.value += 1

        val selectedPath = _selectedProjectPath.value
        val nextSelectedPath = when {
            selectedPath in nextProjectStatesByPath -> selectedPath
            nextProjectStatesByPath.isNotEmpty() -> nextProjectStatesByPath.keys.sorted().first()
            else -> ""
        }
        _selectedProjectPath.value = nextSelectedPath
        _executableInputText.value =
            selectedChoiceFor(nextSelectedPath, nextProjectStatesByPath, emptyMap(), emptyMap())
                ?.editorText
                .orEmpty()
    }

    fun isModified(currentSettings: Collection<MillProjectSettings>): Boolean {
        return snapshotStates(currentSettings) != _projectStatesByPath.value.toSortedMap()
    }

    @Throws(ConfigurationException::class)
    fun validateBeforeApply() {
        val probeState = committedProbeStateFlow.value
        _projectStatesByPath.value.values.sortedBy(MillLinkedProjectSettingsState::externalProjectPath)
            .forEach { state ->
                val validationMessage = validationMessageForApply(state, probeState) ?: return@forEach
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
        val currentStatesByPath = _projectStatesByPath.value
        val currentOriginalSettingsByPath = _originalSettingsByPath.value
        currentStatesByPath.keys.sorted().forEach { projectPath ->
            val state = currentStatesByPath[projectPath] ?: return@forEach
            val baseSettings = currentOriginalSettingsByPath[projectPath]?.clone() ?: MillProjectSettings().also {
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
        return selectedProjectStateFlow.value?.let(::manualPathValidationMessage)
    }

    fun currentExecutableValidationMessage(): String? {
        return executableValidationMessageFlow.value
    }

    fun presentableProjectName(projectPath: String): String {
        return runCatching {
            Path.of(projectPath).fileName?.toString()
        }.getOrNull().takeUnless(String?::isNullOrBlank) ?: projectPath
    }

    private fun selectProjectPath(projectPath: String) {
        _selectedProjectPath.value = projectPath
        _executableInputText.value = selectedChoiceFor(
            projectPath = projectPath,
            statesByPath = _projectStatesByPath.value,
            discoveriesByPath = discoveryResultsFlow.value,
            probeResults = committedProbeStateFlow.value.results,
        )
            ?.editorText
            .orEmpty()
    }

    fun selectExecutableChoice(selectedChoice: MillExecutableChoice?) {
        selectedChoice ?: return
        updateSelectedProjectState { currentState ->
            MillLinkedProjectSettingsState.create(
                externalProjectPath = currentState.externalProjectPath,
                executableSource = selectedChoice.source,
                manualExecutablePath = selectedChoice.manualPath,
                useMillMetadataDuringImport = currentState.useMillMetadataDuringImport,
                createPerModuleTaskNodes = currentState.createPerModuleTaskNodes,
            )
        }
        _executableInputText.value = selectedChoice.editorText
    }

    private fun currentSelectedState(): MillLinkedProjectSettingsState? {
        return _selectedProjectPath.value
            .takeUnless(String::isBlank)
            ?.let(_projectStatesByPath.value::get)
    }

    private fun updateSelectedProjectFlags(
        useMillMetadataDuringImport: Boolean? = null,
        createPerModuleTaskNodes: Boolean? = null,
    ) {
        updateSelectedProjectState { currentState ->
            MillLinkedProjectSettingsState.create(
                externalProjectPath = currentState.externalProjectPath,
                executableSource = currentState.executableSource,
                manualExecutablePath = currentState.manualExecutablePath,
                useMillMetadataDuringImport = useMillMetadataDuringImport ?: currentState.useMillMetadataDuringImport,
                createPerModuleTaskNodes = createPerModuleTaskNodes ?: currentState.createPerModuleTaskNodes,
            )
        }
    }

    private fun updateSelectedProjectState(transform: (MillLinkedProjectSettingsState) -> MillLinkedProjectSettingsState) {
        val selectedProjectPath = _selectedProjectPath.value.takeUnless(String::isBlank) ?: return
        _projectStatesByPath.update { statesByPath ->
            val currentState = statesByPath[selectedProjectPath] ?: return@update statesByPath
            statesByPath + (selectedProjectPath to transform(currentState))
        }
    }

    private fun validationMessageForApply(
        state: MillLinkedProjectSettingsState,
        probeState: MillCommittedProbeState,
    ): String? {
        manualPathValidationMessage(state)?.let { return it }
        if (state in probeState.runningStates) {
            return Bundle.message("settings.validation.executable.checking")
        }
        val cachedProbe =
            probeState.results[state] ?: return Bundle.message("settings.validation.executable.recheck.required")
        if (!cachedProbe.isValid) {
            return cleanProbeMessage(cachedProbe.errorDetails)
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

    private fun currentExecutableChoices(): List<MillExecutableChoice> {
        val projectPath = _selectedProjectPath.value.takeUnless(String::isBlank) ?: return emptyList()
        val state = _projectStatesByPath.value[projectPath] ?: return emptyList()
        return buildExecutableChoices(projectPath, state, null, emptyMap())
    }

    private fun currentSelectedExecutableChoice(choices: List<MillExecutableChoice>): MillExecutableChoice? {
        return currentSelectedState()?.let { findChoiceForState(it, choices) }
    }

    private fun selectedChoiceFor(
        projectPath: String,
        statesByPath: Map<String, MillLinkedProjectSettingsState>,
        discoveriesByPath: Map<String, MillExecutableDiscovery>,
        probeResults: Map<MillLinkedProjectSettingsState, MillExecutableProbeResult>,
    ): MillExecutableChoice? {
        val state = statesByPath[projectPath] ?: return null
        val choices = buildExecutableChoices(projectPath, state, discoveriesByPath[projectPath], probeResults)
        return findChoiceForState(state, choices)
    }

    private fun buildExecutableChoices(
        projectPath: String,
        state: MillLinkedProjectSettingsState,
        discovery: MillExecutableDiscovery?,
        probeResults: Map<MillLinkedProjectSettingsState, MillExecutableProbeResult>,
    ): List<MillExecutableChoice> {
        val discoveryCompleted = discovery != null
        val projectRoot = Path.of(projectPath)
        val expectedProjectWrapper = expectedProjectWrapperPath(projectRoot)
        val projectWrapper = discovery?.projectWrapper?.toString()
        val pathExecutables = discovery?.pathExecutables.orEmpty().map(Path::toString)
        val selectedProbe = probeResults[state]
            ?.takeIf { it.isValid }
        val choices = linkedMapOf<String, MillExecutableChoice>()

        val projectChoice = MillExecutableChoice(
            key = "project",
            displayName = Bundle.message("settings.mill.executable.choice.project"),
            editorHintText = projectWrapper ?: expectedProjectWrapper,
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
            editorHintText = pathExecutables.firstOrNull()
                ?: selectedProbe
                    ?.takeIf {
                        state.executableSource == MillExecutableSource.MANUAL &&
                                state.manualExecutablePath == MillConstants.defaultExecutable
                    }
                    ?.resolvedExecutable
                    ?.takeUnless { it == MillConstants.defaultExecutable }
                ?: pathExecutables.firstOrNull(),
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
        val hintText = resolvedPath ?: trimmedPath.ifBlank { null }
        return MillExecutableChoice(
            key = "manual:$trimmedPath",
            displayName = displayName,
            editorHintText = hintText,
            source = MillExecutableSource.MANUAL,
            manualPath = trimmedPath,
            tooltipText = createManualTooltip(projectRoot.toString(), trimmedPath),
        )
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

    private fun expectedProjectWrapperPath(projectRoot: Path): String {
        val wrapperName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            MillConstants.wrapperBatchName
        } else {
            MillConstants.wrapperScriptName
        }
        return projectRoot.resolve(wrapperName).normalize().toString()
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
        return executableInputChoices(
            executableChoicesFlow.value,
            executableSelectedChoiceFlow.value,
        ).firstOrNull { choice ->
            text == choice.editorText ||
                    text == choice.inputMatchText ||
                    (choice.source == MillExecutableSource.MANUAL &&
                            choice.inputMatchText == null &&
                            text == choice.manualPath)
        }
    }

    private fun executableInputChoices(
        choices: List<MillExecutableChoice>,
        selectedChoice: MillExecutableChoice?,
    ): Sequence<MillExecutableChoice> {
        return sequence {
            choices.forEach { yield(it) }
            selectedChoice?.let { yield(it) }
        }.distinctBy(MillExecutableChoice::key)
    }

    private fun leftHintForExecutableInput(
        projectPath: String,
        text: String,
        choices: List<MillExecutableChoice>,
        selectedChoice: MillExecutableChoice?,
        discovery: MillExecutableDiscovery?,
    ): String {
        val trimmedText = text.trim()
        if (trimmedText.isBlank()) {
            return ""
        }
        val matchedChoice = executableInputChoices(choices, selectedChoice).firstOrNull { choice ->
            trimmedText == choice.editorText ||
                    trimmedText == choice.inputMatchText ||
                    (choice.source == MillExecutableSource.MANUAL &&
                            choice.inputMatchText == null &&
                            trimmedText == choice.manualPath)
        }
        matchedChoice?.editorHintText?.let { return it }
        if (projectPath.isBlank()) {
            return ""
        }
        val hintInput = matchedChoice
            ?.takeIf { it.source == MillExecutableSource.MANUAL }
            ?.manualPath
            ?.takeUnless(String::isBlank)
            ?: trimmedText
        findPathExecutableByInput(discovery, hintInput)?.let { return it }
        if (isExecutableName(hintInput)) {
            return ""
        }
        return resolveDisplayPath(Path.of(projectPath), hintInput).orEmpty()
    }

    private fun findPathExecutableByInput(discovery: MillExecutableDiscovery?, input: String): String? {
        return discovery
            ?.pathExecutables
            .orEmpty()
            .firstOrNull { executable ->
                executable.toString() == input || executable.fileName?.toString() == input
            }
            ?.toString()
    }

    private fun isExecutableName(text: String): Boolean {
        return !text.contains('/') &&
                !text.contains('\\') &&
                !text.startsWith(".") &&
                runCatching { Path.of(text).nameCount == 1 }.getOrDefault(false)
    }

    private fun executableDraftState(
        selectedProjectState: MillLinkedProjectSettingsState?,
        text: String,
        choices: List<MillExecutableChoice>,
        selectedChoice: MillExecutableChoice?,
    ): MillLinkedProjectSettingsState? {
        val currentState = selectedProjectState ?: return null
        val trimmedText = text.trim()
        if (trimmedText.isBlank()) {
            return null
        }
        val choice = executableInputChoices(choices, selectedChoice).firstOrNull { candidate ->
            trimmedText == candidate.editorText ||
                    trimmedText == candidate.inputMatchText ||
                    (candidate.source == MillExecutableSource.MANUAL &&
                            candidate.inputMatchText == null &&
                            trimmedText == candidate.manualPath)
        }
        return MillLinkedProjectSettingsState.create(
            externalProjectPath = currentState.externalProjectPath,
            executableSource = choice?.source ?: MillExecutableSource.MANUAL,
            manualExecutablePath = choice?.manualPath ?: trimmedText,
            useMillMetadataDuringImport = currentState.useMillMetadataDuringImport,
            createPerModuleTaskNodes = currentState.createPerModuleTaskNodes,
        )
    }

    private fun versionTextForPreview(
        previewState: MillLinkedProjectSettingsState?,
        probeState: MillCommittedProbeState,
        draftProbe: MillCachedExecutableProbe?,
    ): String {
        if (previewState == null) {
            return ""
        }
        manualPathValidationMessage(previewState)?.let {
            return "!"
        }
        probeState.results[previewState]?.let {
            return formatProbeVersion(it)
        }
        draftProbe
            ?.takeIf { it.settingsState == previewState }
            ?.let { return formatProbeVersion(it.probeResult) }
        if (previewState in probeState.runningStates) {
            return probeState.results[previewState]?.let(::formatProbeVersion).takeUnless { it == "!" } ?: ""
        }
        return ""
    }

    private fun executableValidationMessage(
        selectedState: MillLinkedProjectSettingsState?,
        probeState: MillCommittedProbeState,
    ): String? {
        val state = selectedState ?: return null
        manualPathValidationMessage(state)?.let { return it }
        val cachedProbe = probeState.results[state] ?: return null
        return cachedProbe.errorDetails
            ?.takeIf { !cachedProbe.isValid }
            ?.let(::cleanProbeMessage)
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

    private fun originalSettingsMap(settings: Collection<MillProjectSettings>): Map<String, MillProjectSettings> {
        return settings
            .map(MillProjectSettings::clone)
            .associateBy(MillProjectSettings::getExternalProjectPath)
    }

    private fun projectStatesFrom(settingsByPath: Map<String, MillProjectSettings>): Map<String, MillLinkedProjectSettingsState> {
        return settingsByPath.values.associate { settings ->
            settings.externalProjectPath to MillLinkedProjectSettingsState.from(settings)
        }
    }
}

private data class MillCachedExecutableProbe(
    val settingsState: MillLinkedProjectSettingsState,
    val probeResult: MillExecutableProbeResult,
)

private data class MillCommittedProbeState(
    val activeStates: Set<MillLinkedProjectSettingsState> = emptySet(),
    val runningStates: Set<MillLinkedProjectSettingsState> = emptySet(),
    val results: Map<MillLinkedProjectSettingsState, MillExecutableProbeResult> = emptyMap(),
)

private sealed interface MillCommittedProbeEvent {
    data object Clear : MillCommittedProbeEvent

    data class ActiveStates(
        val states: Set<MillLinkedProjectSettingsState>,
    ) : MillCommittedProbeEvent

    data class Started(
        val state: MillLinkedProjectSettingsState,
    ) : MillCommittedProbeEvent

    data class Finished(
        val state: MillLinkedProjectSettingsState,
        val result: MillExecutableProbeResult,
    ) : MillCommittedProbeEvent
}

private fun reduceCommittedProbeState(
    currentState: MillCommittedProbeState,
    event: MillCommittedProbeEvent,
): MillCommittedProbeState {
    return when (event) {
        MillCommittedProbeEvent.Clear -> MillCommittedProbeState()
        is MillCommittedProbeEvent.ActiveStates -> currentState.copy(
            activeStates = event.states,
            runningStates = currentState.runningStates.intersect(event.states),
            results = currentState.results.filterKeys(event.states::contains),
        )

        is MillCommittedProbeEvent.Started -> currentState.copy(
            activeStates = currentState.activeStates + event.state,
            runningStates = currentState.runningStates + event.state,
        )

        is MillCommittedProbeEvent.Finished -> currentState.copy(
            activeStates = currentState.activeStates + event.state,
            runningStates = currentState.runningStates - event.state,
            results = currentState.results + (event.state to event.result),
        )
    }
}

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
    val key: String,
    val displayName: String,
    val editorHintText: String?,
    val source: MillExecutableSource,
    val manualPath: String,
    val tooltipText: String,
    val editorTextOverride: String? = null,
    val inputMatchText: String? = null,
) {
    val editorText: String
        get() = editorTextOverride ?: when (source) {
            MillExecutableSource.MANUAL -> manualPath
            else -> displayName
        }
}

internal fun <T> StateFlow<T>.toObservable(scope: CoroutineScope): ObservableProperty<T> {
    return object : ObservableProperty<T> {
        override fun get(): T {
            return value
        }

        override fun afterChange(parentDisposable: Disposable?, listener: (T) -> Unit) {
            registerAfterChange(scope, parentDisposable, listener)
        }
    }
}

internal fun <T> MutableStateFlow<T>.toObservable(scope: CoroutineScope): ObservableMutableProperty<T> {
    return toMutableObservable(scope)
}

internal fun <T> MutableStateFlow<T>.toMutableObservable(scope: CoroutineScope): ObservableMutableProperty<T> {
    return object : ObservableMutableProperty<T> {
        override fun set(value: T) {
            this@toMutableObservable.value = value
        }

        override fun get(): T {
            return value
        }

        override fun afterChange(parentDisposable: Disposable?, listener: (T) -> Unit) {
            registerAfterChange(scope, parentDisposable, listener)
        }
    }
}

internal fun <T> StateFlow<T>.toReducerObservable(
    scope: CoroutineScope,
    setter: (T) -> Unit,
): ObservableMutableProperty<T> {
    return object : ObservableMutableProperty<T> {
        override fun set(value: T) {
            setter(value)
        }

        override fun get(): T {
            return value
        }

        override fun afterChange(parentDisposable: Disposable?, listener: (T) -> Unit) {
            registerAfterChange(scope, parentDisposable, listener)
        }
    }
}

private fun <T> StateFlow<T>.registerAfterChange(
    scope: CoroutineScope,
    parentDisposable: Disposable?,
    listener: (T) -> Unit,
) {
    val job = scope.launch {
        drop(1).collect { value ->
            withContext(Dispatchers.UI) {
                listener(value)
            }
        }
    }
    parentDisposable?.let {
        Disposer.register(it) {
            job.cancel()
        }
    }
}
