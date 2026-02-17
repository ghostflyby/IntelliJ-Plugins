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

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.*
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.APP)
internal class VitePressRootTracker {

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

    private val roots: ConcurrentHashMap.KeySetView<VirtualFile, Boolean> = ConcurrentHashMap.newKeySet<VirtualFile>()

    internal fun add(root: VirtualFile) {
        roots.add(root)
    }

    internal fun remove(root: VirtualFile) {
        roots.remove(root)
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

private const val VITEPRESS_CONFIG_DIRECTORY: String = ".vitepress"


private fun removeIfVitepressDir(pathParent: VirtualFile?) {
    val roots = service<VitePressRootTracker>()
    if (pathParent != null) roots.remove(pathParent)
}

internal class VitePressRootTrackerFileListener : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        val roots = service<VitePressRootTracker>()
        for (e in events) {
            when (e) {
                is VFileCreateEvent if e.childName == VITEPRESS_CONFIG_DIRECTORY && e.isDirectory -> {
                    roots.add(e.parent)
                }

                is VFileCopyEvent if (e.newChildName == VITEPRESS_CONFIG_DIRECTORY) -> {
                    roots.add(e.newParent)
                }

                is VFileDeleteEvent -> {
                    val f = e.file
                    if (f.isDirectory && f.name == VITEPRESS_CONFIG_DIRECTORY) {
                        removeIfVitepressDir(f.parent)
                    }
                }

                is VFileMoveEvent -> {
                    val f = e.file
                    if (f.isDirectory && f.name == VITEPRESS_CONFIG_DIRECTORY) {
                        removeIfVitepressDir(e.oldParent)
                        roots.add(e.newParent)
                    }
                }

                is VFilePropertyChangeEvent -> {
                    if (VirtualFile.PROP_NAME == e.propertyName) {
                        val f = e.file
                        if (f.isDirectory) {
                            val oldName = e.oldValue as? String
                            val newName = e.newValue as? String
                            if (oldName == VITEPRESS_CONFIG_DIRECTORY) removeIfVitepressDir(f.parent)
                            if (newName == VITEPRESS_CONFIG_DIRECTORY) f.parent?.let { roots.add(it) }
                        }
                    }
                }
            }
        }
    }
}
