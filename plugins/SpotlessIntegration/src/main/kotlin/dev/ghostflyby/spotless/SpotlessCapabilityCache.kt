/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.openapi.components.Service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import dev.ghostflyby.spotless.SpotlessFormatResult.*
import kotlinx.coroutines.Job
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.io.path.absolutePathString

@Service(Service.Level.PROJECT)
internal class SpotlessCapabilityCache {
    data class CachedCanFormat(
        val canFormat: Boolean,
        val shouldRefresh: Boolean,
    )

    private enum class CachedState {
        StrictlyFormattable,
        StrictlyNotFormattable,
        RetryableMiss,
    }

    private data class CacheKey(
        val filePath: String,
    )

    private data class CachedCapability(
        val state: CachedState,
        val virtualFileModificationStamp: Long,
        val externalProjectPath: String?,
    )

    private val cache = ConcurrentHashMap<CacheKey, CachedCapability>()
    private val refreshJobs = ConcurrentHashMap<CacheKey, Job>()
    private val revision = AtomicLong()

    fun currentRevision(): Long = revision.get()

    fun cachedCanFormat(virtualFile: VirtualFile): CachedCanFormat? {
        val key = key(virtualFile) ?: return CachedCanFormat(canFormat = false, shouldRefresh = false)
        val cached = cache[key]
        if (cached == null || cached.virtualFileModificationStamp != virtualFile.modificationStamp) {
            return null
        }
        return when (cached.state) {
            CachedState.StrictlyFormattable -> CachedCanFormat(canFormat = true, shouldRefresh = true)
            CachedState.StrictlyNotFormattable -> CachedCanFormat(canFormat = false, shouldRefresh = false)
            CachedState.RetryableMiss -> CachedCanFormat(canFormat = false, shouldRefresh = true)
        }
    }

    fun tryStartRefresh(virtualFile: VirtualFile, job: Job): Boolean {
        val key = key(virtualFile) ?: return false
        return refreshJobs.putIfAbsent(key, job) == null
    }

    fun finishRefresh(virtualFile: VirtualFile, job: Job) {
        key(virtualFile)?.let { key -> refreshJobs.remove(key, job) }
    }

    fun update(
        virtualFile: VirtualFile,
        externalProject: Path?,
        result: SpotlessFormatResult,
        strictProbe: Boolean,
        expectedRevision: Long? = null,
    ) {
        if (!strictProbe || expectedRevision != null && expectedRevision != revision.get()) {
            return
        }
        val key = key(virtualFile) ?: return
        val externalProjectPath = externalProject?.normalize()?.absolutePathString()
        when (result) {
            Clean -> cache[key] = CachedCapability(
                state = CachedState.StrictlyFormattable,
                virtualFileModificationStamp = virtualFile.modificationStamp,
                externalProjectPath = externalProjectPath,
            )

            is Dirty -> cache[key] = CachedCapability(
                state = CachedState.StrictlyNotFormattable,
                virtualFileModificationStamp = virtualFile.modificationStamp,
                externalProjectPath = externalProjectPath,
            )

            NotCovered -> cache[key] = CachedCapability(
                state = CachedState.RetryableMiss,
                virtualFileModificationStamp = virtualFile.modificationStamp,
                externalProjectPath = externalProjectPath,
            )

            is Error -> cache.remove(key)
        }
    }

    fun invalidate(virtualFile: VirtualFile) {
        val key = key(virtualFile) ?: return
        revision.incrementAndGet()
        refreshJobs.remove(key)?.cancel()
        cache.remove(key)
    }

    fun invalidateExternalProject(externalProjectPath: String) {
        revision.incrementAndGet()
        refreshJobs.values.forEach(Job::cancel)
        refreshJobs.clear()
        val keys = cache.entries
            .filter { (_, cached) ->
                cached.externalProjectPath == externalProjectPath
            }
            .map { it.key }
        keys.forEach { key ->
            cache.remove(key)
        }
    }

    fun clear() {
        revision.incrementAndGet()
        cache.clear()
        refreshJobs.values.forEach(Job::cancel)
        refreshJobs.clear()
    }

    private fun key(virtualFile: VirtualFile): CacheKey? {
        val filePath = virtualFile.toNioPathOrNull()?.normalize()?.absolutePathString() ?: return null
        return CacheKey(filePath)
    }
}
