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
import com.intellij.openapi.extensions.ExtensionPointListener
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import dev.ghostflyby.spotless.SpotlessFormatResult.Error
import dev.ghostflyby.spotless.SpotlessFormatResult.NotCovered
import kotlinx.coroutines.*
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

internal const val spotlessNotificationGroupId = "Spotless Notifications"

@Service(Service.Level.PROJECT)
internal class SpotlessProjectService(
    private val project: Project,
    private val scope: CoroutineScope,
) : Disposable.Default {
    private val logger = logger<SpotlessProjectService>()
    private val capabilityCache get() = project.service<SpotlessCapabilityCache>()
    private val registry get() = project.service<SpotlessDaemonRegistry>()

    internal var client: SpotlessDaemonClient = SpotlessDaemonClient()
        set(value) {
            field = value
            registry.clientProvider = { field }
        }
    internal var daemonProvidersLookup: (Project) -> List<SpotlessDaemonProvider> =
        { EP_NAME.extensionList }

    init {
        registry.clientProvider = { client }
        EP_NAME.point.addExtensionPointListener(
            scope,
            false,
            object : ExtensionPointListener<SpotlessDaemonProvider> {
                override fun extensionRemoved(extension: SpotlessDaemonProvider, pluginDescriptor: PluginDescriptor) {
                    registry.releaseDaemonsForProviderSynchronously(extension)
                }
            },
        )
    }

    internal suspend fun releaseAllDaemons(): Int =
        registry.releaseAllDaemons()

    internal fun releaseAllDaemonsAsync(onReleased: (Int) -> Unit = {}): Job =
        scope.launch(Dispatchers.IO) {
            onReleased(releaseAllDaemons())
        }

    internal fun hasRunningDaemons(): Boolean =
        registry.hasRunningDaemons()

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
    ): SpotlessFormatResult {
        val providerTarget = resolveProviderTarget(psiFile.viewProvider.virtualFile)
        if (providerTarget == null) {
            capabilityCache.update(
                psiFile.viewProvider.virtualFile,
                externalProject = null,
                result = NotCovered,
                strictProbe = content.isEmpty(),
            )
            return NotCovered
        }
        val (provider, target) = providerTarget

        val daemon = registry.getDaemon(provider, target.externalProject)
        val result = if (!client.healthCheck(daemon)) {
            Error("Spotless Daemon is not responding")
        } else {
            val request = preprocessFormatRequest(daemon, target.file, psiFile, content)
            client.format(daemon, target.file, request.content, request.skipSteps)
        }
        if (!content.isEmpty() && result is Error) {
            capabilityCache.invalidate(psiFile.viewProvider.virtualFile)
        }
        capabilityCache.update(
            psiFile.viewProvider.virtualFile,
            target.externalProject,
            result,
            strictProbe = content.isEmpty(),
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
        registry.dispose()
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
        if (!capabilityCache.tryStartRefresh(psiFile.viewProvider.virtualFile)) {
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                withTimeoutOrNull(timeout) {
                    format(psiFile, "")
                }
            } finally {
                capabilityCache.finishRefresh(psiFile.viewProvider.virtualFile)
            }
        }
    }

    private suspend fun preprocessFormatRequest(
        daemon: SpotlessDaemonEndpoint,
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
        val preprocessors = PREPROCESSOR_EP.extensionList.filter { preprocessor ->
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

    private data class ProviderTarget(
        val provider: SpotlessDaemonProvider,
        val target: SpotlessDaemonTarget,
    )

    private data class FormattingPreprocessContext(
        override val psiFile: PsiFile,
        override val content: CharSequence,
        override val daemonSteps: List<String>,
    ) : SpotlessFormattingPreprocessContext

    private data class PreprocessingTarget(
        val psiFile: PsiFile,
        val preprocessors: List<SpotlessFormattingPreprocessor>,
    )

    private fun resolveProviderTarget(file: VirtualFile): ProviderTarget? {
        daemonProvidersLookup(project).forEach { provider ->
            val target = runCatching {
                provider.resolveTarget(project, file)
            }.onFailure { error ->
                logger.debug("Failed to resolve Spotless daemon target", error)
            }.getOrNull()
            if (target != null) {
                return ProviderTarget(provider, target)
            }
        }
        return null
    }

    private companion object {
        @JvmStatic
        val EP_NAME: ExtensionPointName<SpotlessDaemonProvider> =
            ExtensionPointName.create("dev.ghostflyby.spotless.spotlessDaemonProvider")

        @JvmStatic
        val PREPROCESSOR_EP: ExtensionPointName<SpotlessFormattingPreprocessor> =
            ExtensionPointName.create("dev.ghostflyby.spotless.spotlessFormattingPreprocessor")
    }
}
