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
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.util.NotNullLazyKey
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
import dev.ghostflyby.intellij.getValue
import dev.ghostflyby.intellij.toAutoCleanKey
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
        val pendingFiles: PersistentSet<VirtualFile> = persistentHashSetOf(),
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
                        // Keep worker alive; next signal will retry pending reparse work.
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

    internal fun queueFilesForReparse(files: Collection<VirtualFile>) {
        if (updatePendingFiles(files)) {
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

    private fun updatePendingFiles(files: Collection<VirtualFile>): Boolean {
        if (files.isEmpty()) return false
        val previous =
            stateRef.getAndUpdate { state ->
                val nextPendingFiles = state.pendingFiles.addAll(files)
                if (nextPendingFiles == state.pendingFiles) state else state.copy(pendingFiles = nextPendingFiles)
            }
        val changed = previous.pendingFiles.addAll(files) != previous.pendingFiles
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
            val pendingFiles = snapshot.pendingFiles
            if (toAdd.isEmpty() && toRemove.isEmpty() && pendingFiles.isEmpty()) return

            ProjectManager.getInstance().openProjects.forEach { project ->
                if (project.isDisposed) return@forEach
                reparseInProject(project, toAdd, toRemove, pendingFiles)
            }

            stateRef.updateAndGet { state ->
                var nextState = state
                if (state.old == snapshot.old) {
                    nextState = nextState.copy(old = nextState.current)
                }
                val remainingPendingFiles = nextState.pendingFiles.removeAll(pendingFiles)
                if (remainingPendingFiles != nextState.pendingFiles) {
                    nextState = nextState.copy(pendingFiles = remainingPendingFiles)
                }
                nextState
            }

            val latest = stateRef.get()
            if (latest.current == latest.old && latest.pendingFiles.isEmpty()) return
        }
    }

    private suspend fun reparseInProject(
        project: Project,
        toAdd: Set<VirtualFile>,
        toRemove: Set<VirtualFile>,
        pendingFiles: Set<VirtualFile>,
    ) {
        val (filesToReparse, cacheDirsToExclude) =
            smartReadAction(project) {
                val fileIndex = ProjectFileIndex.getInstance(project)
                val addRootsInProject = filterRootsInProject(toAdd, fileIndex)
                val removeRootsInProject = filterRootsInProject(toRemove, fileIndex)
                val markdownFiles = filesInRoots(project, addRootsInProject, MarkdownFileType.INSTANCE)
                val vitePressFiles = filesInRoots(project, removeRootsInProject, VitePressFiletype)
                val pendingFilesInProject =
                    pendingFiles.filterTo(LinkedHashSet()) { file ->
                        file.isValid && fileIndex.isInContent(file)
                    }
                val cacheDirs =
                    addRootsInProject.mapNotNull { root ->
                        root.findChild(VITEPRESS_CONFIG_DIRECTORY)
                            ?.takeIf { it.isDirectory }
                            ?.findChild(VITEPRESS_CACHE_DIRECTORY)
                            ?.takeIf { it.isDirectory }
                    }
                Pair(
                    buildSet {
                        addAll(markdownFiles)
                        addAll(vitePressFiles)
                        addAll(pendingFilesInProject)
                    },
                    cacheDirs,
                )
            }

        excludeCacheDirs(project, cacheDirsToExclude)
        reparseFiles(project, filesToReparse)
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

private val isRootCacheKey =
    NotNullLazyKey.createLazyKey<CachedValue<Boolean>, VirtualFile>("dev.ghostflyby.vitepress.isRootCache") {
        CachedValueImpl {
            val tracker = service<VitePressRootTracker>()
            val value = tracker.isVitePressRoot(it)
            CachedValueProvider.Result.create(value, tracker, it)
        }
    }.toAutoCleanKey { PluginDisposable }

private val isUnderRootCacheKey =
    NotNullLazyKey.createLazyKey<CachedValue<Boolean>, VirtualFile>("dev.ghostflyby.vitepress.isUnderRootCache") {
        CachedValueImpl {
            val tracker = service<VitePressRootTracker>()
            val value = tracker.isUnderVitePressRoot(it)
            CachedValueProvider.Result.create(value, tracker, it)
        }
    }.toAutoCleanKey { PluginDisposable }

public val VirtualFile.isVitePressRoot: CachedValue<Boolean> by isRootCacheKey
public val VirtualFile.isUnderVitePressRoot: CachedValue<Boolean> by isUnderRootCacheKey

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
        notifyAboutVitePressRoots(project, initialRoots, rootsKnownInProject = true)
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
        val reparseResolvers = mutableListOf<() -> Set<VirtualFile>>()

        fun scheduleReparse(fileProvider: () -> VirtualFile?) {
            reparseResolvers += {
                fileProvider()?.takeIf(VirtualFile::isValid)?.let(::collectFilesWithVitePressState).orEmpty()
            }
        }

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

                is VFileCreateEvent if hasVitePressFileState(it.childName, it.parent.isUnderVitePressRoot.value) -> {
                    scheduleReparse { it.parent.findChild(it.childName) }
                }

                is VFileCopyEvent if (it.newChildName == VITEPRESS_CONFIG_DIRECTORY) -> {
                    add(it.newParent)
                }

                is VFileCopyEvent if (
                        (it.file.isDirectory && it.newParent.isUnderVitePressRoot.value) ||
                                hasVitePressFileState(it.newChildName, it.newParent.isUnderVitePressRoot.value)
                        ) -> {
                    scheduleReparse { it.newParent.findChild(it.newChildName) }
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
                    } else if (f.isDirectory) {
                        val oldUnderRoot = it.oldParent.isUnderVitePressRoot.value
                        val newUnderRoot = it.newParent.isUnderVitePressRoot.value
                        if (oldUnderRoot != newUnderRoot) {
                            scheduleReparse { f }
                        }
                    } else {
                        if (
                            shouldReparseFileAfterStateTransition(
                                oldFileName = f.name,
                                oldParentIsUnderVitePressRoot = it.oldParent.isUnderVitePressRoot.value,
                                newFileName = f.name,
                                newParentIsUnderVitePressRoot = it.newParent.isUnderVitePressRoot.value,
                            )
                        ) {
                            scheduleReparse { f }
                        }
                    }
                }

                is VFilePropertyChangeEvent -> {
                    if (VirtualFile.PROP_NAME == it.propertyName && it.file.isDirectory) {
                        val oldName = it.oldValue as? String
                        val newName = it.newValue as? String
                        if (oldName == VITEPRESS_CONFIG_DIRECTORY) remove(it.file.parent)
                        if (newName == VITEPRESS_CONFIG_DIRECTORY) it.file.parent?.let { parent -> add(parent) }
                    } else if (VirtualFile.PROP_NAME == it.propertyName && !it.file.isDirectory) {
                        val oldName = it.oldValue as? String
                        val newName = it.newValue as? String
                        val parentIsUnderRoot = it.file.parent?.isUnderVitePressRoot?.value == true
                        if (
                            oldName != null &&
                            newName != null &&
                            shouldReparseFileAfterStateTransition(
                                oldFileName = oldName,
                                oldParentIsUnderVitePressRoot = parentIsUnderRoot,
                                newFileName = newName,
                                newParentIsUnderVitePressRoot = parentIsUnderRoot,
                            )
                        ) {
                            scheduleReparse { it.file }
                        }
                    }
                }
            }
        }

        if (toAdd.isEmpty() && toRemove.isEmpty() && reparseResolvers.isEmpty())
            return null

        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                val tracker = service<VitePressRootTracker>()
                tracker.removeAll(toRemove)
                tracker.addAll(toAdd)
                tracker.queueFilesForReparse(reparseResolvers.flatMapTo(LinkedHashSet()) { it() })
                ProjectManager.getInstance().openProjects.forEach { project ->
                    if (!project.isDisposed) {
                        notifyAboutVitePressRoots(project, toAdd)
                    }
                }
            }
        }
    }
}

private fun collectFilesWithVitePressState(file: VirtualFile): Set<VirtualFile> {
    if (!file.isValid) return emptySet()
    if (!file.isDirectory) {
        return if (hasVitePressFileName(file.name)) setOf(file) else emptySet()
    }
    return buildSet {
        VfsUtilCore.iterateChildrenRecursively(file, null) { child ->
            if (!child.isDirectory && hasVitePressFileName(child.name)) {
                add(child)
            }
            true
        }
    }
}

internal fun shouldReparseFileAfterStateTransition(
    oldFileName: String,
    oldParentIsUnderVitePressRoot: Boolean,
    newFileName: String,
    newParentIsUnderVitePressRoot: Boolean,
): Boolean {
    return hasVitePressFileState(oldFileName, oldParentIsUnderVitePressRoot) !=
            hasVitePressFileState(newFileName, newParentIsUnderVitePressRoot)
}

internal fun hasVitePressFileState(fileName: String, isUnderVitePressRoot: Boolean): Boolean {
    return isUnderVitePressRoot && hasVitePressFileName(fileName)
}

private fun hasVitePressFileName(name: String): Boolean {
    return name.endsWith(VITEPRESS_FILE_EXTENSION, ignoreCase = true)
}

private const val VITEPRESS_FILE_EXTENSION: String = ".md"
