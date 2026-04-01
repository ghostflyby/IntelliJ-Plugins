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

package dev.ghostflyby.vitepress

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.UI
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.*
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.FileContentUtil
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.intellij.plugins.markdown.lang.MarkdownFileType
import org.jetbrains.vuejs.lang.html.VueFileType
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

@Service(Service.Level.APP)
internal class VitePressMdFileTypeWorkaroundSettings : Disposable.Default {

    var isVueLanguageServiceWorkaroundEnabled: Boolean
        get() = FileTypeManager.getInstance().isVueMdAssociationEnabled()
        set(value) {
            if (isVueLanguageServiceWorkaroundEnabled == value) {
                return
            }
            setMdAssociationEnabled(value)
        }

    private fun setMdAssociationEnabled(enabled: Boolean) {
        val fileTypeManager = FileTypeManager.getInstance()
        val targetFileType =
            if (enabled) {
                VueFileType
            } else {
                MarkdownFileType.INSTANCE
            }
        if (!fileTypeManager.setMdAssociation(targetFileType)) {
            return
        }
        FileContentUtil.reparseOpenedFiles()
    }
}

internal class VitePressApplicationConfigurable : BoundConfigurable(Bundle.message("configuration.title")) {
    private val settings = service<VitePressMdFileTypeWorkaroundSettings>()

    override fun createPanel() = panel {
        lateinit var checkBoxComponent: JBCheckBox
        row {
            checkBox(Bundle.message("configuration.vueLsp.checkbox"))
                .bindSelected(settings::isVueLanguageServiceWorkaroundEnabled)
                .also { checkBoxComponent = it.component }
        }
        row {
            cell(createWorkaroundDescriptionPane())
        }

        val uiDisposable = requireNotNull(disposable)
        @Suppress("USELESS_CAST")
        ApplicationManager.getApplication().messageBus.connect(uiDisposable).subscribe(
            FileTypeManager.TOPIC as Topic<FileTypeListener>,
            object : FileTypeListener {
                override fun fileTypesChanged(event: com.intellij.openapi.fileTypes.FileTypeEvent) {
                    ApplicationManager.getApplication().invokeLater {
                        checkBoxComponent.isSelected = settings.isVueLanguageServiceWorkaroundEnabled
                    }
                }
            },
        )
    }
}

private fun createWorkaroundDescriptionPane(): JEditorPane {
    return JEditorPane("text/html", Bundle.message("configuration.vueLsp.description.html")).apply {
        isEditable = false
        isOpaque = false
        background = JBColor.background()
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                event.url?.let { com.intellij.ide.BrowserUtil.browse(it) }
            }
        }
    }
}

@Service(Service.Level.PROJECT)
@State(
    name = "VitePressWorkaroundNotificationState",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE, roamingType = RoamingType.DISABLED)],
)
internal class VitePressWorkaroundNotificationService(
    private val project: Project,
    private val scope: CoroutineScope,
) : SerializablePersistentStateComponent<VitePressWorkaroundNotificationService.State>(State()) {

    internal fun notifyAboutUnconfiguredRoots(roots: Collection<VirtualFile>) {
        if (roots.isEmpty()) {
            return
        }
        scope.launch {
            val settings = service<VitePressMdFileTypeWorkaroundSettings>()
            if (project.isDisposed || settings.isVueLanguageServiceWorkaroundEnabled) {
                return@launch
            }
            val rootsInProject = readAction { collectRootsInProject(roots) }
            if (rootsInProject.isEmpty()) {
                return@launch
            }
            val newRoots = rootsInProject.filterNot { it.url in state.notifiedRootUrls }
            if (newRoots.isEmpty()) {
                return@launch
            }
            updateState { current ->
                current.copy(
                    notifiedRootUrls = LinkedHashSet(current.notifiedRootUrls).apply {
                        addAll(newRoots.map(RootPresentation::url))
                    },
                )
            }
            withContext(Dispatchers.UI) {
                showWorkaroundNotification(newRoots)
            }
        }
    }

    private fun collectRootsInProject(roots: Collection<VirtualFile>): List<RootPresentation> {
        val fileIndex = ProjectFileIndex.getInstance(project)
        return roots.asSequence()
            .filter { root -> root.isValid && fileIndex.isInContent(root) }
            .map { root -> RootPresentation(root.url, root.presentableUrl) }
            .distinctBy(RootPresentation::url)
            .sortedBy(RootPresentation::presentablePath)
            .toList()
    }

    private fun showWorkaroundNotification(roots: List<RootPresentation>) {
        val content =
            if (roots.size == 1) {
                Bundle.message("notification.vueLsp.content.single", roots.single().presentablePath)
            } else {
                Bundle.message("notification.vueLsp.content.multiple", roots.size)
            }
        val notification =
            NotificationGroupManager.getInstance()
                .getNotificationGroup(VITEPRESS_NOTIFICATION_GROUP_ID)
                .createNotification(
                    Bundle.message("notification.vueLsp.title"),
                    content,
                    NotificationType.INFORMATION,
                )
        notification.addAction(
            NotificationAction.createSimpleExpiring(Bundle.message("notification.vueLsp.action.enable")) {
                service<VitePressMdFileTypeWorkaroundSettings>().isVueLanguageServiceWorkaroundEnabled = true
            },
        )
        notification.addAction(
            NotificationAction.createSimpleExpiring(Bundle.message("notification.vueLsp.action.settings")) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, VitePressApplicationConfigurable::class.java)
            },
        )
        notification.notify(project)
    }

    internal data class State(
        @JvmField val notifiedRootUrls: MutableSet<String> = linkedSetOf(),
    )

    private data class RootPresentation(
        val url: String,
        val presentablePath: String,
    )
}

internal fun notifyAboutVitePressRoots(project: Project, roots: Collection<VirtualFile>) {
    project.service<VitePressWorkaroundNotificationService>().notifyAboutUnconfiguredRoots(roots)
}

private fun FileTypeManager.isVueMdAssociationEnabled(): Boolean {
    return getFileTypeByExtension(MARKDOWN_EXTENSION) == VueFileType
}

private fun FileTypeManager.setMdAssociation(targetFileType: FileType): Boolean {
    val currentFileType = getFileTypeByExtension(MARKDOWN_EXTENSION)
    if (currentFileType == targetFileType) {
        return false
    }
    if (currentFileType != UnknownFileType.INSTANCE) {
        removeAssociatedExtension(currentFileType, MARKDOWN_EXTENSION)
    }
    removeAssociatedExtension(targetFileType, MARKDOWN_EXTENSION)
    associateExtension(targetFileType, MARKDOWN_EXTENSION)
    return true
}

private const val MARKDOWN_EXTENSION: String = "md"
internal const val VITEPRESS_NOTIFICATION_GROUP_ID: String = "VitePress"
