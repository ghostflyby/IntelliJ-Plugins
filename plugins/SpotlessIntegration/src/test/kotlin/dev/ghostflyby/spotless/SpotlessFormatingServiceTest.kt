/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.application.options.CodeStyle
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.*
import dev.ghostflyby.spotless.api.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

@TestApplication
internal class SpotlessFormatingServiceTest {
    private val projectPathFixture = tempPathFixture()
    private val projectFixture = projectFixture(pathFixture = projectPathFixture, openAfterCreation = true)
    private val project by projectFixture
    private val sourceRootFixture = projectFixture
        .moduleFixture(name = "spotless-formatting-test")
        .sourceRootFixture(
            pathFixture = projectFixture.pathInProjectFixture(Path.of("src")),
        )
    private val formattingFileFixture = sourceRootFixture.psiFileFixture(
        "FormattingService.kt",
        "fun main() = Unit\n",
    )
    private val formattingFile by formattingFileFixture

    @Test
    suspend fun `run reports thrown format failures via on error`() {
        val externalProject = projectPathFixture.get().toAbsolutePath().normalize()
        val providerState = MutableStateFlow(
            SpotlessDaemonProviderState(
                projects = listOf(
                    ExternalProject(externalProject, generation = 0),
                ),
            ),
        )
        project.service<SpotlessProjectService>().daemonProvidersLookup = {
            listOf(
                object : SpotlessDaemonProvider {
                    override val id: String = "dev.ghostflyby.spotless.test.throwing"

                    override fun state(project: Project): StateFlow<SpotlessDaemonProviderState> = providerState

                    override fun resolveTarget(project: Project, file: VirtualFile): SpotlessDaemonTarget =
                        SpotlessDaemonTarget(externalProject, file.toNioPath())

                    override suspend fun startDaemon(
                        context: SpotlessDaemonStartContext,
                    ): SpotlessDaemonHandle {
                        throw IOException("transport failed")
                    }
                },
            )
        }
        val context = readAction {
            FormattingContext.create(formattingFile, CodeStyle.getSettings(formattingFile))
        }
        val request = TestAsyncFormattingRequest(context, "fun main() = Unit\n")
        val task = requireNotNull(TestableSpotlessFormatingService().createTask(request))

        task.run()

        withTimeout(5.seconds) {
            request.finished.await()
        }
        assertNull(request.updatedText)
        assertEquals("transport failed", request.errorMessage)
        assertNotNull(request.errorTitle)
    }

    private class TestAsyncFormattingRequest(
        private val formattingContext: FormattingContext,
        private val text: String,
    ) : AsyncFormattingRequest {
        val finished = CompletableDeferred<Unit>()
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
            finished.complete(Unit)
        }

        override fun onError(title: String, message: String) {
            errorTitle = title
            errorMessage = message
            finished.complete(Unit)
        }

        override fun onError(title: String, message: String, offset: Int) {
            onError(title, message)
        }
    }

    private class TestableSpotlessFormatingService : SpotlessFormatingService() {
        fun createTask(request: AsyncFormattingRequest): FormattingTask? = createFormattingTask(request)
    }
}
