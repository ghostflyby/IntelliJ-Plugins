/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
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

package dev.ghostflyby.dcevm.agent

import com.intellij.openapi.application.PathManager.getSystemPath
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.util.io.HttpRequests
import dev.ghostflyby.dcevm.Bundle
import kotlinx.coroutines.*
import org.jetbrains.annotations.NonBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.name

private const val HOTSWAP_AGENT_JAR_FILE_NAME = "hotswap-agent.jar"
internal const val HOTSWAP_AGENT_LATEST_JAR_URL =
    "https://github.com/HotswapProjects/HotswapAgent/releases/download/RELEASE-2.0.1/hotswap-agent-2.0.1.jar"

/**
 * Manages locating and downloading HotswapAgent JAR for use in run configs and Gradle.
 */
@Service
internal class HotswapAgentManager(
    private val scope: CoroutineScope,
) {
    internal data class DownloadHooks(
        val cacheDirProvider: () -> Path = {
            Path.of(getSystemPath()).resolve("hotswap-agent")
        },
        val latestJarUrl: String = HOTSWAP_AGENT_LATEST_JAR_URL,
        val progressRunner: suspend (Project, suspend () -> Path?) -> Path? = { project, action ->
            withBackgroundProgress(project, Bundle.message("agent.downloading"), TaskCancellation.cancellable()) {
                action()
            }
        },
        val downloadRequest: suspend (String, Path) -> Unit = { url, target ->
            val indicator = ProgressManager.getGlobalProgressIndicator()
            HttpRequests.request(url)
                .productNameAsUserAgent()
                .connect { request ->
                    request.saveToFile(target, indicator)
                }
        },
    )

    @Volatile
    internal var downloadHooks: DownloadHooks = DownloadHooks()

    private val log = Logger.getInstance(HotswapAgentManager::class.java)

    private val cacheDir: Path by lazy { downloadHooks.cacheDirProvider() }
    private val agentJarPath: Path by lazy { cacheDir.resolve(HOTSWAP_AGENT_JAR_FILE_NAME) }
    @Volatile
    private var downloadJob: Deferred<Path?>? = null

    /**
     * Returns cached agent jar path if present; otherwise starts a background warm-up download.
     */
    @NonBlocking
    fun getCachedAgentJarOrWarmUp(project: Project): Path? {
        val cached = cachedAgentJarPathOrNull()
        if (cached != null) {
            return cached
        }
        getLocalAgentJarAsync(project)
        return null
    }

    internal fun cachedAgentJarPathOrNull(): Path? {
        return if (Files.isRegularFile(agentJarPath)) agentJarPath else null
    }

    /**
     * Coroutine-based background download with visible progress indicator.
     */
    @NonBlocking
    fun getLocalAgentJarAsync(project: Project): Job = synchronized(this) {
        val activeJob = downloadJob
        if (activeJob != null && activeJob.isActive) {
            return activeJob
        }
        val newJob = scope.async {
            downloadHooks.progressRunner(project) { download() }
        }
        downloadJob = newJob
        newJob.invokeOnCompletion {
            synchronized(this@HotswapAgentManager) {
                if (downloadJob === newJob) {
                    downloadJob = null
                }
            }
        }
        return newJob
    }


    /**
     * Suspending variant using coroutines and optional progress indicator.
     */
    private suspend fun download(): Path? = withContext(Dispatchers.IO) {
        if (Files.isRegularFile(agentJarPath)) return@withContext agentJarPath
        try {
            cacheDir.createDirectories()
            val tmp = Files.createTempFile(cacheDir, agentJarPath.name, ".tmp")
            downloadHooks.downloadRequest(downloadHooks.latestJarUrl, tmp)
            Files.move(
                tmp, agentJarPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
            agentJarPath
        } catch (e: IOException) {
            log.warn("Unable to download HotswapAgent", e)
            null
        }
    }

    companion object {
        fun getInstance(): HotswapAgentManager = service()
    }
}
