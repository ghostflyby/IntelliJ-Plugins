/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.icons.AllIcons
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.popup.*
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.CustomStatusBarWidget
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.TextPanel
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import com.intellij.ui.NewUI
import com.intellij.util.ui.JBDimension
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path

internal const val spotlessStatusBarWidgetId = "dev.ghostflyby.spotless.statusBar"

internal class SpotlessStatusBarWidgetActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val application = ApplicationManager.getApplication()
        if (application.isHeadlessEnvironment || application.isUnitTestMode) {
            return
        }
        project.service<SpotlessProjectService>().daemonStatus
            .map { snapshot -> snapshot.providers.isNotEmpty() }
            .distinctUntilChanged()
            .collect {
                withContext(Dispatchers.EDT) {
                    project.service<StatusBarWidgetsManager>()
                        .updateWidget(SpotlessStatusBarWidgetFactory::class.java)
                }
            }
    }
}

internal class SpotlessStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = spotlessStatusBarWidgetId

    override fun getDisplayName(): String = Bundle.message("status.bar.widget.display.name")

    override fun isAvailable(project: Project): Boolean =
        project.service<SpotlessProjectService>().daemonStatus.value.providers.isNotEmpty()

    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget =
        SpotlessStatusBarWidget(project, scope)
}

internal class SpotlessStatusBarWidget(
    project: Project,
    parentScope: CoroutineScope,
) : CustomStatusBarWidget {
    private val spotlessService = project.service<SpotlessProjectService>()
    private val widgetJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val widgetScope = CoroutineScope(parentScope.coroutineContext + widgetJob)
    private val widgetComponentDelegate by lazy(LazyThreadSafetyMode.NONE, ::createWidgetComponent)

    @Volatile
    private var currentSnapshot = spotlessService.daemonStatus.value
    private var popup: JBPopup? = null

    init {
        widgetScope.launch {
            spotlessService.daemonStatus.collectLatest { snapshot ->
                currentSnapshot = snapshot
            }
        }
    }

    override fun ID(): String = spotlessStatusBarWidgetId

    override fun getComponent(): TextPanel = widgetComponentDelegate

    override fun dispose() {
        popup?.cancel()
        widgetJob.cancel()
        popup = null
    }

    private fun createWidgetComponent() = TextPanel {
        Bundle.message(
            "status.bar.widget.tooltip.providers",
            currentSnapshot.providers.size,
        )
    }.apply {
        text = Bundle.message("status.bar.widget.text")
        addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1) {
                        showPopup(this@apply)
                    }
                }
            },
        )
    }

    private fun showPopup(component: Component) {
        popup?.cancel()
        val createdPopup = JBPopupFactory.getInstance()
            .createActionGroupPopup(
                Bundle.message("status.bar.widget.popup.title"),
                createSpotlessDaemonPopupActionGroup(spotlessService, currentSnapshot),
                DataManager.getInstance().getDataContext(component),
                JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
                true,
            ).apply {
                setMinimumSize(JBDimension(320, 1))
            }
        createdPopup.addListener(
            object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    if (popup === createdPopup) {
                        popup = null
                    }
                }
            },
        )
        Disposer.register(this, createdPopup)
        popup = createdPopup
        createdPopup.show(PopupShowOptions.aboveComponent(component))
    }
}

internal fun createSpotlessDaemonPopupActionGroup(
    spotlessService: SpotlessProjectService,
    snapshot: SpotlessDaemonStatusSnapshot,
): ActionGroup = DefaultActionGroup().apply {
    snapshot.providers.forEach { providerStatus ->
        addSeparator(providerStatus.presentableName)
        abbreviatedExternalProjectPaths(providerStatus.externalProjects).forEach { (externalProject, text) ->
            add(
                createExternalProjectAction(
                    spotlessService = spotlessService,
                    provider = providerStatus.provider,
                    externalProject = externalProject,
                    text = text,
                ),
            )
        }
    }
    addSeparator()
    add(RefreshSpotlessProvidersAction(spotlessService))
}

private fun createExternalProjectAction(
    spotlessService: SpotlessProjectService,
    provider: SpotlessDaemonProvider,
    externalProject: Path,
    text: String,
): AnAction {
    val restartAction = RestartSpotlessDaemonAction(spotlessService, provider, externalProject)
    val stopAction = StopSpotlessDaemonAction(spotlessService, provider, externalProject)
    if (NewUI.isEnabled()) {
        return SpotlessExternalProjectAction(
            spotlessService = spotlessService,
            provider = provider,
            externalProject = externalProject,
            text = text,
            inlineActions = listOf(restartAction, stopAction),
        )
    }
    return object : DefaultActionGroup(listOf(restartAction, stopAction)) {
        init {
            templatePresentation.text = text
            templatePresentation.description = externalProject.toString()
            templatePresentation.isPopupGroup = true
            templatePresentation.isHideGroupIfEmpty = false
            templatePresentation.isDisableGroupIfEmpty = false
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(event: AnActionEvent) {
            event.presentation.isEnabledAndVisible =
                spotlessService.findProviderStatus(provider, externalProject) != null
        }
    }
}

private class SpotlessExternalProjectAction(
    private val spotlessService: SpotlessProjectService,
    private val provider: SpotlessDaemonProvider,
    private val externalProject: Path,
    text: String,
    inlineActions: List<AnAction>,
) : DumbAwareAction(text, externalProject.toString(), null) {
    init {
        templatePresentation.putClientProperty(ActionUtil.INLINE_ACTIONS, inlineActions)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val providerStatus = spotlessService.findProviderStatus(provider, externalProject)
        event.presentation.isEnabledAndVisible = providerStatus != null
        event.presentation.putClientProperty(ActionUtil.TOOLTIP_TEXT, externalProject.toString())
        event.presentation.putClientProperty(
            ActionUtil.SECONDARY_TEXT,
            providerStatus?.runtimeStates?.get(externalProject).presentableText(),
        )
    }

    override fun actionPerformed(event: AnActionEvent) {
        spotlessService.restartDaemon(provider, externalProject)
    }
}

private class RestartSpotlessDaemonAction(
    private val spotlessService: SpotlessProjectService,
    private val provider: SpotlessDaemonProvider,
    private val externalProject: Path,
) : DumbAwareAction(
    Bundle.message("status.bar.widget.action.restart.daemon"),
    null,
    AllIcons.Actions.StopAndRestart,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible = spotlessService.findProviderStatus(provider, externalProject) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        spotlessService.restartDaemon(provider, externalProject)
    }
}

private class StopSpotlessDaemonAction(
    private val spotlessService: SpotlessProjectService,
    private val provider: SpotlessDaemonProvider,
    private val externalProject: Path,
) : DumbAwareAction(
    Bundle.message("status.bar.widget.action.stop.daemon"),
    null,
    AllIcons.Actions.Suspend,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val runtimeState = spotlessService.findProviderStatus(provider, externalProject)
            ?.runtimeStates
            ?.get(externalProject)
        event.presentation.isEnabledAndVisible = runtimeState != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        spotlessService.releaseDaemon(provider, externalProject)
    }
}

private class RefreshSpotlessProvidersAction(
    private val spotlessService: SpotlessProjectService,
) : DumbAwareAction(
    Bundle.message("status.bar.widget.action.refresh"),
    null,
    AllIcons.Actions.Refresh,
) {
    override fun actionPerformed(event: AnActionEvent) {
        spotlessService.refreshDaemonProviders()
    }
}

private fun SpotlessProjectService.findProviderStatus(
    provider: SpotlessDaemonProvider,
    externalProject: Path,
): SpotlessProviderStatus? = daemonStatus.value.providers.firstOrNull { providerStatus ->
    providerStatus.provider === provider && externalProject in providerStatus.externalProjects
}

private fun SpotlessDaemonRuntimeState?.presentableText(): String = when (this) {
    SpotlessDaemonRuntimeState.Starting -> Bundle.message("status.bar.widget.state.starting")
    SpotlessDaemonRuntimeState.Running -> Bundle.message("status.bar.widget.state.running")
    null -> Bundle.message("status.bar.widget.state.not.running")
}

internal fun abbreviatedExternalProjectPaths(paths: List<Path>): List<Pair<Path, String>> {
    if (paths.isEmpty()) {
        return emptyList()
    }
    val commonAncestor = paths.drop(1).fold(paths.first()) { common, path ->
        commonPathAncestor(common, path) ?: return@fold common
    }.takeIf { candidate -> paths.all { it.startsWith(candidate) } }
    return paths.map { path ->
        val abbreviated = commonAncestor?.let { common ->
            if (path == common) {
                path.fileName?.toString()
            } else {
                common.relativize(path).toString()
            }
        }.orEmpty().ifEmpty { path.fileName?.toString() ?: path.toString() }
        path to abbreviated
    }
}

private fun commonPathAncestor(first: Path, second: Path): Path? {
    var candidate: Path? = first
    while (candidate != null && !second.startsWith(candidate)) {
        candidate = candidate.parent
    }
    return candidate
}
