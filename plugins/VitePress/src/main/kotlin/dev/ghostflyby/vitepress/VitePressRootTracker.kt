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

import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.getOrCreateUserData
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.events.*
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.util.CachedValueImpl
import com.intellij.util.FileContentUtil
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentHashSetOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.intellij.plugins.markdown.lang.MarkdownFileType
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds

@Service(Service.Level.APP)
internal class VitePressRootTracker(
    scope: CoroutineScope,
) : ModificationTracker {
    private data class State(
        val current: PersistentSet<VirtualFile> = persistentHashSetOf(),
        val old: PersistentSet<VirtualFile> = persistentHashSetOf(),
    )

    private val stateRef: AtomicReference<State> =
        AtomicReference(State())
    private val modificationCount = AtomicLong(0)
    private val flushSignals =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    init {
        scope.launch {
            @OptIn(FlowPreview::class)
            flushSignals
                .debounce(ROOT_REPARSE_DEBOUNCE)
                .collect {
                    try {
                        flushPendingReparseUntilSettled()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Keep worker alive; next signal will retry diff-based reparse.
                        scheduleReparseFlush()
                    }
                }
        }
    }

    internal fun isUnderVitePressRoot(file: VirtualFile): Boolean {
        val roots = stateRef.get().current
        var d: VirtualFile? = if (file.isDirectory) file else file.parent
        while (d != null) {
            if (d in roots) return true
            d = d.parent
        }
        return false
    }

    internal fun isVitePressRoot(file: VirtualFile): Boolean {
        return file in stateRef.get().current
    }

    internal fun add(root: VirtualFile) {
        addAll(setOf(root))
    }

    internal fun remove(root: VirtualFile) {
        removeAll(setOf(root))
    }

    internal fun addAll(roots: Collection<VirtualFile>) {
        if (updateCurrent(roots, isAdd = true)) {
            scheduleReparseFlush()
        }
    }

    internal fun removeAll(roots: Collection<VirtualFile>) {
        if (updateCurrent(roots, isAdd = false)) {
            scheduleReparseFlush()
        }
    }

    private fun updateCurrent(roots: Collection<VirtualFile>, isAdd: Boolean): Boolean {
        if (roots.isEmpty()) return false
        val update: (PersistentSet<VirtualFile>) -> PersistentSet<VirtualFile> =
            if (isAdd) {
                { current -> current.addAll(roots) }
            } else {
                { current -> current.removeAll(roots) }
            }

        val previous =
            stateRef.getAndUpdate { state ->
                val nextCurrent = update(state.current)
                if (nextCurrent == state.current) state else state.copy(current = nextCurrent)
            }
        val changed = update(previous.current) != previous.current
        if (changed) {
            modificationCount.getAndIncrement()
        }
        return changed
    }


    private fun filesInRoots(project: Project, roots: List<VirtualFile>, fileType: FileType): Collection<VirtualFile> {
        if (roots.isEmpty()) return emptySet()
        val scope = GlobalSearchScopesCore.directoriesScope(project, true, *roots.toTypedArray())
        return FileTypeIndex.getFiles(fileType, scope)
    }

    private fun filterRootsInProject(
        roots: Set<VirtualFile>,
        fileIndex: ProjectFileIndex,
    ): List<VirtualFile> {
        return roots.filter { root ->
            root.isValid && fileIndex.isInContent(root)
        }
    }

    private fun scheduleReparseFlush() {
        flushSignals.tryEmit(Unit)
    }

    private suspend fun flushPendingReparseUntilSettled() {
        while (true) {
            val snapshot = stateRef.get()
            val toAdd = snapshot.current.subtract(snapshot.old)
            val toRemove = snapshot.old.subtract(snapshot.current)
            if (toAdd.isEmpty() && toRemove.isEmpty()) return

            ProjectManager.getInstance().openProjects.forEach { project ->
                if (project.isDisposed) return@forEach
                reparseInProject(project, toAdd, toRemove)
            }

            // Advance old only for the snapshot that was reparsed.
            stateRef.updateAndGet { state ->
                if (state.old == snapshot.old) state.copy(old = snapshot.current) else state
            }

            val latest = stateRef.get()
            if (latest.current == latest.old) return
        }
    }

    private suspend fun reparseInProject(
        project: Project,
        toAdd: Set<VirtualFile>,
        toRemove: Set<VirtualFile>,
    ) {
        val (addFiles, removeFiles, cacheDirsToExclude) =
            smartReadAction(project) {
                val fileIndex = ProjectFileIndex.getInstance(project)
                val addRootsInProject = filterRootsInProject(toAdd, fileIndex)
                val removeRootsInProject = filterRootsInProject(toRemove, fileIndex)
                val markdownFiles = filesInRoots(project, addRootsInProject, MarkdownFileType.INSTANCE)
                val vitePressFiles = filesInRoots(project, removeRootsInProject, VitePressFiletype)
                val cacheDirs =
                    addRootsInProject.mapNotNull { root ->
                        root.findChild(VITEPRESS_CONFIG_DIRECTORY)
                            ?.takeIf { it.isDirectory }
                            ?.findChild(VITEPRESS_CACHE_DIRECTORY)
                            ?.takeIf { it.isDirectory }
                    }
                Triple(markdownFiles, vitePressFiles, cacheDirs)
            }

        excludeCacheDirs(project, cacheDirsToExclude)
        if (addFiles.isEmpty() && removeFiles.isEmpty()) return
        reparseFiles(project, addFiles)
        reparseFiles(project, removeFiles)
    }

    private fun excludeCacheDirs(project: Project, dirs: Collection<VirtualFile>) {
        if (dirs.isEmpty() || project.isDisposed) return
        dirs.forEach { dir ->
            if (!dir.isValid) return@forEach
            val module = ModuleUtilCore.findModuleForFile(dir, project) ?: return@forEach
            WriteCommandAction.runWriteCommandAction(project) {
                val model = ModuleRootManager.getInstance(module).modifiableModel
                var committed = false
                try {
                    val contentEntry =
                        model.contentEntries.firstOrNull { entry ->
                            val contentRoot = entry.file ?: return@firstOrNull false
                            VfsUtilCore.isAncestor(contentRoot, dir, false)
                        } ?: return@runWriteCommandAction

                    if (dir.url !in contentEntry.excludeFolderUrls) {
                        contentEntry.addExcludeFolder(dir.url)
                        model.commit()
                        committed = true
                    }
                } finally {
                    if (!committed) {
                        model.dispose()
                    }
                }
            }
        }
    }

    private suspend fun reparseFiles(project: Project, files: Collection<VirtualFile>) {
        if (project.isDisposed) return
        if (files.isEmpty()) return
        files.asSequence().chunked(REPARSE_CHUNK_SIZE).forEach {
            if (project.isDisposed) return
            backgroundWriteAction {
                if (!project.isDisposed) {
                    FileContentUtil.reparseFiles(project, it, /* includeOpenFiles = */ true)
                }
            }
        }
    }

    override fun getModificationCount(): Long {
        return modificationCount.get()
    }
}

private val IS_ROOT_CACHE_KEY: Key<CachedValue<Boolean>> =
    Key.create("dev.ghostflyby.vitepress.isRootCache")
private val IS_UNDER_ROOT_CACHE_KEY: Key<CachedValue<Boolean>> =
    Key.create("dev.ghostflyby.vitepress.isUnderRootCache")

public fun VirtualFile.isUnderVitePressRoot(): Boolean {
    return getOrCreateUserData(IS_UNDER_ROOT_CACHE_KEY) {
        CachedValueImpl {
            val tracker = service<VitePressRootTracker>()
            val value = tracker.isUnderVitePressRoot(this)
            CachedValueProvider.Result.create(value, tracker)
        }
    }.value
}

public fun VirtualFile.isVitePressRoot(): Boolean {
    return getOrCreateUserData(IS_ROOT_CACHE_KEY) {
        CachedValueImpl {
            val tracker = service<VitePressRootTracker>()
            val value = tracker.isVitePressRoot(this)
            CachedValueProvider.Result.create(value, tracker)
        }
    }.value
}

internal class VitePressRootTrackerActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        val tracker = service<VitePressRootTracker>()
        val fileIndex = ProjectFileIndex.getInstance(project)
        val initialRoots =
            readAction {
                buildSet {
                    fileIndex.iterateContent { f ->
                        if (f.isDirectory && f.findChild(VITEPRESS_CONFIG_DIRECTORY)?.isDirectory == true) {
                            add(f)
                        }
                        true
                    }
                }
            }
        tracker.addAll(initialRoots)
    }
}

private const val VITEPRESS_CONFIG_DIRECTORY: String = ".vitepress"
private const val VITEPRESS_CACHE_DIRECTORY: String = "cache"
private val ROOT_REPARSE_DEBOUNCE = 250.milliseconds
private const val REPARSE_CHUNK_SIZE: Int = 100


internal class VitePressRootTrackerFileListener : AsyncFileListener {

    override fun prepareChange(events: List<VFileEvent>): AsyncFileListener.ChangeApplier? {
        val toAdd = mutableSetOf<VirtualFile>()
        val toRemove = mutableSetOf<VirtualFile>()
        fun add(file: VirtualFile) {
            toAdd.add(file)
            toRemove.remove(file)
        }

        fun remove(file: VirtualFile) {
            toRemove.add(file)
            toAdd.remove(file)
        }

        events.forEach {
            when (it) {
                is VFileCreateEvent if it.childName == VITEPRESS_CONFIG_DIRECTORY && it.isDirectory -> {
                    add(it.parent)
                }

                is VFileCopyEvent if (it.newChildName == VITEPRESS_CONFIG_DIRECTORY) -> {
                    add(it.newParent)
                }

                is VFileDeleteEvent -> {
                    val f = it.file
                    if (f.isDirectory && f.name == VITEPRESS_CONFIG_DIRECTORY) {
                        remove(f.parent)
                    }
                }

                is VFileMoveEvent -> {
                    val f = it.file
                    if (f.isDirectory && f.name == VITEPRESS_CONFIG_DIRECTORY) {
                        remove(it.oldParent)
                        add(it.newParent)
                    }
                }

                is VFilePropertyChangeEvent -> {
                    if (VirtualFile.PROP_NAME == it.propertyName && it.file.isDirectory) {
                        val oldName = it.oldValue as? String
                        val newName = it.newValue as? String
                        if (oldName == VITEPRESS_CONFIG_DIRECTORY) remove(it.file.parent)
                        if (newName == VITEPRESS_CONFIG_DIRECTORY) it.file.parent?.let { parent -> add(parent) }
                    }
                }
            }
        }

        if (toAdd.isEmpty() && toRemove.isEmpty())
            return null

        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                val tracker = service<VitePressRootTracker>()
                tracker.removeAll(toRemove)
                tracker.addAll(toAdd)
            }
        }
    }
}
