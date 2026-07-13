/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.openapi.components.service
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem.getInstance
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.io.IOException
import java.nio.file.Files.createDirectories
import java.nio.file.Files.writeString
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class SpotlessFormatingServiceTest : BasePlatformTestCase() {
    fun testRunReportsThrownFormatFailuresViaOnError() {
        project.service<SpotlessProjectService>().daemonProviderLookup = {
            throw IOException("transport failed")
        }
        val path = projectBasePath().resolve("src/FormattingService.kt")
        createDirectories(path.parent)
        writeString(path, "fun main() = Unit\n")
        val virtualFile = requireNotNull(getInstance().refreshAndFindFileByNioFile(path)) {
            "Failed to create VirtualFile for $path"
        }
        val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
        val context = FormattingContext.create(psiFile, CodeStyle.getSettings(psiFile))
        val request = TestAsyncFormattingRequest(context, "fun main() = Unit\n")

        runFormattingTask(SpotlessFormatingService(), request)

        assertTrue(request.finished.await(5, TimeUnit.SECONDS))
        assertNull(request.updatedText)
        assertEquals("transport failed", request.errorMessage)
        assertNotNull(request.errorTitle)
    }

    private fun projectBasePath(): Path = Path.of(requireNotNull(project.basePath))

    private fun runFormattingTask(
        service: SpotlessFormatingService,
        request: AsyncFormattingRequest,
    ) {
        val method = SpotlessFormatingService::class.java.getDeclaredMethod(
            "createFormattingTask",
            AsyncFormattingRequest::class.java,
        )
        method.isAccessible = true
        val task = requireNotNull(method.invoke(service, request)) as Runnable
        task.run()
    }

    private class TestAsyncFormattingRequest(
        private val formattingContext: FormattingContext,
        private val text: String,
    ) : AsyncFormattingRequest {
        val finished = CountDownLatch(1)
        var updatedText: String? = null
            private set
        var errorTitle: String? = null
            private set
        var errorMessage: String? = null
            private set

        override fun getDocumentText(): String = text

        override fun getIOFile(): File? = null

        override fun getFormattingRanges(): MutableList<TextRange> =
            mutableListOf(formattingContext.formattingRange)

        override fun canChangeWhitespaceOnly(): Boolean = false

        override fun isQuickFormat(): Boolean = false

        override fun getContext(): FormattingContext = formattingContext

        override fun onTextReady(updatedText: String?) {
            this.updatedText = updatedText
            finished.countDown()
        }

        override fun onError(title: String, message: String) {
            errorTitle = title
            errorMessage = message
            finished.countDown()
        }

        override fun onError(title: String, message: String, offset: Int) {
            onError(title, message)
        }
    }
}
