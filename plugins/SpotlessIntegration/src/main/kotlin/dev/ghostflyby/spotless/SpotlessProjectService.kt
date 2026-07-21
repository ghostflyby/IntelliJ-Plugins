/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.ghostflyby.spotless.SpotlessFormatResult.Error
import dev.ghostflyby.spotless.SpotlessFormatResult.NotCovered
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider
import dev.ghostflyby.spotless.api.SpotlessFormattingPreprocessor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal const val spotlessNotificationGroupId = "Spotless Notifications"

@Service(Service.Level.PROJECT)
internal class SpotlessProjectService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable {
    private val logger = logger<SpotlessProjectService>()
    private val capabilityCache get() = project.service<SpotlessCapabilityCache>()

    internal var client: SpotlessDaemonClient = SpotlessDaemonClient()
    internal val daemonManager = SpotlessDaemonManager(project, scope) { client }
    internal var daemonProvidersLookup: (Project) -> List<SpotlessDaemonProvider>
        get() = daemonManager.providersLookup
        set(value) {
            daemonManager.providersLookup = value
        }

    internal val daemonStatus: StateFlow<SpotlessDaemonStatusSnapshot>
        get() = daemonManager.snapshot

    internal fun refreshDaemonProviders() {
        daemonManager.refresh()
    }

    internal suspend fun releaseAllDaemons(): Int =
        daemonManager.releaseAllDaemons()

    internal fun releaseAllDaemonsAsync(onReleased: (Int) -> Unit = {}): Job =
        daemonManager.releaseAllDaemonsAsync(onReleased)

    internal fun releaseDaemons(provider: SpotlessDaemonProvider): Job =
        daemonManager.releaseDaemons(provider)

    internal fun releaseDaemon(
        provider: SpotlessDaemonProvider,
        externalProject: Path,
    ): Job = daemonManager.releaseDaemon(provider, externalProject)

    internal fun restartDaemon(
        provider: SpotlessDaemonProvider,
        externalProject: Path,
    ): Job = daemonManager.restartDaemon(provider, externalProject)

    internal fun hasRunningDaemons(): Boolean =
        daemonManager.hasRunningDaemons()

    internal fun formatAsync(
        psiFile: PsiFile,
        content: CharSequence,
        onResult: (SpotlessFormatResult) -> Unit,
        onError: (Throwable) -> Unit,
    ): Job =
        scope.launch(Dispatchers.IO) {
            try {
                onResult(format(psiFile, content))
            } catch (error: Throwable) {
                onError(error)
            }
        }

    internal suspend fun format(
        psiFile: PsiFile,
        content: CharSequence,
    ): SpotlessFormatResult = format(psiFile, content, expectedCacheRevision = null)

    private suspend fun format(
        psiFile: PsiFile,
        content: CharSequence,
        expectedCacheRevision: Long?,
    ): SpotlessFormatResult {
        val providerTarget = daemonManager.resolveTarget(psiFile.viewProvider.virtualFile)
        if (providerTarget == null) {
            capabilityCache.update(
                psiFile.viewProvider.virtualFile,
                externalProject = null,
                result = NotCovered,
                strictProbe = content.isEmpty(),
                expectedRevision = expectedCacheRevision,
            )
            return NotCovered
        }
        val target = providerTarget.target

        val daemon = daemonManager.getDaemon(providerTarget)
        val result = if (!client.healthCheck(daemon)) {
            Error("Spotless Daemon is not responding")
        } else {
            val request = preprocessFormatRequest(daemon, target.file, psiFile, content)
            client.format(daemon, target.file, request.content, request.skipSteps)
        }
        if (content.isNotEmpty() && result is Error) {
            capabilityCache.invalidate(psiFile.viewProvider.virtualFile)
        }
        capabilityCache.update(
            psiFile.viewProvider.virtualFile,
            target.externalProject,
            result,
            strictProbe = content.isEmpty(),
            expectedRevision = expectedCacheRevision,
        )
        return result
    }

    internal suspend fun canFormat(psiFile: PsiFile): Boolean =
        format(psiFile, "") == SpotlessFormatResult.Clean

    internal fun canFormatSync(
        psiFile: PsiFile,
        timeout: Duration = 500.milliseconds,
    ): Boolean {
        val cached = capabilityCache.cachedCanFormat(psiFile.viewProvider.virtualFile)
        if (cached != null) {
            if (cached.shouldRefresh) {
                scheduleCanFormatRefresh(psiFile, timeout)
            }
            return cached.canFormat
        }
        scheduleCanFormatRefresh(psiFile, timeout)
        return false
    }

    override fun dispose() {
        daemonManager.dispose()
        runCatching {
            client.close()
        }.onFailure { error ->
            logger.warn("Failed to close Spotless HTTP client", error)
        }
    }

    private fun scheduleCanFormatRefresh(
        psiFile: PsiFile,
        timeout: Duration,
    ) {
        val cacheRevision = capabilityCache.currentRevision()
        val virtualFile = psiFile.viewProvider.virtualFile
        val refreshJob = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val job = currentCoroutineContext().job
            try {
                withTimeoutOrNull(timeout) {
                    format(psiFile, "", expectedCacheRevision = cacheRevision)
                }
            } finally {
                capabilityCache.finishRefresh(virtualFile, job)
            }
        }
        if (!capabilityCache.tryStartRefresh(virtualFile, refreshJob)) {
            refreshJob.cancel()
            return
        }
        refreshJob.start()
    }

    private suspend fun preprocessFormatRequest(
        daemon: SpotlessDaemonProvider.Endpoint,
        path: Path,
        psiFile: PsiFile,
        content: CharSequence,
    ): FormatRequest {
        if (content.isEmpty()) {
            return FormatRequest(content)
        }
        val target = findPreprocessingTarget(psiFile) ?: return FormatRequest(content)
        val daemonSteps = runCatching {
            client.steps(daemon, path)
        }.onFailure { error ->
            logger.debug("Failed to inspect Spotless formatter steps", error)
        }.getOrNull() ?: return FormatRequest(content)
        var processedContent = content
        val skippedSteps = linkedSetOf<String>()
        target.preprocessors.forEach { preprocessor ->
            val result = runCatching {
                preprocessor.preprocess(
                    FormattingPreprocessContext(target.psiFile, processedContent, daemonSteps),
                )
            }.onFailure { error ->
                logger.debug("Failed to preprocess Spotless formatting request", error)
            }.getOrNull() ?: return@forEach
            processedContent = result.content
            skippedSteps += result.skippedSteps
        }
        return FormatRequest(
            content = processedContent,
            skipSteps = daemonSteps.filter { step -> step in skippedSteps }.distinct(),
        )
    }

    private suspend fun findPreprocessingTarget(psiFile: PsiFile): PreprocessingTarget? = readAction {
        val preprocessors = SpotlessFormattingPreprocessor.EP_NAME.extensionList.filter { preprocessor ->
            runCatching {
                preprocessor.isApplicableTo(psiFile)
            }.onFailure { error ->
                logger.debug("Failed to check Spotless formatting preprocessor applicability", error)
            }.getOrDefault(false)
        }
        preprocessors.takeIf(List<SpotlessFormattingPreprocessor>::isNotEmpty)
            ?.let { PreprocessingTarget(psiFile, it) }
    }

    private data class FormatRequest(
        val content: CharSequence,
        val skipSteps: List<String> = emptyList(),
    )

    private data class FormattingPreprocessContext(
        override val psiFile: PsiFile,
        override val content: CharSequence,
        override val daemonSteps: List<String>,
    ) : SpotlessFormattingPreprocessor.Context

    private data class PreprocessingTarget(
        val psiFile: PsiFile,
        val preprocessors: List<SpotlessFormattingPreprocessor>,
    )
}
