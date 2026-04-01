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

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.UI
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runWriteAction
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.intellij.plugins.markdown.lang.MarkdownFileType
import org.jetbrains.vuejs.lang.html.VueFileType
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

@Service(Service.Level.APP)
@State(
    name = "VitePressMdFileTypeWorkaroundSettings",
    storages = [Storage("vitepress.xml", roamingType = RoamingType.DISABLED)],
)
internal class VitePressMdFileTypeWorkaroundSettings :
    SerializablePersistentStateComponent<VitePressMdFileTypeWorkaroundSettings.State>(State()),
    Disposable {

    init {
        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        @Suppress("USELESS_CAST")
        connection.subscribe(
            FileTypeManager.TOPIC as Topic<FileTypeListener>,
            object : FileTypeListener {
                override fun fileTypesChanged(event: com.intellij.openapi.fileTypes.FileTypeEvent) {
                    clearTrackedAssociationIfWorkaroundInactive()
                }
            },
        )
        connection.subscribe(
            DynamicPluginListener.TOPIC,
            object : DynamicPluginListener {
                override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
                    if (isUpdate || pluginDescriptor.pluginId.idString != VITEPRESS_PLUGIN_ID) {
                        return
                    }
                    restoreTrackedAssociationForPluginUnload()
                }
            },
        )
    }

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
        val targetFileType = if (enabled) VueFileType else resolveRestoreFileType(fileTypeManager)
        val previousAssociation = currentMdAssociation(fileTypeManager)
        val changed =
            runWriteAction {
                fileTypeManager.setMdAssociation(targetFileType)
            }
        if (!changed) {
            if (!enabled) {
                clearTrackedAssociation()
            }
            return
        }
        if (enabled) {
            updateState {
                it.copy(
                    previousMdAssociationName = previousAssociation.fileTypeName,
                    previousMdAssociationWasUnknown = previousAssociation.isUnknown,
                )
            }
        } else {
            clearTrackedAssociation()
        }
        FileContentUtil.reparseOpenedFiles()
    }

    override fun dispose() = Unit

    private fun restoreTrackedAssociationForPluginUnload() {
        if (!isVueLanguageServiceWorkaroundEnabled || !hasTrackedAssociation()) {
            return
        }

        val fileTypeManager = FileTypeManager.getInstance()
        val restoreFileType = resolveRestoreFileType(fileTypeManager)
        val changed =
            runWriteAction {
                fileTypeManager.setMdAssociation(restoreFileType)
            }
        clearTrackedAssociation()
        if (changed) {
            FileContentUtil.reparseOpenedFiles()
        }
    }

    private fun clearTrackedAssociationIfWorkaroundInactive() {
        if (!isVueLanguageServiceWorkaroundEnabled && hasTrackedAssociation()) {
            clearTrackedAssociation()
        }
    }

    private fun clearTrackedAssociation() {
        if (!hasTrackedAssociation()) {
            return
        }
        updateState { it.copy(previousMdAssociationName = null, previousMdAssociationWasUnknown = false) }
    }

    private fun hasTrackedAssociation(): Boolean {
        return state.previousMdAssociationName != null || state.previousMdAssociationWasUnknown
    }

    private fun resolveRestoreFileType(fileTypeManager: FileTypeManager): FileType {
        if (state.previousMdAssociationWasUnknown) {
            return UnknownFileType.INSTANCE
        }

        val previousName = state.previousMdAssociationName ?: return MarkdownFileType.INSTANCE
        return fileTypeManager.findFileTypeByName(previousName) ?: MarkdownFileType.INSTANCE
    }

    private fun currentMdAssociation(fileTypeManager: FileTypeManager): MdAssociationSnapshot {
        val currentFileType = fileTypeManager.getFileTypeByExtension(MARKDOWN_EXTENSION)
        return if (currentFileType == UnknownFileType.INSTANCE) {
            MdAssociationSnapshot(fileTypeName = null, isUnknown = true)
        } else {
            MdAssociationSnapshot(fileTypeName = currentFileType.name, isUnknown = false)
        }
    }

    internal data class State(
        @JvmField val previousMdAssociationName: String? = null,
        @JvmField val previousMdAssociationWasUnknown: Boolean = false,
    )

    private data class MdAssociationSnapshot(
        val fileTypeName: String?,
        val isUnknown: Boolean,
    )
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

@Service(Service.Level.APP)
@State(
    name = "VitePressWorkaroundNotificationState",
    storages = [Storage("vitepress.xml", roamingType = RoamingType.DISABLED)],
)
internal class VitePressWorkaroundNotificationService(
    private val scope: CoroutineScope,
) : SerializablePersistentStateComponent<VitePressWorkaroundNotificationService.State>(State()) {
    private val notificationMutex = Mutex()

    internal fun notifyAboutUnconfiguredRoots(
        project: Project,
        roots: Collection<VirtualFile>,
        rootsKnownInProject: Boolean = false,
    ) {
        if (roots.isEmpty()) {
            return
        }
        scope.launch {
            val settings = service<VitePressMdFileTypeWorkaroundSettings>()
            if (project.isDisposed || settings.isVueLanguageServiceWorkaroundEnabled || state.hasShownNotification) {
                return@launch
            }
            val hasRelevantRoots =
                if (rootsKnownInProject) {
                    readAction { roots.any(VirtualFile::isValid) }
                } else {
                    readAction { hasRootsInProject(project, roots) }
                }
            if (!hasRelevantRoots) {
                return@launch
            }
            val shouldShowNotification =
                notificationMutex.withLock {
                    if (state.hasShownNotification) {
                        false
                    } else {
                        updateState { current ->
                            current.copy(hasShownNotification = true)
                        }
                        true
                    }
                }
            if (!shouldShowNotification) {
                return@launch
            }
            withContext(Dispatchers.UI) {
                showWorkaroundNotification(project)
            }
        }
    }

    private fun hasRootsInProject(project: Project, roots: Collection<VirtualFile>): Boolean {
        val fileIndex = ProjectFileIndex.getInstance(project)
        return roots.any { root -> root.isValid && fileIndex.isInContent(root) }
    }

    private fun showWorkaroundNotification(project: Project) {
        val notification =
            NotificationGroupManager.getInstance()
                .getNotificationGroup(VITEPRESS_NOTIFICATION_GROUP_ID)
                .createNotification(
                    Bundle.message("notification.vueLsp.title"),
                    Bundle.message("notification.vueLsp.content"),
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
        @JvmField val hasShownNotification: Boolean = false,
    )
}

internal fun notifyAboutVitePressRoots(
    project: Project,
    roots: Collection<VirtualFile>,
    rootsKnownInProject: Boolean = false,
) {
    service<VitePressWorkaroundNotificationService>()
        .notifyAboutUnconfiguredRoots(project, roots, rootsKnownInProject)
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
    if (targetFileType == UnknownFileType.INSTANCE) {
        return currentFileType != UnknownFileType.INSTANCE
    }
    removeAssociatedExtension(targetFileType, MARKDOWN_EXTENSION)
    associateExtension(targetFileType, MARKDOWN_EXTENSION)
    return true
}

private const val MARKDOWN_EXTENSION: String = "md"
private const val VITEPRESS_PLUGIN_ID: String = "dev.ghostflyby.vitepress"
internal const val VITEPRESS_NOTIFICATION_GROUP_ID: String = "VitePress"
