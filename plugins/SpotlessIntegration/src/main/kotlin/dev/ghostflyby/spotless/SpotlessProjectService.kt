/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.spotless.SpotlessFormatResult.Error
import dev.ghostflyby.spotless.SpotlessFormatResult.NotCovered
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal const val spotlessNotificationGroupId = "Spotless Notifications"

@Service(Service.Level.PROJECT)
internal class SpotlessProjectService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable.Default {
    private val logger = logger<SpotlessProjectService>()
    private val capabilityCache = project.service<SpotlessCapabilityCache>()
    private val registry = project.service<SpotlessDaemonRegistry>()

    internal var client: SpotlessDaemonClient = SpotlessDaemonClient()
        set(value) {
            field = value
            registry.clientProvider = { field }
        }
    internal var daemonProviderLookup: (Project) -> SpotlessDaemonProvider? =
        { currentProject -> EP_NAME.findFirstSafe { it.isApplicableTo(currentProject) } }

    init {
        registry.clientProvider = { client }
    }

    internal fun releaseAllDaemons(): Int =
        registry.releaseAllDaemons()

    internal fun hasRunningDaemons(): Boolean =
        registry.hasRunningDaemons()

    internal fun formatAsync(
        virtualFile: VirtualFile,
        content: CharSequence,
        onResult: (SpotlessFormatResult) -> Unit,
        onError: (Throwable) -> Unit,
    ): Job =
        scope.launch(Dispatchers.IO) {
            try {
                onResult(format(virtualFile, content))
            } catch (error: Throwable) {
                onError(error)
            }
        }

    internal suspend fun format(
        virtualFile: VirtualFile,
        content: CharSequence,
    ): SpotlessFormatResult {
        val provider = daemonProviderLookup(project)
        val target = provider?.findTarget(project, virtualFile)
        if (provider == null || target == null) {
            capabilityCache.update(
                virtualFile,
                externalProject = null,
                result = NotCovered,
                strictProbe = content.isEmpty(),
            )
            return NotCovered
        }

        val daemon = registry.getDaemon(provider, target.externalProject)
        val result = if (!client.healthCheck(daemon)) {
            Error("Spotless Daemon is not responding")
        } else {
            client.format(daemon, target.file, content)
        }
        if (!content.isEmpty() && result is Error) {
            capabilityCache.invalidate(virtualFile)
        }
        capabilityCache.update(
            virtualFile,
            target.externalProject,
            result,
            strictProbe = content.isEmpty(),
        )
        return result
    }

    internal suspend fun canFormat(virtualFile: VirtualFile): Boolean =
        format(virtualFile, "") == SpotlessFormatResult.Clean

    internal fun canFormatSync(
        virtualFile: VirtualFile,
        timeout: Duration = 500.milliseconds,
    ): Boolean {
        val cached = capabilityCache.cachedCanFormat(virtualFile)
        if (cached != null) {
            if (cached.shouldRefresh) {
                scheduleCanFormatRefresh(virtualFile, timeout)
            }
            return cached.canFormat
        }
        scheduleCanFormatRefresh(virtualFile, timeout)
        return false
    }

    override fun dispose() {
        registry.dispose()
        runCatching {
            client.close()
        }.onFailure { error ->
            logger.warn("Failed to close Spotless HTTP client", error)
        }
    }

    private fun scheduleCanFormatRefresh(
        virtualFile: VirtualFile,
        timeout: Duration,
    ) {
        if (!capabilityCache.tryStartRefresh(virtualFile)) {
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(timeout) {
                    format(virtualFile, "")
                }
            } finally {
                capabilityCache.finishRefresh(virtualFile)
            }
        }
    }

    private companion object {
        @JvmStatic
        val EP_NAME: ExtensionPointName<SpotlessDaemonProvider> =
            ExtensionPointName.create("dev.ghostflyby.spotless.spotlessDaemonProvider")
    }
}
