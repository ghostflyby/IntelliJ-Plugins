/*
 * Copyright (c) 2025 ghostflyby <ghostflyby+intellij@outlook.com>
 *
 * This program is free software; you can redistribute it and/or
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
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.ModalTaskOwner
import com.intellij.platform.ide.progress.TaskCancellation
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.ide.progress.withModalProgress
import com.intellij.util.io.HttpRequests
import dev.ghostflyby.dcevm.Bundle
import kotlinx.coroutines.*
import org.jetbrains.annotations.Blocking
import org.jetbrains.annotations.NonBlocking
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.name

/**
 * Manages locating and downloading HotswapAgent JAR for use in run configs and Gradle.
 */
@Service
internal class HotswapAgentManager(private val scope: CoroutineScope) {
    private val log = Logger.getInstance(HotswapAgentManager::class.java)

    // GitHub provides a stable redirect for the latest agent jar
    private val latestJarUrl =
        "https://github.com/HotswapProjects/HotswapAgent/releases/download/RELEASE-2.0.1/hotswap-agent-2.0.1.jar"

    // Cache location inside IDE system path
    private val cacheDir: Path by lazy {
        val systemPath = getSystemPath()
        Path.of(systemPath).resolve("hotswap-agent")
    }

    private val agentJarPath: Path by lazy { cacheDir.resolve("hotswap-agent.jar") }

    /**
     * Returns the local agent jar if present.
     */
    @Blocking
    fun getLocalAgentJar(project: Project) = runBlocking {
        scope.run {
            withModalProgress(
                ModalTaskOwner.project(project),
                Bundle.message("agent.downloading"),
                TaskCancellation.cancellable()
            ) {
                download()
            }
        }
    }

    /**
     * Coroutine-based background download with visible progress indicator.
     * fire and forget
     */
    @NonBlocking
    fun getLocalAgentJarAsync(project: Project) =
        scope.async {
            withBackgroundProgress(project, Bundle.message("agent.downloading"), TaskCancellation.cancellable()) {
                download()
            }
        }


    /**
     * Suspending variant using coroutines and optional progress indicator.
     */
    private suspend fun download(): Path? = scope.run {
        withContext(Dispatchers.IO) {
            if (Files.isRegularFile(agentJarPath)) return@withContext agentJarPath
            try {
                cacheDir.createDirectories()
                val tmp = Files.createTempFile(cacheDir, agentJarPath.name, ".tmp")
                coroutineToIndicator<Unit> { indicator ->
                    HttpRequests.request(latestJarUrl)
                        .productNameAsUserAgent()
                        .connect { request ->
                            request.saveToFile(tmp, indicator)
                        }
                }
                Files.move(
                    tmp, agentJarPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
                )
                agentJarPath
            } catch (e: IOException) {
                log.warn("Unable to download HotswapAgent", e)
                cancel("${Bundle.message("agent.download.failed")}\n${e.message}", e)
                null
            }
        }
    }

    companion object {
        fun getInstance(): HotswapAgentManager = service()
    }
}
