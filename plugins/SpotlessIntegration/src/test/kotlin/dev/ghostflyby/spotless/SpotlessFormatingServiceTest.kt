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

package dev.ghostflyby.spotless

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem.getInstance
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.registerOrReplaceServiceInstance
import java.io.File
import java.io.IOException
import java.nio.file.Files.createDirectories
import java.nio.file.Files.writeString
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class SpotlessFormatingServiceTest : BasePlatformTestCase() {
    fun testRunReportsThrownFormatFailuresViaOnError() {
        ApplicationManager.getApplication().registerOrReplaceServiceInstance(
            Spotless::class.java,
            object : Spotless {
                override fun releaseDaemon(host: SpotlessDaemonHost) = Unit

                override suspend fun format(
                    project: com.intellij.openapi.project.Project,
                    virtualFile: VirtualFile,
                    content: CharSequence,
                ): SpotlessFormatResult {
                    throw IOException("transport failed")
                }

                override suspend fun canFormat(
                    project: com.intellij.openapi.project.Project,
                    virtualFile: VirtualFile,
                ): Boolean = false

                override fun canFormatSync(
                    project: com.intellij.openapi.project.Project,
                    virtualFile: VirtualFile,
                    timeout: kotlin.time.Duration,
                ): Boolean = false
            },
            testRootDisposable,
        )
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
