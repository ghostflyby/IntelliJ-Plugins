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

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.util.FileContentUtil
import com.intellij.util.messages.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
internal class VitePressRootTracker {
    companion object {
        @JvmField
        @Topic.AppLevel
        // TODO: fire events in batch if multiple roots added/removed together, maybe with a delay to coalesce multiple events together
        val TOPIC: Topic<RootChangeListener> = Topic.create("VitePressRootTracker", RootChangeListener::class.java)
    }

    internal fun isUnderVitePressRoot(file: VirtualFile): Boolean {
        var d: VirtualFile? = if (file.isDirectory) file else file.parent
        while (d != null) {
            if (d in roots) return true
            d = d.parent
        }
        return false
    }

    internal fun isVitePressRoot(file: VirtualFile): Boolean {
        return file in roots
    }

    private val roots: MutableSet<VirtualFile> = ConcurrentHashMap.newKeySet<VirtualFile>()

    internal fun add(root: VirtualFile) {
        if (!roots.add(root)) return
        val bus = ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC)
        bus.onRootAdded(root)
    }

    internal fun remove(root: VirtualFile) {
        if (!roots.remove(root)) return
        val bus = ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC)
        bus.onRootRemoved(root)
    }

    interface RootChangeListener {
        fun onRootAdded(root: VirtualFile)
        fun onRootRemoved(root: VirtualFile)
    }
}

public fun VirtualFile.isUnderVitePressRoot(): Boolean {
    return service<VitePressRootTracker>().isUnderVitePressRoot(this)
}

public fun VirtualFile.isVitePressRoot(): Boolean {
    return service<VitePressRootTracker>().isVitePressRoot(this)
}

internal class VitePressRootTrackerActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.messageBus.connect(PluginDisposable).subscribe(
            VitePressRootTracker.TOPIC,
            object : VitePressRootTracker.RootChangeListener {
                override fun onRootAdded(root: VirtualFile) {
                    project.service<VitePressReparseScheduler>().reparseUnder(root, VitePressFiletype)
                }

                override fun onRootRemoved(root: VirtualFile) {
                    project.service<VitePressReparseScheduler>().reparseUnder(root, VitePressFiletype)
                }
            },
        )
        val fileIndex = ProjectFileIndex.getInstance(project)
        readAction {
            fileIndex.iterateContent { f ->
                if (f.isDirectory && f.findChild(VITEPRESS_CONFIG_DIRECTORY)?.isDirectory == true) {
                    service<VitePressRootTracker>().add(f)
                }
                true
            }
        }
    }
}

@Service(Service.Level.PROJECT)
internal class VitePressReparseScheduler(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    // TODO: chunked reparse if too many files
    fun reparseUnder(root: VirtualFile, fileType: FileType) {
        scope.launch {
            val files: List<VirtualFile> =
                smartReadAction(project) {
                    val dirScope = GlobalSearchScopesCore.directoryScope(project, root, true)
                    FileTypeIndex.getFiles(fileType, dirScope).toList()
                }

            if (files.isEmpty()) return@launch

            backgroundWriteAction {
                FileContentUtil.reparseFiles(project, files, /*includeOpenFiles=*/true)
            }
        }
    }
}

private const val VITEPRESS_CONFIG_DIRECTORY: String = ".vitepress"


private fun removeIfVitePressDir(pathParent: VirtualFile?) {
    val roots = service<VitePressRootTracker>()
    if (pathParent != null) roots.remove(pathParent)
}

internal class VitePressRootTrackerFileListener : AsyncFileListener {

    override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
        val roots = service<VitePressRootTracker>()
        val list = events.map {
            when (it) {
                is VFileCreateEvent if it.childName == VITEPRESS_CONFIG_DIRECTORY && it.isDirectory -> {
                    { roots.add(it.parent) }
                }

                is VFileCopyEvent if (it.newChildName == VITEPRESS_CONFIG_DIRECTORY) -> {
                    { roots.add(it.newParent) }
                }

                is VFileDeleteEvent -> {
                    val f = it.file
                    if (f.isDirectory && f.name == VITEPRESS_CONFIG_DIRECTORY) {
                        { removeIfVitePressDir(f.parent) }
                    } else {
                        {}
                    }
                }

                is VFileMoveEvent -> {
                    val f = it.file
                    if (f.isDirectory && f.name == VITEPRESS_CONFIG_DIRECTORY) {
                        {
                            removeIfVitePressDir(it.oldParent)
                            roots.add(it.newParent)
                        }
                    } else {
                        {}
                    }
                }

                is VFilePropertyChangeEvent -> {
                    if (VirtualFile.PROP_NAME == it.propertyName && it.file.isDirectory) {
                        val oldName = it.oldValue as? String
                        val newName = it.newValue as? String
                        {
                            if (oldName == VITEPRESS_CONFIG_DIRECTORY) removeIfVitePressDir(it.file.parent)
                            if (newName == VITEPRESS_CONFIG_DIRECTORY) it.file.parent?.let { roots.add(it) }
                        }
                    } else {
                        {}
                    }
                }

                else -> {
                    {}
                }
            }
        }

        if (list.isEmpty())
            return null

        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                list.forEach { it() }
            }
        }
    }
}
