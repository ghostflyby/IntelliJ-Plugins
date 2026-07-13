/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.components.service
import com.intellij.openapi.util.NlsSafe
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

internal class SpotlessFormatingService : AsyncDocumentFormattingService() {
    override fun createFormattingTask(formattingRequest: AsyncFormattingRequest): FormattingTask? {
        val project = formattingRequest.context.project
        val virtualFile = formattingRequest.context.virtualFile ?: return null
        return SpotlessFormattingTask(
            service = project.service<SpotlessProjectService>(),
            virtualFile = virtualFile,
            formattingRequest = formattingRequest,
        )
    }

    private class SpotlessFormattingTask(
        private val service: SpotlessProjectService,
        private val virtualFile: VirtualFile,
        private val formattingRequest: AsyncFormattingRequest,
    ) : FormattingTask {
        private var job: Job? = null

        override fun cancel(): Boolean {
            job?.cancel()
            return true
        }

        override fun run() {
            job = service.formatAsync(
                virtualFile = virtualFile,
                content = formattingRequest.documentText,
                onResult = { result ->
                    when (result) {
                        SpotlessFormatResult.Clean,
                        SpotlessFormatResult.NotCovered,
                            -> formattingRequest.onTextReady(null)

                        is SpotlessFormatResult.Dirty -> formattingRequest.onTextReady(result.content)
                        is SpotlessFormatResult.Error -> formattingRequest.onError(
                            Bundle.message("spotless.format.notification.error.title"),
                            result.message,
                        )
                    }
                },
                onError = { error ->
                    if (error !is CancellationException) {
                        formattingRequest.onError(
                            Bundle.message("spotless.format.notification.error.title"),
                            error.message ?: error.javaClass.simpleName,
                        )
                    }
                },
            )
        }
    }

    override fun getNotificationGroupId(): String {
        return spotlessNotificationGroupId
    }

    override fun getName(): @NlsSafe String {
        return "Spotless"
    }

    override fun getFeatures(): Set<FormattingService.Feature?> = emptySet()

    override fun canFormat(file: PsiFile): Boolean {
        val virtualFile = file.viewProvider.virtualFile
        return file.project.service<SpotlessProjectService>().canFormatSync(virtualFile)
    }
}
